(ns metabase.driver.motherduck
  "MotherDuck driver.

  MotherDuck speaks the Postgres wire protocol, so this driver reuses the Postgres JDBC client and
  the Postgres driver's query-execution behavior by parenting on `:postgres`. The one thing that is
  *not* Postgres-compatible is the catalog: the backend is DuckDB, and a single connection can see
  many databases. Sync/metadata multimethods are therefore overridden here to use DuckDB `duckdb_*`
  metadata functions scoped to `database_name = current_database()`, which cleanly excludes the
  `system`/`temp` databases and all built-in/internal objects.

  See PLAN.md (phases T5/§4/§5) for the validated rewrite SQL and the DuckDB type map."
  (:require
   [clojure.string :as str]
   [honey.sql :as sql]
   [metabase.driver :as driver]
   ;; ensure the parent driver is loaded before we register against it
   metabase.driver.postgres
   [metabase.driver.sql-jdbc :as sql-jdbc]
   [metabase.driver.sql-jdbc.connection :as sql-jdbc.conn]
   [metabase.driver.sql-jdbc.execute :as sql-jdbc.execute]
   [metabase.driver.sql-jdbc.quoting :as sql-jdbc.quoting]
   [metabase.driver.sql-jdbc.sync :as sql-jdbc.sync]
   [metabase.driver.sql.query-processor :as sql.qp]
   [metabase.driver.sql.query-processor.util :as sql.qp.u]
   [metabase.driver.sql.util :as sql.u]
   [metabase.util :as u]
   [metabase.util.date-2 :as u.date]
   [metabase.util.honey-sql-2 :as h2x])
  (:import
   (java.sql Connection ResultSet Types)))

(set! *warn-on-reflection* true)

(driver/register! :motherduck, :parent :postgres)

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                                  Connection                                                     |
;;; +----------------------------------------------------------------------------------------------------------------+

(defmethod sql-jdbc.conn/connection-details->spec :motherduck
  [_driver details]
  ;; MotherDuck's Postgres endpoint (pg.<region>-aws.motherduck.com:5432) *requires* an encrypted
  ;; connection; a plaintext attempt just hangs until the client times out. Force SSL on and use
  ;; `sslmode=require`, which encrypts the connection but does NOT attempt to verify the server
  ;; certificate or hostname.
  ;;
  ;; `verify-full` (encrypt + validate the cert chain and hostname against the JVM trust store) was
  ;; tried first but the connection would hang/time out against the MotherDuck endpoint. `require` is
  ;; confirmed working with the Postgres JDBC driver against MotherDuck, so we start there.
  ;;
  ;; Passing `:ssl true` to the Postgres `connection-details->spec` already yields `sslmode=require`
  ;; when no explicit ssl-mode is set; we set it explicitly here to be unambiguous.
  ;;
  ;; `options=--compatibility-mode=metabase` is forwarded to the gateway as a startup packet option
  ;; (like `PGOPTIONS`), opting the connection into MotherDuck-gateway code paths written specifically
  ;; for Metabase (e.g. quoting array elements the way real Postgres does).
  (-> (sql-jdbc.conn/connection-details->spec :postgres (assoc details :ssl true))
      (assoc :sslmode "require"
             :options "--compatibility-mode=metabase")))

;; Real Postgres signals "table does not exist" with SQLSTATE `42P01`; the Postgres impl of
;; `impl-table-known-to-not-exist?` checks for exactly that. MotherDuck's gateway forwards DuckDB's own
;; "Catalog Error" for the same condition without that SQLSTATE, so the Postgres check never matches and
;; the exception propagates instead of `driver/table-exists?` returning `false`. Match on the DuckDB
;; catalog error text instead.
(defmethod sql-jdbc/impl-table-known-to-not-exist? :motherduck
  [_driver e]
  (boolean (re-find #"(?i)catalog error.*does not exist" (or (.getMessage ^java.sql.SQLException e) ""))))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                              Feature flags                                                      |
;;; +----------------------------------------------------------------------------------------------------------------+

;; Start conservative. Sync essentials (`:describe-fields`, `:describe-fks`, `:describe-is-nullable`,
;; `:describe-default-expr`, `:schemas`, `:set-timezone`, `:basic-aggregations`) are inherited `true`
;; from Postgres. Actions, table-privileges and database-replication are already `false` for
;; non-`:postgres` drivers (see the `(= driver :postgres)` methods in `metabase.driver.postgres`).
;; Everything below is disabled either because it isn't implemented against the DuckDB catalog yet, or
;; because we don't emit (or can't trust) the corresponding metadata.
(doseq [feature [:describe-indexes            ; no index sync (we don't override describe-indexes-sql)
                 ;; DuckDB DOES create the column when the test-data loader emits `GENERATED ALWAYS AS
                 ;; (...)`, but the inherited `describe-fields-sql` (`information_schema.columns.is_generated`)
                 ;; comes back `false`/"NEVER" for it over the MotherDuck gateway regardless -- confirmed
                 ;; by `describe-fields-returns-is-generated-test` failing with `[false false false]`
                 ;; instead of `[false true false]`. `:database-is-generated` is stripped in
                 ;; `describe-fields-pre-process-xf` below so this inaccurate value never surfaces.
                 :describe-is-generated
                 :uploads
                 :persist-models
                 :database-routing
                 :connection-impersonation
                 :nested-field-columns
                 ;; FKs can be *read* (see describe-fks-sql), but the test-data loader can't create
                 ;; them (DuckDB has no `ALTER TABLE ... ADD FOREIGN KEY`), so disable FK sync for now.
                 :metadata/key-constraints
                 :transforms/table
                 :transforms/python
                 :transforms/index-ddl
                 ;; DuckDB's regex engine (RE2, like BigQuery/Clickhouse/Presto/Redshift/Vertica/Athena
                 ;; -- see their `:regex/lookaheads-and-lookbehinds false` overrides) doesn't support
                 ;; Perl-style lookahead/lookbehind assertions. `:host`/`:domain`/`:subdomain`/`:path`
                 ;; column extractions desugar to `regex-match-first` with hardcoded lookaround regexes
                 ;; (`metabase.lib.filter.desugar.jvm`), which DuckDB's `regexp_extract` rejects outright:
                 ;; "Invalid Input Error: invalid perl operator: (?<". Same root cause, same fix as the
                 ;; other RE2-backed drivers -- this feature flag gates those tests off
                 ;; (`mt/normal-drivers-with-feature :expressions :regex/lookaheads-and-lookbehinds`).
                 :regex/lookaheads-and-lookbehinds]]
  (defmethod driver/database-supports? [:motherduck feature]
    [_driver _feature _db]
    false))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                             Metadata / sync                                                     |
;;; +----------------------------------------------------------------------------------------------------------------+

(def ^:private describe-database-tables-sql
  ;; Tables + views for the connection's single database. `current_database()` scoping excludes the
  ;; `system`/`temp` databases and all internal objects; views additionally filter `internal = false`.
  ;; A vector so params can be added later without touching `describe-database*`.
  [(str/join
    "\n"
    ["SELECT schema_name AS \"schema\", table_name AS \"name\", comment AS \"description\""
     "FROM duckdb_tables()"
     "WHERE database_name = current_database()"
     "UNION ALL"
     "SELECT schema_name, view_name, comment"
     "FROM duckdb_views()"
     "WHERE database_name = current_database() AND internal = false"])])

(defmethod driver/describe-database* :motherduck
  [_driver database]
  {:tables (into #{} (sql-jdbc.execute/reducible-query database describe-database-tables-sql))})

;; No `:motherduck` override: the inherited `:postgres` `describe-fields-sql` (an
;; `information_schema.columns` query, `udt_name` as `:database-type`) works as-is against the
;; MotherDuck gateway and returns the same lower-case pg type names query execution does, so
;; `database-type->base-type` can be inherited unmodified too (see below). Removed the DuckDB-native
;; `duckdb_columns()`/`duckdb_constraints()` version and the `database-type->base-type` override that
;; existed solely to reconcile its upper-case DuckDB type strings with Postgres's lower-case map.
;; TODO: re-add a `:motherduck` override if tests turn up cases the inherited query gets wrong
;; (e.g. the `pg_catalog` materialized-view branch, `col_description()`, or identity/autoincrement
;; detection, none of which are known to work against DuckDB's pg-wire emulation).

;; Skip the Postgres implementation, which tags columns whose type is a Postgres enum; DuckDB has no
;; such dynamic types. `:database-is-generated` is also stripped here: the inherited `describe-fields-sql`
;; still computes it (`information_schema.columns.is_generated`), but it comes back inaccurate over the
;; MotherDuck gateway (see the `:describe-is-generated` feature flag above), so drop it rather than
;; surface a wrong value.
(defmethod sql-jdbc.sync/describe-fields-pre-process-xf :motherduck
  [_driver _database & _args]
  (map #(dissoc % :database-is-generated)))

;; No Postgres enums to look up.
(defmethod driver/dynamic-database-types-lookup :motherduck
  [_driver _database _database-types]
  nil)

(defmethod sql-jdbc.sync/describe-fks-sql :motherduck
  [driver & {:keys [schema-names table-names]}]
  ;; `duckdb_constraints()` has no *referenced schema* column, so we assume the referenced (PK) table
  ;; lives in the same schema as the FK table. `UNNEST` expands the multi-column list columns to one
  ;; row per column. This is shipped for correctness even though FK sync is disabled by default
  ;; (`:metadata/key-constraints` is false) because the test loader can't create FK constraints.
  (sql/format
   {:select   [[:schema_name :fk-table-schema]
               [:table_name  :fk-table-name]
               [[:unnest :constraint_column_names] :fk-column-name]
               [:schema_name :pk-table-schema]
               [:referenced_table :pk-table-name]
               [[:unnest :referenced_column_names] :pk-column-name]]
    :from     [[[:duckdb_constraints] :c]]
    :where    [:and
               [:= :constraint_type [:inline "FOREIGN KEY"]]
               [:= :database_name [:current_database]]
               (when (seq schema-names) [:in :schema_name schema-names])
               (when (seq table-names) [:in :table_name table-names])]
    :order-by [:schema_name :table_name]}
   :dialect (sql.qp/quote-style driver)))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                                Type mapping                                                     |
;;; +----------------------------------------------------------------------------------------------------------------+

;; No `:motherduck` override: `describe-fields-sql` now feeds `database-type->base-type` the same
;; lower-case `udt_name`/pg wire type strings (`int4`, `varchar`, `timestamptz`, ...) whether the value
;; came from sync or from query execution, so the inherited `:postgres` map (and its `column->semantic-type`,
;; keyed on lower-case `"json"`) applies unmodified.
;; TODO: re-add DuckDB-only type handling (`HUGEINT`, `STRUCT`/`MAP`/`UNION`, array types, etc.) here if
;; sync tests turn up types Postgres's map doesn't recognize.

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                              Query processing                                                   |
;;; +----------------------------------------------------------------------------------------------------------------+

;; A string literal compiles to a bare parameter placeholder (`?`). MotherDuck's Postgres gateway
;; can't deduce types involving an untyped parameter and rejects the prepared statement with
;; "ambiguous result column types" whenever one determines a result column's type — a literal text
;; expression (`SELECT ? AS "foo"`), a `CASE ... THEN ?` branch, a `CONCAT(col, ?)` arg, etc. An
;; explicit CAST gives the gateway the type it needs (the narrow `::sql.qp/expression-literal-text-value`
;; fix Redshift/Vertica use is subsumed by this: that clause compiles down to a plain string too, but
;; alone it misses strings that reach `->honeysql` un-wrapped, e.g. `:case`/`:day-name` values).
;; Numbers and booleans get inlined upstream, so strings are the only ambiguous literals left.
(defmethod sql.qp/->honeysql [:motherduck String]
  [_driver s]
  (h2x/cast :text s))

;; Postgres compiles `:regex-match-first` to `substring(expr FROM pattern)` — a Postgres-only
;; two-arg POSIX-regex overload of `substring`. DuckDB's `substring` has no such overload (only
;; positional `substring(str, start[, len])`), so it silently tries to coerce the pattern string to
;; an integer position and fails. Same fix as the DuckDB community driver
;; (`modules/drivers/duckdb/src/metabase/driver/duckdb.clj`): DuckDB's native `regexp_extract`
;; (default group 0 = whole match) is the direct equivalent.
(defmethod sql.qp/->honeysql [:motherduck :regex-match-first]
  [driver [_ arg pattern]]
  [:regexp_extract (sql.qp/->honeysql driver arg) (sql.qp/->honeysql driver pattern)])

;; Postgres parses a `YYYYMMDDHH24MISS`-formatted string with the 2-arg `to_timestamp(text, text)`
;; format-string overload, which parses the fields then interprets them as local time in the session
;; TimeZone to produce an absolute `timestamptz`; DuckDB's `to_timestamp` only has a 1-arg
;; `(double) -> timestamptz` (Unix epoch) overload, so the inherited Postgres impl 404s ("No function
;; matches ... to_timestamp(STRING, STRING)"). DuckDB's own string-with-format parser is `strptime`,
;; using strftime-style `%` directives, but it only returns a zone-less `timestamp`. Casting that to
;; `TIMESTAMPTZ` reproduces Postgres's exact semantics: DuckDB interprets the naive value as local time
;; in the session TimeZone (confirmed live: `CAST(strptime(...) AS TIMESTAMPTZ)` under session tz
;; America/New_York shifts the instant by the zone's offset, same as Postgres's `to_timestamp`) —
;; `:set-timezone` is inherited unchanged from `:postgres`, so both drivers have the session TimeZone
;; set the same way. This is why `:motherduck` sits alongside `:postgres`/`:h2`/`:databricks` (not
;; `:mysql`/`:sqlserver`/`:presto-jdbc`, which can't produce a real `timestamptz` here) in the affected
;; tests.
(defmethod sql.qp/cast-temporal-string [:motherduck :Coercion/YYYYMMDDHHMMSSString->Temporal]
  [_driver _coercion-strategy expr]
  (h2x/cast "timestamptz" [:strptime expr (h2x/literal "%Y%m%d%H%M%S")]))

;; Postgres casts a BYTEA column to text with `convert_from(expr, 'UTF8')`; DuckDB has no
;; `convert_from` (it errors "Scalar Function with name convert_from does not exist"). DuckDB's own
;; BLOB->VARCHAR decoder is `decode`, which assumes UTF8 same as Postgres's call here.
(defmethod sql.qp/cast-temporal-byte [:motherduck :Coercion/YYYYMMDDHHMMSSBytes->Temporal]
  [driver _coercion-strategy expr]
  (sql.qp/cast-temporal-string driver :Coercion/YYYYMMDDHHMMSSString->Temporal [:decode expr]))

(defmethod sql.qp/cast-temporal-byte [:motherduck :Coercion/ISO8601Bytes->Temporal]
  [driver _coercion-strategy expr]
  (sql.qp/cast-temporal-string driver :Coercion/ISO8601->DateTime [:decode expr]))

;; The Postgres implementations of these two methods delegate to `h2x` helpers that dispatch on the
;; *db-type keyword* using the global hierarchy, which knows nothing about driver parentage — so they
;; blow up on `:motherduck`. DuckDB understands the same `now()` / `expr + INTERVAL 'n unit'` SQL that
;; those helpers emit for Postgres, so delegate with an explicit `:postgres` db-type.
(defmethod sql.qp/current-datetime-honeysql-form :motherduck
  [_driver]
  (h2x/current-datetime-honeysql-form :postgres))

(defmethod sql.qp/add-interval-honeysql-form :motherduck
  [_driver hsql-form amount unit]
  (h2x/add-interval-honeysql-form :postgres hsql-form amount unit))

;; This override compiles to:
;;   TIMEZONE('America/Los_Angeles', TIMEZONE('UTC', CAST("my_field" AS timestamp)))
;; The inherited Postgres implementation compiles to:
;;   TIMEZONE(?, TIMEZONE(?, "my_field"))
;; which MotherDuck's gateway rejects a bare `?` param inside a scalar function's argument list 
;; because in these cases the bind phase is insufficient to figure out the type of these params. 
;; Timezone names are rendered as inline string literals instead of params. The
;; source-timezone-only branch's datetime arg gets the same treatment: converting a literal
;; (`(convert-timezone "2024-01-01 00:00:00" "America/Los_Angeles" "UTC")`) would otherwise compile
;; the literal to a bare `?` too --
;;   TIMEZONE('America/Los_Angeles', TIMEZONE('UTC', ?))
;; -- so it's wrapped in an explicit cast instead:
;;   TIMEZONE('America/Los_Angeles', TIMEZONE('UTC', CAST(? AS timestamp)))
(defmethod sql.qp/->honeysql [:motherduck :convert-timezone]
  [driver [_ arg target-timezone source-timezone]]
  (let [expr         (sql.qp/->honeysql driver (cond-> arg
                                                 (string? arg) u.date/parse))
        timestamptz? (or (sql.qp.u/field-with-tz? arg)
                         (h2x/is-of-type? expr "timestamptz")
                         (h2x/is-of-type? expr "timestamp with time zone"))
        _            (sql.u/validate-convert-timezone-args timestamptz? target-timezone source-timezone)
        expr         [:timezone (h2x/literal target-timezone)
                      (if-not timestamptz?
                        [:timezone (h2x/literal source-timezone) (h2x/->pg-timestamp expr)]
                        expr)]]
    (h2x/with-database-type-info expr "timestamp")))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                              Result reading                                                     |
;;; +----------------------------------------------------------------------------------------------------------------+

;; TODO: The quote issue will be fixed in motherduck Postgres Endpoint- will be able to remove this patch later. 
(defn- parse-array-literal
  "Parse a Postgres/DuckDB array text literal (e.g. `{a,b,\"c,d\"}`, possibly nested) into a Clojure
  vector. `NULL` elements become `nil`, nested `{...}` become nested vectors.

  Why we can't use the JDBC driver's own parser: MotherDuck's Postgres endpoint returns array
  elements *unquoted* even when they contain spaces (e.g. `{Chez Jay,Musso & Frank}`), whereas real
  Postgres quotes them (`{\"Chez Jay\",...}`). `org.postgresql`'s `PgArray.getArray()` therefore
  treats the interior whitespace as insignificant and collapses it (`Chez Jay` -> `ChezJay`). The raw
  string from `ResultSet.getString` is intact, so we parse that ourselves."
  [^String s]
  (let [n (count s)]
    (letfn [(parse-elements [i]                             ; i points just past an opening '{'
              (loop [i i, acc []]
                (cond
                  (>= i n)                  [acc i]
                  (= (.charAt s i) \})      [acc (inc i)]
                  (= (.charAt s i) \,)      (recur (inc i) acc)
                  (= (.charAt s i) \{)      (let [[sub j] (parse-elements (inc i))]
                                              (recur j (conj acc sub)))
                  (= (.charAt s i) \")      (let [[v j] (parse-quoted (inc i))]
                                              (recur j (conj acc v)))
                  :else                     (let [[v j] (parse-unquoted i)]
                                              (recur j (conj acc v))))))
            (parse-quoted [i]                               ; i points just past the opening '"'
              (let [sb (StringBuilder.)]
                (loop [i i]
                  (let [c (.charAt s i)]
                    (cond
                      (= c \\) (do (.append sb (.charAt s (inc i))) (recur (+ i 2)))
                      (= c \") [(.toString sb) (inc i)]
                      :else    (do (.append sb c) (recur (inc i))))))))
            (parse-unquoted [i]
              (loop [j i]
                (if (or (>= j n) (contains? #{\, \}} (.charAt s j)))
                  (let [tok (str/trim (subs s i j))]
                    [(when-not (= "NULL" (u/upper-case-en tok)) tok) j])
                  (recur (inc j)))))]
      (when (and (pos? n) (= \{ (.charAt s 0)))
        (first (parse-elements 1))))))

;; Read array columns (`Types/ARRAY`, e.g. `array_agg(...)`) from the raw text literal rather than via
;; `PgArray.getArray()`, which mangles MotherDuck's unquoted whitespace-containing elements. See
;; [[parse-array-literal]].
(defmethod sql-jdbc.execute/read-column-thunk [:motherduck Types/ARRAY]
  [_driver ^ResultSet rs _rsmeta ^Integer i]
  (fn []
    (some-> (.getString rs i) parse-array-literal)))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                          Driver-managed table DDL                                                |
;;; +----------------------------------------------------------------------------------------------------------------+

;; The `:sql-jdbc` default impls of `create-table!`/`drop-table!`/`insert-into!`/`rename-tables!*` run their SQL
;; through `clojure.java.jdbc`, which calls pgjdbc's `executeUpdate`/`executeBatch` -- both reject the result set
;; MotherDuck's gateway returns for *every* statement, DDL included (see AGENTS.md). Re-implemented here with a
;; raw `Statement`/`PreparedStatement` and `.execute()`, which permits (and ignores) the returned result set --
;; same fix already applied to the test-data loader's `execute-sql!`/`do-insert!`
;; (`test/metabase/test/data/motherduck.clj`), needed again here because these are separate multimethods
;; (driver-managed table actions used e.g. by `driver.sql-jdbc-test`, not test-data loading).
(defn- raw-execute!
  "Run `sql` (a single statement, no params) against `db-or-id` via a raw `Statement.execute`."
  [driver db-or-id sql]
  (sql-jdbc.execute/do-with-connection-with-options
   driver db-or-id {:write? true}
   (fn [^Connection conn]
     (with-open [stmt (.createStatement conn)]
       (.execute stmt ^String sql)))))

(defmethod driver/create-table! :motherduck
  [driver database-id table-name column-definitions & {:keys [primary-key]}]
  (raw-execute!
   driver database-id
   (sql-jdbc.quoting/with-quoting driver
     (first (sql/format {:create-table (sql-jdbc.quoting/quote-table table-name)
                         :with-columns (cond-> (mapv (fn [[col-name type-spec]]
                                                       (vec (cons (sql-jdbc.quoting/quote-identifier col-name)
                                                                  (if (string? type-spec)
                                                                    [[:raw type-spec]]
                                                                    type-spec))))
                                                     column-definitions)
                                         primary-key (conj [(into [:primary-key] primary-key)]))}
                        :quoted true
                        :dialect (sql.qp/quote-style driver))))))

(defmethod driver/drop-table! :motherduck
  [driver db-id table-name]
  (raw-execute!
   driver db-id
   (first (sql/format {:drop-table [:if-exists (keyword table-name)]}
                      :quoted true
                      :dialect (sql.qp/quote-style driver)))))

(defmethod driver/insert-into! :motherduck
  [driver db-id table-name column-names values]
  (sql-jdbc.execute/do-with-connection-with-options
   driver db-id {:write? true}
   (fn [^Connection conn]
     (doseq [chunk (partition-all (or driver/*insert-chunk-rows* 100) values)
             :let  [[sql & params] (sql/format {:insert-into (keyword table-name)
                                                :columns     (sql-jdbc.quoting/quote-columns driver column-names)
                                                :values      chunk}
                                               :quoted true
                                               :dialect (sql.qp/quote-style driver))]]
       (with-open [stmt (.prepareStatement conn ^String sql)]
         (when (seq params)
           (sql-jdbc.execute/set-parameters! driver stmt params))
         (.execute stmt))))))

;; DuckDB does support multi-statement transactions, but the pg gateway always reports the connection as
;; IDLE (see AGENTS.md), so pgjdbc's own `Connection.commit()`/`.rollback()` machinery can't be trusted to
;; send anything. Sidestep that entirely by sending literal `BEGIN`/`COMMIT`/`ROLLBACK` SQL text over a plain
;; autocommit connection -- the gateway/DuckDB treat those as ordinary statements, not JDBC transaction API
;; calls, so they always go out on the wire.
(defmethod driver/rename-tables!* :motherduck
  [driver db-id sorted-rename-map]
  (let [sqls (mapv (fn [[from-table to-table]]
                     (first (sql/format {:alter-table  (keyword from-table)
                                         :rename-table (keyword (name to-table))}
                                        :quoted true
                                        :dialect (sql.qp/quote-style driver))))
                   sorted-rename-map)]
    (sql-jdbc.execute/do-with-connection-with-options
     driver db-id {:write? true}
     (fn [^Connection conn]
       (with-open [stmt (.createStatement conn)]
         (.execute stmt "BEGIN;")
         (try
           (doseq [sql sqls]
             (.execute stmt ^String sql))
           (.execute stmt "COMMIT;")
           (catch Throwable e
             (.execute stmt "ROLLBACK;")
             (throw e))))))))

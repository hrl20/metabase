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
   [metabase.driver.sql-jdbc.connection :as sql-jdbc.conn]
   [metabase.driver.sql-jdbc.execute :as sql-jdbc.execute]
   [metabase.driver.sql-jdbc.sync :as sql-jdbc.sync]
   [metabase.driver.sql.query-processor :as sql.qp]
   [metabase.util :as u]))

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
  (-> (sql-jdbc.conn/connection-details->spec :postgres (assoc details :ssl true))
      (assoc :sslmode "require")))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                              Feature flags                                                      |
;;; +----------------------------------------------------------------------------------------------------------------+

;; Start conservative. Sync essentials (`:describe-fields`, `:describe-fks`, `:describe-is-nullable`,
;; `:schemas`, `:set-timezone`, `:basic-aggregations`) are inherited `true` from Postgres. Actions,
;; table-privileges and database-replication are already `false` for non-`:postgres` drivers (see the
;; `(= driver :postgres)` methods in `metabase.driver.postgres`). Everything below is disabled either
;; because it isn't implemented against the DuckDB catalog yet, or because we don't emit the
;; corresponding metadata (see the `describe-fields` SQL below).
(doseq [feature [:describe-indexes            ; no index sync (we don't override describe-indexes-sql)
                 :describe-default-expr        ; describe-fields does not emit :database-default
                 :describe-is-generated        ; ...nor :database-is-generated
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
                 :transforms/index-ddl]]
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

(defmethod sql-jdbc.sync/describe-fields-sql :motherduck
  [driver & {:keys [schema-names table-names]}]
  ;; Column aliases are intentionally kebab-case to match the keywords `describe-fields-xf` reads
  ;; (`reducible-query` only lower-cases labels; it does not convert `_` -> `-`), mirroring Postgres.
  (sql/format
   {:select    [[:col.schema_name :table-schema]
                [:col.table_name  :table-name]
                [:col.column_name :name]
                [:col.data_type   :database-type]
                [[:- :col.column_index [:inline 1]] :database-position]
                [[:and
                  [:not= :pk.pk_cols nil]
                  [:list_contains :pk.pk_cols :col.column_name]]
                 :pk?]
                [:col.comment :field-comment]
                [[:not :col.is_nullable] :database-required]
                [:col.is_nullable :database-is-nullable]]
    :from      [[[:duckdb_columns] :col]]
    :left-join [[{:select [:schema_name :table_name [:constraint_column_names :pk_cols]]
                  :from   [[[:duckdb_constraints] :c]]
                  :where  [:and
                           [:= :constraint_type [:inline "PRIMARY KEY"]]
                           [:= :database_name [:current_database]]]}
                 :pk]
                [:and
                 [:= :pk.schema_name :col.schema_name]
                 [:= :pk.table_name :col.table_name]]]
    :where     [:and
                [:= :col.database_name [:current_database]]
                [:= :col.internal false]
                (when (seq schema-names) [:in :col.schema_name schema-names])
                (when (seq table-names) [:in :col.table_name table-names])]
    :order-by  [:col.schema_name :col.table_name :col.column_index]}
   :dialect (sql.qp/quote-style driver)))

;; Skip the Postgres implementation, which tags columns whose type is a Postgres enum; DuckDB has no
;; such dynamic types, so delegate to the plain `:sql-jdbc` (identity) transform.
(defmethod sql-jdbc.sync/describe-fields-pre-process-xf :motherduck
  [driver database & args]
  (apply (get-method sql-jdbc.sync/describe-fields-pre-process-xf :sql-jdbc) driver database args))

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

(def ^:private duckdb-database-type->base-type
  ;; Ported from the DuckDB community driver
  ;; (modules/drivers/duckdb/src/metabase/driver/duckdb.clj). Pattern order matters: more specific
  ;; patterns (e.g. `TIMESTAMP WITH TIME ZONE`) must precede their prefixes (`TIMESTAMP`). Applied to
  ;; the *upper-cased* type string, so it also matches the lower-case type names the Postgres wire
  ;; protocol reports for query results (e.g. `int4`, `timestamptz`).
  (sql-jdbc.sync/pattern-based-database-type->base-type
   [[#"BOOLEAN"                  :type/Boolean]
    [#"BOOL"                     :type/Boolean]
    [#"LOGICAL"                  :type/Boolean]
    [#"HUGEINT"                  :type/BigInteger]
    [#"UBIGINT"                  :type/BigInteger]
    [#"BIGINT"                   :type/BigInteger]
    [#"INT8"                     :type/BigInteger]
    [#"LONG"                     :type/BigInteger]
    [#"INT4"                     :type/Integer]
    [#"SIGNED"                   :type/Integer]
    [#"INT2"                     :type/Integer]
    [#"SHORT"                    :type/Integer]
    [#"INT1"                     :type/Integer]
    [#"UINTEGER"                 :type/Integer]
    [#"USMALLINT"                :type/Integer]
    [#"UTINYINT"                 :type/Integer]
    [#"INTEGER"                  :type/Integer]
    [#"SMALLINT"                 :type/Integer]
    [#"TINYINT"                  :type/Integer]
    [#"INT"                      :type/Integer]
    [#"DECIMAL"                  :type/Decimal]
    [#"DOUBLE"                   :type/Float]
    [#"FLOAT8"                   :type/Float]
    [#"NUMERIC"                  :type/Float]
    [#"REAL"                     :type/Float]
    [#"FLOAT4"                   :type/Float]
    [#"FLOAT"                    :type/Float]
    [#"VARCHAR"                  :type/Text]
    [#"BPCHAR"                   :type/Text]
    [#"CHAR"                     :type/Text]
    [#"TEXT"                     :type/Text]
    [#"STRING"                   :type/Text]
    [#"JSON"                     :type/JSON]
    [#"BLOB"                     :type/*]
    [#"BYTEA"                    :type/*]
    [#"VARBINARY"                :type/*]
    [#"BINARY"                   :type/*]
    [#"UUID"                     :type/UUID]
    [#"TIMESTAMPTZ"              :type/DateTimeWithTZ]
    [#"TIMESTAMP WITH TIME ZONE" :type/DateTimeWithTZ]
    [#"DATETIME"                 :type/DateTime]
    [#"TIMESTAMP_S"              :type/DateTime]
    [#"TIMESTAMP_MS"             :type/DateTime]
    [#"TIMESTAMP_NS"             :type/DateTime]
    [#"TIMESTAMP"                :type/DateTime]
    [#"DATE"                     :type/Date]
    [#"TIME"                     :type/Time]
    [#"GEOMETRY"                 :type/*]]))

(defmethod sql-jdbc.sync/database-type->base-type :motherduck
  [driver database-type]
  ;; Sync reads DuckDB catalog type names (`VARCHAR`, `DECIMAL(10,2)`, `INTEGER[]`, ...); query
  ;; results come back over the Postgres wire with pg type names (`int4`, `timestamptz`, ...).
  (let [upper (u/upper-case-en (name database-type))]
    (cond
      ;; Collection/nested types MUST be handled before the pattern maps below, otherwise
      ;; `re-find` would match the *element* type inside the string (e.g. `VARCHAR[]` -> `:type/Text`)
      ;; and downstream code (fingerprinting) would run text ops like `SUBSTRING` on an array column,
      ;; which DuckDB rejects. `:type/Array`/`:type/Structured` are never exactly `:type/Text`.
      (str/ends-with? upper "]")             :type/Array        ; LIST / ARRAY, incl. `T[]`, `T[N]`, `T[][]`
      (re-find #"^(STRUCT|MAP|UNION)" upper)  :type/Structured
      ;; Try the comprehensive Postgres map first (keyed by lower-case pg names) to preserve Postgres'
      ;; semantics for the execute path, then fall back to the DuckDB pattern map (which handles
      ;; precision suffixes and the `WITH TIME ZONE` spelling via regex `re-find`).
      :else
      (or ((get-method sql-jdbc.sync/database-type->base-type :postgres) driver database-type)
          (duckdb-database-type->base-type upper)))))

;; DuckDB's `JSON` type carries JSON-encoded values.
(defmethod sql-jdbc.sync/column->semantic-type :motherduck
  [_driver database-type _column-name]
  (when (and database-type (= "JSON" (u/upper-case-en database-type)))
    :type/SerializedJSON))

;; Belt-and-suspenders: these schemas live in the `system` database (already excluded by the
;; `current_database()` scoping above), but exclude them explicitly too.
(defmethod sql-jdbc.sync/excluded-schemas :motherduck
  [_driver]
  #{"information_schema" "pg_catalog"})

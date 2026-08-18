(ns metabase.driver.motherduck
  "MotherDuck driver.

  MotherDuck speaks the Postgres wire protocol. Therefore this driver derives from the `:postgres`
  driver. It uses the Postgres JDBC client and the query execution of the Postgres driver.

  The catalog is not Postgres-compatible. The database engine is DuckDB, and one connection can see
  many databases. Therefore the methods for sync and metadata below use the DuckDB `duckdb_*`
  metadata functions. These methods add a `database_name = current_database()` condition. That
  condition removes the `system` and `temp` databases and all internal objects.

  Refer to PLAN.md (phases T5/§4/§5) for the SQL and the DuckDB type map."
  (:require
   [clojure.string :as str]
   [honey.sql :as sql]
   [metabase.driver :as driver]
   ;; Load the parent driver first. The registration below needs the parent driver.
   metabase.driver.postgres
   [metabase.driver.sql-jdbc :as sql-jdbc]
   [metabase.driver.sql-jdbc.connection :as sql-jdbc.conn]
   [metabase.driver.sql-jdbc.execute :as sql-jdbc.execute]
   [metabase.driver.sql-jdbc.sync :as sql-jdbc.sync]
   [metabase.driver.sql-jdbc.sync.common :as sql-jdbc.sync.common]
   [metabase.driver.sql-jdbc.sync.describe-table :as sql-jdbc.describe-table]
   [metabase.driver.sql.query-processor :as sql.qp]
   [metabase.driver.sql.query-processor.util :as sql.qp.u]
   [metabase.driver.sql.util :as sql.u]
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
  ;; The MotherDuck Postgres endpoint (pg.<region>-aws.motherduck.com:5432) requires an encrypted
  ;; connection. A connection without encryption does not answer, and the client stops at its time
  ;; limit. Therefore this method sets SSL on and sets `sslmode=require`. That mode encrypts the
  ;; connection. It does not examine the server certificate or the host name.
  ;;
  ;; The `verify-full` mode encrypts the connection and also examines the certificate chain and the
  ;; host name against the JVM trust store. A test of that mode against the MotherDuck endpoint also
  ;; did not answer. The `require` mode operates correctly with the Postgres JDBC driver against
  ;; MotherDuck.
  ;;
  ;; A `:ssl true` detail alone gives `sslmode=require` in the Postgres `connection-details->spec`
  ;; method when no ssl-mode is set. This method sets `sslmode` again to make the value clear.
  ;;
  ;; The gateway receives `options=--compatibility-mode=metabase` as a startup packet option, which
  ;; is equivalent to `PGOPTIONS`. This option selects the MotherDuck gateway code paths for
  ;; Metabase. For example, those code paths put quotation marks around array elements in the same
  ;; way as real Postgres.
  (-> (sql-jdbc.conn/connection-details->spec :postgres (assoc details :ssl true))
      (assoc :sslmode "require"
             :options "--compatibility-mode=metabase")))

;; Real Postgres gives the SQLSTATE `42P01` for a table that does not exist. The Postgres method of
;; `impl-table-known-to-not-exist?` looks for that SQLSTATE. For the same condition, the MotherDuck
;; gateway sends the DuckDB "Catalog Error" message without that SQLSTATE. Therefore the Postgres
;; method finds no match, and the exception continues instead of `driver/table-exists?` giving
;; `false`. This method looks for the text of the DuckDB catalog error instead.
(defmethod sql-jdbc/impl-table-known-to-not-exist? :motherduck
  [_driver e]
  (boolean (re-find #"(?i)catalog error.*does not exist" (or (.getMessage ^java.sql.SQLException e) ""))))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                              Feature flags                                                      |
;;; +----------------------------------------------------------------------------------------------------------------+

;; Start with a small set of features. The driver gets these necessary sync features as `true` from
;; the Postgres driver: `:describe-fields`, `:describe-fks`, `:describe-is-nullable`,
;; `:describe-default-expr`, `:schemas`, `:set-timezone` and `:basic-aggregations`. Actions, table
;; privileges and database replication are already `false` for all drivers that are not `:postgres`.
;; Refer to the `(= driver :postgres)` methods in `metabase.driver.postgres`. Each feature below is
;; `false` for one of two reasons. The driver has no implementation of the feature against the DuckDB
;; catalog. Or the driver cannot give correct metadata for the feature.
(doseq [feature [:describe-indexes            ; No index sync. This driver has no `describe-indexes-sql` method.
                 ;; DuckDB makes the column when the test data loader sends `GENERATED ALWAYS AS
                 ;; (...)`. But the `describe-fields-sql` query of the parent driver reads
                 ;; `information_schema.columns.is_generated`, and that column is always `false` or
                 ;; "NEVER" over the MotherDuck gateway. The
                 ;; `describe-fields-returns-is-generated-test` test shows this behavior. It gives
                 ;; `[false false false]` and not `[false true false]`. The
                 ;; `describe-fields-pre-process-xf` method below removes `:database-is-generated`.
                 ;; Therefore this incorrect value does not go to the application.
                 :describe-is-generated
                 :uploads
                 :persist-models
                 :database-routing
                 :connection-impersonation
                 ;; DuckDB has no row-level security and no role-based GRANT statements. The test
                 ;; code for connection impersonation makes and removes roles with
                 ;; `tx/with-temp-roles!`. That code comes from the `:postgres` driver and sends
                 ;; `ALTER TABLE ... DISABLE ROW LEVEL SECURITY`. It does not operate against the
                 ;; MotherDuck gateway.
                 :test/rls-impersonation
                 :test/column-impersonation
                 ;; The driver can read the foreign keys. Refer to `describe-fks-sql`. But the test
                 ;; data loader cannot make them, because DuckDB has no `ALTER TABLE ... ADD FOREIGN
                 ;; KEY` statement. Therefore the foreign key sync is off.
                 :metadata/key-constraints
                 :transforms/table
                 :transforms/python
                 :transforms/index-ddl
                 ;; The regular expression engine of DuckDB is RE2. The BigQuery, ClickHouse, Presto,
                 ;; Redshift, Vertica and Athena drivers use the same engine. Refer to their
                 ;; `:regex/lookaheads-and-lookbehinds false` methods. RE2 does not support the Perl
                 ;; lookahead assertions and lookbehind assertions. The `:host`, `:domain`,
                 ;; `:subdomain` and `:path` column extractions become a `regex-match-first` clause
                 ;; with such assertions. Refer to `metabase.lib.filter.desugar.jvm`. The DuckDB
                 ;; `regexp_extract` function refuses these patterns with this message: "Invalid Input
                 ;; Error: invalid perl operator: (?<". The cause and the correction are the same as
                 ;; for the other RE2 drivers. This feature flag removes those tests. Refer to
                 ;; `mt/normal-drivers-with-feature :expressions :regex/lookaheads-and-lookbehinds`.
                 :regex/lookaheads-and-lookbehinds]]
  (defmethod driver/database-supports? [:motherduck feature]
    [_driver _feature _db]
    false))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                             Metadata / sync                                                     |
;;; +----------------------------------------------------------------------------------------------------------------+

(def ^:private describe-database-tables-sql
  ;; The tables and the views of the one database of the connection. The `current_database()`
  ;; condition removes the `system` and `temp` databases and all internal objects. The condition for
  ;; the views also removes the objects with `internal = true`. This value is a vector. Therefore the
  ;; code can add parameters later without a change to `describe-database*`.
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

;; There is no `:motherduck` method for `describe-fields-sql`. The `:postgres` method operates
;; correctly against the MotherDuck gateway. That method reads `information_schema.columns` and gives
;; `udt_name` as the `:database-type`. These are the same lower-case Postgres type names that query
;; execution gives. Therefore the driver also keeps the `database-type->base-type` method of the
;; parent driver. Refer to the note below.
;;
;; A DuckDB version of this query with `duckdb_columns()` and `duckdb_constraints()` was here before.
;; A `database-type->base-type` method was here for the one purpose to change the upper-case DuckDB
;; type names to the lower-case Postgres names. Both are removed.
;;
;; TODO: add a `:motherduck` method again if the tests find a case that the parent query gets wrong.
;; For example, the `pg_catalog` branch for materialized views, the `col_description()` function, or
;; the detection of an identity column. There is no evidence that these operate against the DuckDB
;; emulation of the Postgres wire protocol.

;; Do not use the Postgres method. That method marks each column whose type is a Postgres enum, and
;; DuckDB has no such dynamic types. This method also removes `:database-is-generated`. The
;; `describe-fields-sql` query of the parent driver still calculates that value from
;; `information_schema.columns.is_generated`. But the value is incorrect over the MotherDuck gateway.
;; Refer to the `:describe-is-generated` feature flag above. Therefore this method removes the value
;; instead of a report of a wrong value.
(defmethod sql-jdbc.sync/describe-fields-pre-process-xf :motherduck
  [_driver _database & _args]
  (map #(dissoc % :database-is-generated)))

;; There are no Postgres enum types to read.
(defmethod driver/dynamic-database-types-lookup :motherduck
  [_driver _database _database-types]
  nil)

;; The `get-table-pks` method of the `:sql-jdbc` driver calls `DatabaseMetaData.getPrimaryKeys` of
;; pgjdbc. The SQL of that call joins `pg_class` to itself. It also puts
;; `information_schema._pg_expandarray(indkey)` in a subquery. DuckDB makes a very bad plan for that
;; shape. Over the gateway the query stops with this message: "Out of Memory Error: failed to pin
;; block of size 256.0 KiB (47.4 MiB/47.6 MiB used)".
;;
;; A test on 2026-07-29 shows that the cause is the shape of the query. The cause is not the quantity
;; of data, and it is not the re-use of a connection. The error occurs on the first statement of a
;; new connection. It occurs against a catalog with 56 `pg_class` rows and an empty `pg_index` table.
;; The error stops when the second `pg_class` alias goes out of the select list. That alias is
;; `ci.relname`, the PK_NAME column.
;;
;; The `duckdb_constraints()` function gives the same data with one inexpensive query. A method for
;; `get-table-pks` is the usual correction for a database whose JDBC `getPrimaryKeys` function
;; operates incorrectly. The `:oracle`, `:snowflake` and `:clickhouse` drivers all have such a
;; method. On `:motherduck`, only the default `driver/table-exists?` method comes to this method, and
;; it comes through `describe-table`. Sync uses the `describe-fields` method of the parent driver.
;; That method finds `pk?` in `information_schema.table_constraints`. Therefore sync does not use
;; this method.
(def ^:private table-pks-sql
  (str "SELECT unnest(constraint_column_names) FROM duckdb_constraints()"
       " WHERE database_name = current_database() AND constraint_type = 'PRIMARY KEY'"
       " AND schema_name = ? AND table_name = ?"))

(defmethod sql-jdbc.describe-table/get-table-pks :motherduck
  [driver ^Connection conn _db-name-or-nil table]
  (with-open [stmt (sql-jdbc.sync.common/prepare-statement driver conn table-pks-sql [(:schema table) (:name table)])
              rs   (.executeQuery stmt)]
    (loop [pks []]
      (if (.next rs)
        (recur (conj pks (.getString rs 1)))
        pks))))

(defmethod sql-jdbc.sync/describe-fks-sql :motherduck
  [driver & {:keys [schema-names table-names]}]
  ;; The `duckdb_constraints()` function has no column for the schema of the referenced table.
  ;; Therefore this query assumes that the referenced table is in the schema of the table with the
  ;; foreign key. The `UNNEST` function makes one row for each column of a constraint with more than
  ;; one column. This method is here for correctness. The foreign key sync is off, because
  ;; `:metadata/key-constraints` is `false`. The test loader cannot make foreign key constraints.
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

;; There is no `:motherduck` method for `database-type->base-type`. The `describe-fields-sql` query
;; gives `database-type->base-type` the same lower-case `udt_name` type names that query execution
;; gives. For example, `int4`, `varchar` and `timestamptz`. Therefore the `:postgres` map operates
;; correctly. The `column->semantic-type` function of that driver also operates correctly, because
;; its key is the lower-case name `"json"`.
;;
;; TODO: add DuckDB type handling here if the sync tests find a type that the Postgres map does not
;; know. For example, `HUGEINT`, `STRUCT`, `MAP`, `UNION` or the array types.

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                              Query processing                                                   |
;;; +----------------------------------------------------------------------------------------------------------------+

;; A string literal compiles to a parameter placeholder (`?`) without a type. The MotherDuck
;; Postgres gateway cannot find the type of such a parameter at prepare time. It ignores the
;; parameter type that pgjdbc declares. Then it cannot find the type of a result column, and it
;; refuses the prepared statement with this message: "Prepared statement with ambiguous result column
;; types is not supported". This occurs for a text expression (`SELECT ? AS "foo"`), a
;; `CASE ... THEN ?` branch, an argument of `CONCAT(col, ?)`, and other such statements.
;;
;; An explicit CAST gives the type to the gateway. The Redshift driver and the Vertica driver make a
;; smaller correction with `::sql.qp/expression-literal-text-value`. This method includes that
;; correction, because such a clause also compiles to a plain string. But that correction alone does
;; not find the strings that come to `->honeysql` without a wrapper. For example, the values of a
;; `:case` clause or a `:day-name` clause. Numbers and boolean values become inline literals before
;; this point. Therefore strings are the only literals that keep no type.
(defmethod sql.qp/->honeysql [:motherduck String]
  [_driver s]
  (h2x/cast :text s))

;; A `:starts-with`, `:contains` or `:ends-with` clause with a literal makes a `LIKE` pattern. The
;; default `escape-like-pattern` method of the `:sql` driver puts a backslash before each `\`, `_`
;; and `%` metacharacter. Postgres uses `\` as the LIKE escape character by default. Therefore the
;; Postgres driver derives from `::like-escape-char-built-in`. The
;; `transform-literal-like-pattern-honeysql` method of that parent gives the pattern back without a
;; change. It does not add an `ESCAPE '\'` clause.
;;
;; DuckDB has no default LIKE escape character. Therefore the backslashes stay in the pattern and the
;; results are incorrect. For example, `starts-with "\"` gave `[]`, and `starts-with "_"` gave the
;; rows with a `\` prefix. This method adds the `ESCAPE '\'` clause of the SQL standard again. It is
;; the same HoneySQL that the default `:sql` method makes. DuckDB obeys this clause. A live test
;; shows this behavior: `'\Backslash' LIKE '\\%' ESCAPE '\'` is true, and `LIKE '\_%' ESCAPE '\'`
;; finds only the rows with a `_` prefix.
(defmethod sql.qp/transform-literal-like-pattern-honeysql :motherduck
  [_driver like-rhs-honeysql]
  [:escape like-rhs-honeysql [:inline "\\"]])

;; JSON unfolding. The `:motherduck` driver gets `:nested-field-columns` as `true` from the
;; `:postgres` driver. The `driver.common/json-unfolding-default` function gives this value.
;;
;; Postgres reads an unfolded field with `(parent #>> (array['a', 'b']::text[]))::type`. The
;; `format-json-query` function in `metabase.driver.postgres` makes this SQL. DuckDB does not have
;; the `#>>` operator. DuckDB also does not have `text[]` path arrays. The equivalent DuckDB
;; function is `json_extract_string(parent, '$."a"."b"')`. This function gives the value as a
;; VARCHAR without quotation marks. For an array or an object, this function gives the JSON text.
;; The `#>>` operator gives the same JSON text. The `:mysql` driver has the same problem and uses
;; the same solution. That driver also replaces the Postgres operator with a JSONPath function.
;;
;; The code puts the path into the SQL with `[:inline ...]`. It does not send the path as a
;; parameter. At prepare time, the gateway cannot find the type of a bare `?` in the argument list
;; of a scalar function. Refer to the note about `->honeysql [:motherduck String]`.
(def ^:private json-cast-types
  "A map of database types. Each key is the `:database-type` of an unfolded field. The
  `sql-jdbc.describe-table/db-type-map` map gives these types. Each value is the DuckDB type for the
  cast of the extracted value.

  Only the `decimal` type needs a different value. In DuckDB, a `DECIMAL` type without parameters is
  the same as `DECIMAL(18,3)`. This type truncates JSON numbers and gives no error. For example, it
  changes `1.4466014248940077e9` to `1446601424.894`. The Postgres `decimal` type has unlimited
  precision. The `:mysql` driver uses `double` for the same reason. The `double` type keeps all the
  numbers that Jackson gives for a `:type/Number` nfc column.

  The `text`, `boolean` and `timestamp` types are correct DuckDB type names. The code does not
  change them. For example, `CAST('2012-04-23T18:44:43.511Z' AS TIMESTAMP)` reads the ISO-8601 JSON
  date and time values in these columns."
  {"decimal" "double"})

(defmethod sql.qp/json-query :motherduck
  [_driver unwrapped-identifier nfc-field]
  {:pre [(h2x/identifier? unwrapped-identifier)]}
  (let [field-type (:database-type nfc-field)
        nfc-path   (:nfc-path nfc-field)
        json-path  (apply str "$" (for [k (rest nfc-path)]
                                    (format ".\"%s\"" (if (number? k) k (name k)))))]
    (h2x/cast (get json-cast-types field-type field-type)
              [:json_extract_string
               (sql.qp.u/nfc-field->parent-identifier unwrapped-identifier nfc-field)
               [:inline json-path]])))

;; Postgres compiles `:regex-match-first` to `substring(expr FROM pattern)`. That form is a
;; Postgres-only version of `substring` with two arguments and a POSIX regular expression. The DuckDB
;; `substring` function does not have that version. It has only `substring(str, start[, len])` with
;; a position. Therefore DuckDB tries to change the pattern string into an integer position, and it
;; fails. The correction is the same as in the DuckDB community driver
;; (`modules/drivers/duckdb/src/metabase/driver/duckdb.clj`). The DuckDB `regexp_extract` function is
;; the equivalent function. Its default group is 0, the full match.
(defmethod sql.qp/->honeysql [:motherduck :regex-match-first]
  [driver [_ _opts arg pattern]]
  [:regexp_extract (sql.qp/->honeysql driver arg) (sql.qp/->honeysql driver pattern)])

;; Postgres reads a `YYYYMMDDHH24MISS` string with the `to_timestamp(text, text)` function with two
;; arguments. That function reads the fields and interprets them as local time in the session
;; TimeZone. It gives an absolute `timestamptz` value. The DuckDB `to_timestamp` function has only
;; the version with one argument, `(double) -> timestamptz`, for a Unix epoch value. Therefore the
;; Postgres method fails with this message: "No function matches ... to_timestamp(STRING, STRING)".
;;
;; The DuckDB function that reads a string with a format is `strptime`. It uses the `%` directives of
;; strftime. But it gives a `timestamp` value without a zone. A cast of that value to `TIMESTAMPTZ`
;; gives the same behavior as Postgres. DuckDB interprets the value without a zone as local time in
;; the session TimeZone. A live test shows this behavior: with the session time zone
;; America/New_York, `CAST(strptime(...) AS TIMESTAMPTZ)` moves the instant by the offset of that
;; zone. The Postgres `to_timestamp` function does the same. This driver keeps the `:set-timezone`
;; method of the `:postgres` driver. Therefore both drivers set the session TimeZone in the same way.
;;
;; This is the reason that the affected tests put `:motherduck` with `:postgres`, `:h2` and
;; `:databricks`. The `:mysql`, `:sqlserver` and `:presto-jdbc` drivers cannot make a true
;; `timestamptz` value here.
(defmethod sql.qp/cast-temporal-string [:motherduck :Coercion/YYYYMMDDHHMMSSString->Temporal]
  [_driver _coercion-strategy expr]
  (h2x/cast "timestamptz" [:strptime expr (h2x/literal "%Y%m%d%H%M%S")]))

;; Postgres changes a BYTEA column to text with `convert_from(expr, 'UTF8')`. DuckDB has no
;; `convert_from` function. It gives this error: "Scalar Function with name convert_from does not
;; exist". The DuckDB function that changes a BLOB into a VARCHAR is `decode`. It assumes UTF8, the
;; same as the Postgres call here.
(defmethod sql.qp/cast-temporal-byte [:motherduck :Coercion/YYYYMMDDHHMMSSBytes->Temporal]
  [driver _coercion-strategy expr]
  (sql.qp/cast-temporal-string driver :Coercion/YYYYMMDDHHMMSSString->Temporal [:decode expr]))

(defmethod sql.qp/cast-temporal-byte [:motherduck :Coercion/ISO8601Bytes->Temporal]
  [driver _coercion-strategy expr]
  (sql.qp/cast-temporal-string driver :Coercion/ISO8601->DateTime [:decode expr]))

;; The Postgres methods for these two functions call `h2x` helper functions. Those helpers dispatch
;; on the db-type keyword with the global hierarchy. That hierarchy does not know the parent of a
;; driver. Therefore those helpers fail for `:motherduck`. DuckDB accepts the same `now()` SQL and
;; the same `expr + INTERVAL 'n unit'` SQL that the helpers make for Postgres. Therefore these
;; methods call the helpers with the `:postgres` db-type.
(defmethod sql.qp/current-datetime-honeysql-form :motherduck
  [_driver]
  (h2x/current-datetime-honeysql-form :postgres))

(defmethod sql.qp/add-interval-honeysql-form :motherduck
  [_driver hsql-form amount unit]
  (h2x/add-interval-honeysql-form :postgres hsql-form amount unit))

;; The Postgres method puts the time zone names into the HoneySQL form as plain strings, and
;; HoneySQL makes a parameter for each one. That method compiles to:
;;   TIMEZONE(?, TIMEZONE(?, "my_field"))
;; The MotherDuck gateway refuses this statement. It cannot find the type of a parameter without a
;; type at prepare time. Then it cannot find the type of the result column. Refer to the note about
;; `->honeysql [:motherduck String]`.
;;
;; Therefore this method makes each time zone name an inline string literal. For a field with a date
;; or time database type, this method compiles to:
;;   TIMEZONE('America/Los_Angeles', TIMEZONE('UTC', "my_field"))
;;
;; The `h2x/->pg-timestamp` function adds a cast only for an argument that has no date or time type.
;; A literal datetime is such an argument. For example,
;; `(convert-timezone "2024-01-01 00:00:00" "America/Los_Angeles" "UTC")` would otherwise compile the
;; literal to a parameter without a type:
;;   TIMEZONE('America/Los_Angeles', TIMEZONE('UTC', ?))
;; With the cast, this method compiles to:
;;   TIMEZONE('America/Los_Angeles', TIMEZONE('UTC', CAST(? AS timestamp)))
;;
;; The other branch is for an argument that already has a time zone. That branch has no source time
;; zone. It compiles to `TIMEZONE('America/Los_Angeles', expr)`.
(defmethod sql.qp/->honeysql [:motherduck :convert-timezone]
  [driver [_ _opts arg target-timezone source-timezone]]
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
;;; |                                          Driver-managed table DDL                                                |
;;; +----------------------------------------------------------------------------------------------------------------+

;; The `:sql-jdbc` method sends DROP TABLE through `jdbc/execute!`, which calls `executeUpdate` of
;; pgjdbc. The gateway sends a result set for a DROP TABLE statement, also in compatibility mode.
;; Then `executeUpdate` gives this error: "A result was returned when none was expected". A live test
;; on 2026-08-01 shows this behavior. The same test shows that CREATE TABLE and INSERT on the same
;; path give no result set. Refer to the note about `execute-sql!` in
;; `metabase.test.data.motherduck` for the full list. The `Statement.execute` method permits a result
;; set and ignores it.
(defmethod driver/drop-table! :motherduck
  [driver db-id table-name]
  (let [sql (first (sql/format {:drop-table [:if-exists (keyword table-name)]}
                               :quoted true
                               :dialect (sql.qp/quote-style driver)))]
    (sql-jdbc.execute/do-with-connection-with-options
     driver db-id {:write? true}
     (fn [^Connection conn]
       (with-open [stmt (.createStatement conn)]
         (.execute stmt ^String sql))))))

;; The `:postgres` method loads the rows with the COPY wire protocol (`COPY ... FROM STDIN`). The
;; MotherDuck gateway does not support that protocol. Therefore this method calls the `:sql-jdbc`
;; method, which sends INSERT statements in groups. The `:redshift` driver does the same.
(defmethod driver/insert-into! :motherduck
  [driver db-id table-name column-names values]
  ((get-method driver/insert-into! :sql-jdbc) driver db-id table-name column-names values))

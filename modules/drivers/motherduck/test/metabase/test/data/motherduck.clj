(ns metabase.test.data.motherduck
  "Test data extensions for the `:motherduck` driver.

  All statements go through the Postgres wire-protocol endpoint of MotherDuck. This applies to the
  sync and the queries of the driver under test. It also applies to the test data load, which sends
  CREATE DATABASE, CREATE TABLE and INSERT statements. All of these use the same connection details
  and the same `connection-details->spec :motherduck` method as the driver.

  The `:motherduck` driver derives from the `:postgres` driver. Therefore the standard Postgres test
  extensions and SQL-JDBC test extensions do almost all of the work. The methods below cover only
  the conditions where DuckDB is different from real Postgres."
  (:require
   [metabase.config.core :as config]
   [metabase.driver :as driver]
   [metabase.driver.motherduck-test.util :as motherduck-test.util]
   [metabase.driver.sql-jdbc.connection-test :as connection-test]
   [metabase.driver.sql-jdbc.execute :as sql-jdbc.execute]
   [metabase.driver.sql-jdbc.sync.describe-table-test :as describe-table-test]
   [metabase.test :as mt]
   [metabase.test.data.interface :as tx]
   ;; The `row-xform` method below uses the `:postgres` method. That method is available only after
   ;; Clojure loads this namespace. A `DRIVERS=motherduck` test run does not load the Postgres test
   ;; extensions by itself.
   [metabase.test.data.postgres]
   [metabase.test.data.sql :as sql.tx :refer [qualify-and-quote]]
   [metabase.test.data.sql-jdbc.execute :as execute]
   [metabase.test.data.sql-jdbc.load-data :as load-data]
   [metabase.test.data.sql-jdbc.spec :as spec]
   [metabase.test.data.sql.ddl :as ddl]
   [metabase.util.random :as u.random])
  (:import
   (java.sql Connection)))

(set! *warn-on-reflection* true)

;; This namespace does not call `(sql-jdbc.tx/add-test-extensions! :motherduck)`. The `:motherduck`
;; driver derives from the `:postgres` driver. Refer to `metabase.driver.motherduck`. The Postgres
;; test extensions already derive `:postgres` from `:sql-jdbc/test-extensions`. Therefore
;; `:motherduck` also gets the SQL-JDBC test extensions. A second `derive` call would fail with the
;; "already has ... as ancestor" assertion of `clojure.core/derive`. The Redshift test extensions do
;; the same.

(doseq [[feature supported?] {:upload-with-auto-pk (not config/is-test?)
                              :test/time-type false
                              ::describe-table-test/describe-materialized-view-fields false ; MotherDuck has no materialized views
                              ;; The `describe-fields-sql` query reads the PRIMARY KEY columns from
                              ;; `duckdb_constraints` and gives `pk?`. It does this although
                              ;; `:metadata/key-constraints` is `false`, because the loader cannot
                              ;; make foreign keys. The `:mongo` and `:sqlite` drivers have the same
                              ;; condition. Without this value, the `::describe-pks` feature takes
                              ;; `false` from `:metadata/key-constraints`.
                              ::describe-table-test/describe-pks true
                              :test/cannot-destroy-db true
                              ;; The gateway uses the token as the password. It does not examine
                              ;; `:user`, thus the user name has no effect. Refer to
                              ;; `dbdef->connection-details` below. The
                              ;; `test-bad-connection-detail-acquisition` test makes only `:user`
                              ;; incorrect. Therefore that test cannot break the connection. The
                              ;; `:hive-like` driver has the same condition.
                              ::connection-test/regular-connection-pooling false}]
  (defmethod driver/database-supports? [:motherduck feature] [_driver _feature _db] supported?))

;; The gateway ignores an unknown detail key. Other test extensions use the standard
;; `{:unknown_config "single"}` value, but that value does not break the connection here. The gateway
;; always needs a `:dbname` value that names a database that exists. Refer to
;; `dbdef->connection-details` below. Therefore a name of a database that does not exist always
;; breaks the connection.
(defmethod tx/bad-connection-details :motherduck
  [_driver]
  {:dbname (u.random/random-name)})

;; The `:postgres` method writes the `public` schema in the query. DuckDB and MotherDuck put the user
;; tables in the `main` schema.
(defmethod tx/agg-venues-by-category-id :motherduck
  [_driver]
  "select category_id, array_agg(name)
   from main.venues
   group by category_id
   order by 1 asc
   limit 2;")

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                              Connection details                                                 |
;;; +----------------------------------------------------------------------------------------------------------------+

;; Postgres wire-protocol connection details for MotherDuck. The driver under test uses these
;; details. The test data loader also uses them. The `spec/dbdef->spec` function makes the JDBC specs
;; from these details with the `connection-details->spec :motherduck` method. The default host and
;; port are the us-east-1 endpoint. The default user name is `metabase`, and the user name has no
;; effect. The MB_MOTHERDUCK_TEST_HOST, MB_MOTHERDUCK_TEST_PORT and MB_MOTHERDUCK_TEST_USER
;; environment variables can replace these values. The password is the MotherDuck token. The
;; MB_MOTHERDUCK_TEST_PASSWORD environment variable can replace it.
;;
;; The pg gateway always needs a `dbname` value that names a database that exists. Postgres uses a
;; default database when there is no `dbname` value. The gateway is different. It uses the user name
;; as the `dbname` value. If no such database exists, it refuses the connection with this message:
;; "failed to attach '<user>'".
;;
;; Therefore the `:server` context connects to a database that always exists. That database is
;; `my_db`, or the value of MB_MOTHERDUCK_TEST_SERVER_DBNAME. The `:server` context sends the CREATE
;; DATABASE and DROP DATABASE statements. It can run before the test database exists, and it can run
;; after the test database exists. MotherDuck DDL applies to the full account. Therefore CREATE
;; DATABASE and DROP DATABASE for other databases operate correctly from that session.
;;
;; Some callers give a `nil` context and a true `database-name` value. The create-db API test for
;; snowplow is one example. Therefore the condition below examines `:server` and does not examine
;; `:db`.
(defmethod tx/dbdef->connection-details :motherduck
  [_driver context {:keys [database-name]}]
  {:host     (tx/db-test-env-var :motherduck :host "pg.us-east-1-aws.motherduck.com")
   :port     (tx/db-test-env-var :motherduck :port 5432)
   :user     (tx/db-test-env-var :motherduck :user "metabase")
   :password (or (tx/db-test-env-var :motherduck :password) (motherduck-test.util/motherduck-token))
   :ssl      true
   :dbname   (if (and database-name (not= context :server))
               database-name
               (tx/db-test-env-var :motherduck :server-dbname "my_db"))})

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                              DDL / type dialect                                                 |
;;; +----------------------------------------------------------------------------------------------------------------+

(doseq [[base-type db-type] {:type/BigInteger     "BIGINT"
                             :type/Boolean        "BOOL"
                             :type/Date           "DATE"
                             :type/DateTime       "TIMESTAMP"
                             :type/DateTimeWithTZ "TIMESTAMPTZ"
                             :type/Decimal        "DECIMAL"
                             :type/Float          "DOUBLE"
                             :type/Integer        "INTEGER"
                             :type/Text           "STRING"
                             :type/Time           "TIME"
                             ;; The `:motherduck` driver derives from the `:postgres` driver in
                             ;; `driver/hierarchy`. Postgres maps `:type/TimeWithTZ` to "TIME WITH
                             ;; TIME ZONE". That type is TIMETZ, a true DuckDB type. Without the
                             ;; three entries below, these types get that Postgres type. Then the
                             ;; loader makes the `time_ltz` and `time_tz` columns of
                             ;; `attempted-murders` as DuckDB TIMETZ columns.
                             ;;
                             ;; But the JDBC ResultSetMetaData of the pg gateway gives those columns
                             ;; as `Types.TIME` and not as `TIME_WITH_TIMEZONE`. Therefore
                             ;; `read-column-thunk` uses the reader for a time without a zone. That
                             ;; reader gives a `LocalTime` value and removes the offset. This is a
                             ;; limit of the gateway. The read side cannot correct it.
                             ;;
                             ;; These three types get the plain "TIME" type, the same as
                             ;; `:type/Time`. Then the `driver-distinguishes-between-base-types?`
                             ;; function of the test framework correctly reports no support for a
                             ;; TIME with a time zone. This report agrees with the true behavior. The
                             ;; community `:duckdb` driver also does not claim this support.
                             :type/TimeWithTZ          "TIME"
                             :type/TimeWithLocalTZ     "TIME"
                             :type/TimeWithZoneOffset  "TIME"
                             :type/UUID           "UUID"}]
  (defmethod sql.tx/field-base-type->sql-type [:motherduck base-type] [_ _] db-type))

;; DuckDB has no SERIAL type. Postgres uses that type for a primary key. Therefore a primary key id
;; is a plain INTEGER, and the loader makes the value. Refer to `row-xform` below.
(defmethod sql.tx/pk-sql-type :motherduck [_] "INTEGER")

;; The `:postgres` method gives "public". But DuckDB and MotherDuck put the user tables in `main`.
;; Some tests put `(sql.tx/session-schema driver)` before a table name. The tests for a rename and for
;; the creation of a view are two examples. Without this method, those tests use a `public` schema
;; that does not exist.
(defmethod sql.tx/session-schema :motherduck [_driver] "main")

(defmethod sql.tx/create-db-sql :motherduck
  [driver {:keys [database-name]}]
  (format "CREATE DATABASE IF NOT EXISTS %s;" (qualify-and-quote driver database-name)))

;; Do not use the Postgres method. That method puts a DO block before the DROP statement. The DO
;; block uses `pg_stat_activity` to stop the other connections, and DuckDB cannot run it. These
;; statements go through a connection in the `:server` context. That connection is attached to a
;; different database. Refer to `dbdef->connection-details`. Therefore a plain DROP statement is
;; sufficient.
(defmethod ddl/drop-db-ddl-statements :motherduck
  [driver {:keys [database-name]} & _]
  [(format "DROP DATABASE IF EXISTS %s CASCADE;" (qualify-and-quote driver database-name))])

;; DuckDB has no `ALTER TABLE ... ADD FOREIGN KEY` statement. Therefore the loader makes the test
;; datasets without foreign key constraints. This agrees with `:metadata/key-constraints false` in
;; the driver.
(defmethod sql.tx/add-fk-sql :motherduck [& _] nil)

;; The `:postgres` method gives "GENERATED ALWAYS AS (%s) STORED". But DuckDB supports only a VIRTUAL
;; generated column. VIRTUAL is the default when the statement has no `STORED` word and no `VIRTUAL`
;; word. The `STORED` word gives this error: "Can not create a STORED generated column!". This method
;; gives the same expression as the `sql.tx/generated-column-sql :default` method.
(defmethod sql.tx/generated-column-sql :motherduck [_ expr]
  (format "GENERATED ALWAYS AS (%s)" expr))

;; This method combines two transducers. The first transducer makes the id values, because DuckDB
;; does not have the SERIAL type. Refer to `pk-sql-type`. The second transducer is the Postgres
;; transducer. It puts each `:type/JSON` value into a `CAST(? AS json)` expression. Then the value
;; goes into the column as JSON and not as text. DuckDB can also cast the VARCHAR parameter
;; automatically. But the explicit cast gives the parameter type to the gateway. Without the cast,
;; the gateway cannot find the type of the `?` parameter in an INSERT statement. The gateway has
;; this limitation for all statements in this driver.
(defmethod load-data/row-xform :motherduck
  [driver dbdef tabledef]
  (comp (load-data/maybe-add-ids-xform tabledef)
        ((get-method load-data/row-xform :postgres) driver dbdef tabledef)))

;; The Postgres method loads each table with one INSERT statement. But the Postgres JDBC driver
;; permits a maximum of 65,535 parameters in a prepared statement. A large table has more parameters
;; than this maximum. The `orders` table of the sample dataset is one example. Therefore this method
;; gives a chunk size, and the loader sends the rows in groups.
(defmethod load-data/chunk-size :motherduck
  [_driver _dbdef _tabledef]
  200)

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                       statement execution (pg gateway)                                          |
;;; +----------------------------------------------------------------------------------------------------------------+

;; The `--compatibility-mode=metabase` option makes the gateway give a correct command tag for most
;; DML statements and DDL statements. But the gateway processes some statement classes outside that
;; compatibility layer, and it still sends a result set for them. The default `jdbc-execute!`
;; function calls `executeUpdate` of pgjdbc, and that function refuses a result set with this
;; message: "A result was returned when none was expected".
;;
;; A live test on 2026-08-01 measured each statement class. These classes send a result set: `SET`
;; (for example `SET SESSION TIMEZONE`), `DROP TABLE`, `UPDATE`, `COMMENT ON TABLE` and `COMMENT ON
;; COLUMN`. These classes send no result set: `CREATE TABLE`, `INSERT` and `CREATE OR REPLACE VIEW`.
;; The test did not measure `CREATE DATABASE`, `DROP DATABASE` or `DROP VIEW`. The behavior is the
;; same for each pgjdbc `preferQueryMode` value. Therefore a connection property cannot correct it.
;;
;; The `Statement.execute` method permits a result set and ignores it. Therefore the loader paths for
;; these statements use the methods below.
(defmethod execute/execute-sql! :motherduck
  [driver ^Connection conn sql]
  (execute/default-execute-sql!
   driver conn sql
   :execute! (fn [^Connection conn ^String sql]
               (with-open [stmt (.createStatement conn)]
                 (.execute stmt sql)))))

(defmethod load-data/do-insert! :motherduck
  [driver ^Connection conn table-identifier rows]
  ;; Set the session time zone in the same way as the `:sql-jdbc` method, but with `.execute`. A `SET`
  ;; statement sends a result set. Refer to `execute-sql!` above. The `:sql-jdbc` method sends the
  ;; `SET` statement with `jdbc/execute!`, and that function refuses a result set. The INSERT
  ;; statements are correct on the `:sql-jdbc` path, because an INSERT statement sends no result set.
  ;; But the `SET` statement is in the body of the `:sql-jdbc` method. Therefore this driver must
  ;; replace the full method.
  (with-open [stmt (.createStatement conn)]
    (.execute stmt "SET SESSION TIMEZONE TO 'UTC';"))
  ;; The `set-parameter` function can read the time zone of the database. The load must not do this,
  ;; because the sync of the database is not complete.
  (mt/with-database-timezone-id nil
    (doseq [[sql & params] (ddl/insert-rows-dml-statements driver table-identifier rows)]
      (try
        (with-open [stmt (.prepareStatement conn ^String sql)]
          (when (seq params)
            (sql-jdbc.execute/set-parameters! driver stmt params))
          (.execute stmt))
        (catch Throwable e
          (throw (ex-info (format "INSERT FAILED: %s" (ex-message e))
                          {:driver driver, :sql sql}
                          e)))))))

;; The `:sql-jdbc/test-extensions` methods send CREATE VIEW and DROP VIEW with `jdbc/execute!`. These
;; two methods use the same SQL builders, but they send the SQL with the `execute-sql!` method above.
;; That method uses `Statement.execute`, which permits a result set.
;;
;; NOTE: view DDL was one of the statement classes that sent a result set. The live test on
;; 2026-08-01 shows that `CREATE OR REPLACE VIEW` is now correct. That test did not measure `DROP
;; VIEW`. Therefore it is possible to remove these two methods. First run `describe-view-fields` and
;; `describe-table-test` again to make sure.
(defmethod tx/create-view-of-table! :motherduck
  [driver database view-name table-name options]
  (sql-jdbc.execute/do-with-connection-with-options
   driver database {:write? true}
   (fn [conn]
     (execute/execute-sql! driver conn (first (sql.tx/create-view-of-table-sql driver database view-name table-name options))))))

(defmethod tx/drop-view! :motherduck
  [driver database view-name options]
  (sql-jdbc.execute/do-with-connection-with-options
   driver database {:write? true}
   (fn [conn]
     (execute/execute-sql! driver conn (first (sql.tx/drop-view-sql driver database view-name options))))))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                      create / load / cleanup lifecycle                                          |
;;; +----------------------------------------------------------------------------------------------------------------+

(defonce ^:private ^{:doc "The names of the MotherDuck databases that this test run made. The set also
  contains the names of the databases that this test run found. The cleanup drops only the databases
  in this set. Therefore it never drops a true database of a shared MotherDuck account."}
  created-databases
  (atom #{}))

(defmethod tx/create-db! :motherduck
  [driver dbdef & options]
  (swap! created-databases conj (:database-name dbdef))
  (apply load-data/create-db! driver dbdef options))

(defmethod tx/dataset-already-loaded? :motherduck
  [driver dbdef]
  ;; Keep the name. Then the `after-run` method drops the database also when `create-db!` does not
  ;; run for it. For example, an earlier run can stop before its own cleanup and leave the database.
  (swap! created-databases conj (:database-name dbdef))
  (try
    (sql-jdbc.execute/do-with-connection-with-options
     driver
     (spec/dbdef->spec driver :db dbdef)
     {:write? false}
     (fn [^java.sql.Connection conn]
       ;; Look for the first table of the dataset. Do not examine only the connection, as the
       ;; `:sql-jdbc` method does. Then the loader loads the data again for a database that an
       ;; earlier run made but did not fill.
       (with-open [stmt (.prepareStatement conn (str "SELECT 1 FROM duckdb_tables() "
                                                     "WHERE database_name = current_database() AND table_name = ? "
                                                     "LIMIT 1"))]
         (.setString stmt 1 (:table-name (first (:table-definitions dbdef))))
         (with-open [rset (.executeQuery stmt)]
           (.next rset)))))
    (catch Throwable _
      ;; The pg gateway refuses the connection when the database does not exist.
      false)))

(defmethod tx/before-run :motherduck
  [_driver]
  ;; This method makes no database, thus there is no database to drop. It only makes the set of names
  ;; empty. This is necessary after an earlier run in the same REPL that did not come to `after-run`.
  (reset! created-databases #{}))

(defmethod tx/after-run :motherduck
  [driver]
  ;; Drop only the databases that this test run made. The [[created-databases]] atom holds their
  ;; names. Do not change the other databases in the account. The `sample_data` database and the
  ;; databases of a developer are examples of such other databases.
  (sql-jdbc.execute/do-with-connection-with-options
   driver
   (spec/dbdef->spec driver :server nil)
   {:write? true}
   (fn [^java.sql.Connection conn]
     (doseq [db-name @created-databases]
       (with-open [stmt (.createStatement conn)]
         (.execute stmt (format "DROP DATABASE IF EXISTS \"%s\" CASCADE;" db-name))))
     (reset! created-databases #{}))))

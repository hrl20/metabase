(ns metabase.test.data.motherduck
  "Test-data extensions for the `:motherduck` driver.

  Everything goes through MotherDuck's Postgres wire-protocol endpoint — sync/queries by the
  driver-under-test AND test-data loading (CREATE DATABASE / CREATE TABLE / INSERT) alike, using
  the same connection details / `connection-details->spec :motherduck` as the driver itself.
  `:motherduck` derives from `:postgres`, so the stock Postgres/SQL-JDBC test extensions do nearly
  all of the work; the overrides below cover only the places where the backend (DuckDB) differs
  from real Postgres."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [metabase.config.core :as config]
   [metabase.driver :as driver]
   [metabase.driver.sql-jdbc.execute :as sql-jdbc.execute]
   [metabase.driver.sql-jdbc.sync.describe-table-test :as describe-table-test]
   [metabase.test :as mt]
   [metabase.test.data.interface :as tx]
   [metabase.test.data.sql :as sql.tx :refer [qualify-and-quote]]
   [metabase.test.data.sql-jdbc.execute :as execute]
   [metabase.test.data.sql-jdbc.load-data :as load-data]
   [metabase.test.data.sql-jdbc.spec :as spec]
   [metabase.test.data.sql.ddl :as ddl])
  (:import
   (java.sql Connection)))

(set! *warn-on-reflection* true)

;; We don't call `(sql-jdbc.tx/add-test-extensions! :motherduck)` here: `:motherduck` derives from
;; `:postgres` (see `metabase.driver.motherduck`), and Postgres's test extensions already derive
;; `:postgres` -> `:sql-jdbc/test-extensions`. So `:motherduck` inherits the SQL-JDBC test extensions
;; transitively, and re-deriving them would trip `clojure.core/derive`'s "already has ... as ancestor"
;; assertion. (Same reasoning as the Redshift test extensions.)

(doseq [[feature supported?] {:upload-with-auto-pk (not config/is-test?)
                              :test/time-type false
                              ::describe-table-test/describe-materialized-view-fields false ; motherduck has no materialized views
                              ;; `describe-fields-sql` reads PRIMARY KEY columns from `duckdb_constraints`
                              ;; and emits `pk?`, even though `:metadata/key-constraints` is false (the
                              ;; loader can't create FKs). Same situation as `:mongo`/`:sqlite`; the
                              ;; `::describe-pks` proxy otherwise infers false from key-constraints.
                              ::describe-table-test/describe-pks true
                              :test/cannot-destroy-db true}]
  (defmethod driver/database-supports? [:motherduck feature] [_driver _feature _db] supported?))

(defmethod tx/bad-connection-details :motherduck
  [_driver]
  {:unknown_config "single"})

;; Inherited from `:postgres`, but that impl hardcodes the `public` schema; DuckDB/MotherDuck puts
;; user tables in `main`.
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

(defn- dotenv
  "Parse simple `KEY=VALUE` lines from the repo-root `.env` into a string->string map (empty map if
  the file is absent). The test runner's working directory is the repo root."
  []
  (let [f (io/file ".env")]
    (if (.exists f)
      (into {}
            (for [line  (str/split-lines (slurp f))
                  :let  [line (str/trim line)]
                  :when (and (seq line)
                             (not (str/starts-with? line "#"))
                             (str/includes? line "="))
                  :let  [[k v] (str/split line #"=" 2)]]
              [(str/trim k) (str/trim v)]))
      {})))

(defn- motherduck-token
  "The MotherDuck token: `MOTHERDUCK_TOKEN` env var, then a `MOTHERDUCK_TOKEN=` line in repo-root
  `.env`. The MotherDuck pg gateway authenticates with the token as the Postgres password."
  []
  (or (not-empty (System/getenv "MOTHERDUCK_TOKEN"))
      (not-empty (get (dotenv) "MOTHERDUCK_TOKEN"))))

;; Postgres-wire connection details to MotherDuck, used both by the driver-under-test and by the
;; test-data loader (`spec/dbdef->spec` builds JDBC specs from these via `connection-details->spec
;; :motherduck`). Host/port/user default to the us-east-1 endpoint and a (cosmetic) `metabase` user,
;; overridable via MB_MOTHERDUCK_TEST_{HOST,PORT,USER}; the password is the MotherDuck token,
;; overridable via MB_MOTHERDUCK_TEST_PASSWORD.
;;
;; The pg gateway always needs a `dbname` that exists: unlike Postgres — which falls back to a
;; default database when `dbname` is absent — it defaults `dbname` to the *username* and refuses the
;; connection with "failed to attach '<user>'" if no such database exists. So the `:server` context
;; (used to run CREATE/DROP DATABASE, possibly before/after the test database exists) connects to a
;; database assumed to always exist: `my_db` unless MB_MOTHERDUCK_TEST_SERVER_DBNAME says otherwise.
;; MotherDuck DDL is account-scoped, so CREATE/DROP DATABASE for *other* databases works fine from
;; that session. Some callers pass a `nil` context with a real `database-name` (e.g. the snowplow
;; create-db API test), so gate on `:server` rather than only matching `:db`.
(defmethod tx/dbdef->connection-details :motherduck
  [_driver context {:keys [database-name]}]
  {:host     (tx/db-test-env-var :motherduck :host "pg.us-east-1-aws.motherduck.com")
   :port     (tx/db-test-env-var :motherduck :port 5432)
   :user     (tx/db-test-env-var :motherduck :user "metabase")
   :password (or (tx/db-test-env-var :motherduck :password) (motherduck-token))
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
                             :type/UUID           "UUID"}]
  (defmethod sql.tx/field-base-type->sql-type [:motherduck base-type] [_ _] db-type))

;; DuckDB has no SERIAL (the Postgres pk type); PK ids are plain INTEGERs generated by the loader
;; (see `row-xform` below).
(defmethod sql.tx/pk-sql-type :motherduck [_] "INTEGER")

;; Inherited from `:postgres` as "public", but DuckDB/MotherDuck put user tables in `main`. Tests that
;; qualify table names with `(sql.tx/session-schema driver)` (renames, view creation, ...) otherwise
;; target a nonexistent `public` schema.
(defmethod sql.tx/session-schema :motherduck [_driver] "main")

(defmethod sql.tx/create-db-sql :motherduck
  [driver {:keys [database-name]}]
  (format "CREATE DATABASE IF NOT EXISTS %s;" (qualify-and-quote driver database-name)))

;; Skip the Postgres impl, which prepends a `pg_stat_activity` connection-killing DO block that
;; DuckDB can't run. These statements execute over a `:server`-context connection — attached to a
;; *different* database (see `dbdef->connection-details`) — so a plain DROP works.
(defmethod ddl/drop-db-ddl-statements :motherduck
  [driver {:keys [database-name]} & _]
  [(format "DROP DATABASE IF EXISTS %s CASCADE;" (qualify-and-quote driver database-name))])

;; DuckDB has no `ALTER TABLE ... ADD FOREIGN KEY`, so test datasets are created without FK
;; constraints (matches `:metadata/key-constraints false` in the driver).
(defmethod sql.tx/add-fk-sql :motherduck [& _] nil)

(defmethod load-data/row-xform :motherduck
  [_driver _dbdef tabledef]
  (load-data/maybe-add-ids-xform tabledef))

;; The Postgres impl loads each table in a single INSERT, but the Postgres JDBC driver caps a
;; prepared statement at 65,535 parameters, which bigger tables (e.g. sample-dataset `orders`)
;; exceed. Restore the stock chunked loading.
(defmethod load-data/chunk-size :motherduck
  [_driver _dbdef _tabledef]
  200)

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                       statement execution (pg gateway)                                          |
;;; +----------------------------------------------------------------------------------------------------------------+

;; MotherDuck's pg gateway returns a result set for *every* statement — DDL, INSERT and SET included
;; (e.g. a `Count` row for INSERT) — where real Postgres returns only a command tag. pgjdbc's
;; `executeUpdate`/`executeBatch`, which the default `jdbc-execute!` ends up calling, reject that
;; with "A result was returned when none was expected". Plain `Statement.execute` permits (and
;; ignores) the returned result set.
(defmethod execute/execute-sql! :motherduck
  [driver ^Connection conn sql]
  (execute/default-execute-sql!
   driver conn sql
   :execute! (fn [^Connection conn ^String sql]
               (with-open [stmt (.createStatement conn)]
                 (.execute stmt sql)))))

(defmethod load-data/do-insert! :motherduck
  [driver ^Connection conn table-identifier rows]
  ;; Same session-timezone pinning as the stock sql-jdbc impl, but with raw `.execute` — see
  ;; `execute-sql!` above for why `jdbc/execute!` can't run it.
  (with-open [stmt (.createStatement conn)]
    (.execute stmt "SET SESSION TIMEZONE TO 'UTC';"))
  ;; `set-parameter` might try to look at the DB timezone; we don't want to do that while loading
  ;; the data because the DB hasn't been synced yet.
  (mt/with-database-timezone-id nil
    (doseq [[sql & params] (ddl/insert-rows-dml-statements driver table-identifier rows)]
      (try
        ;; Raw `.execute` rather than `jdbc/execute!` for two reasons: pgjdbc's `executeUpdate` (what
        ;; c.j.jdbc calls) rejects the result set the gateway returns for INSERT ("A result was
        ;; returned when none was expected"), and c.j.jdbc's default wrapping transaction would never
        ;; be COMMITted (the gateway always reports the session IDLE in ReadyForQuery, so pgjdbc
        ;; skips sending COMMIT) — the inserted rows would be silently rolled back on close.
        (with-open [stmt (.prepareStatement conn ^String sql)]
          (when (seq params)
            (sql-jdbc.execute/set-parameters! driver stmt params))
          (.execute stmt))
        (catch Throwable e
          (throw (ex-info (format "INSERT FAILED: %s" (ex-message e))
                          {:driver driver, :sql sql}
                          e)))))))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                      create / load / cleanup lifecycle                                          |
;;; +----------------------------------------------------------------------------------------------------------------+

(defonce ^:private ^{:doc "Names of every MotherDuck database this test run has created (or ensured
  exists). Cleanup only ever drops databases in this set, so a shared MotherDuck account's real
  databases are never touched."}
  created-databases
  (atom #{}))

(defmethod tx/create-db! :motherduck
  [driver dbdef & options]
  (swap! created-databases conj (:database-name dbdef))
  (apply load-data/create-db! driver dbdef options))

(defmethod tx/dataset-already-loaded? :motherduck
  [driver dbdef]
  ;; Track the name so `after-run` cleanup drops the database even if `create-db!` never runs for
  ;; it (e.g. it was left behind by a previous run that crashed before its own cleanup).
  (swap! created-databases conj (:database-name dbdef))
  (try
    (sql-jdbc.execute/do-with-connection-with-options
     driver
     (spec/dbdef->spec driver :db dbdef)
     {:write? false}
     (fn [^java.sql.Connection conn]
       ;; Check the dataset's first table rather than mere connectability (the stock sql-jdbc
       ;; impl), so a database that a crashed run created but never finished loading gets reloaded
       ;; instead of trusted.
       (with-open [stmt (.prepareStatement conn (str "SELECT 1 FROM duckdb_tables() "
                                                     "WHERE database_name = current_database() AND table_name = ? "
                                                     "LIMIT 1"))]
         (.setString stmt 1 (:table-name (first (:table-definitions dbdef))))
         (with-open [rset (.executeQuery stmt)]
           (.next rset)))))
    (catch Throwable _
      ;; the pg gateway refuses the connection outright when the database doesn't exist
      false)))

(defmethod tx/before-run :motherduck
  [_driver]
  ;; Nothing has been created yet, so there's nothing to drop; just make sure tracking starts from a
  ;; clean slate (e.g. after a previous run in the same REPL that didn't reach `after-run`).
  (reset! created-databases #{}))

(defmethod tx/after-run :motherduck
  [driver]
  ;; Drop only the databases this test run created (tracked in [[created-databases]]), leaving any
  ;; other databases in the account — e.g. the built-in `sample_data` or a developer's own —
  ;; untouched.
  (sql-jdbc.execute/do-with-connection-with-options
   driver
   (spec/dbdef->spec driver :server nil)
   {:write? true}
   (fn [^java.sql.Connection conn]
     (doseq [db-name @created-databases]
       (with-open [stmt (.createStatement conn)]
         (.execute stmt (format "DROP DATABASE IF EXISTS \"%s\" CASCADE;" db-name))))
     (reset! created-databases #{}))))

(ns metabase.test.data.motherduck
  "Test-data extensions for the `:motherduck` driver.

  The driver-under-test connects to MotherDuck over the **Postgres wire protocol** (see
  `metabase.driver.motherduck`), so `tx/dbdef->connection-details` returns Postgres details and the
  driver syncs/queries through the pg endpoint.

  Writes (CREATE DATABASE / CREATE TABLE / INSERT), however, go through the **DuckDB JDBC client**
  (`md:` connection) — the proven path for bulk-loading MotherDuck. `dbdef->spec` and the workspace
  helpers below therefore build DuckDB JDBC specs directly rather than delegating to
  `connection-details->spec :motherduck` (which now yields a Postgres spec). DuckDB JDBC is a
  test-only dependency, supplied by the sibling duckdb module on the `:drivers` classpath.

  See PLAN.md phase T6."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [metabase.config.core :as config]
   [metabase.driver :as driver]
   [metabase.driver.sql-jdbc.execute :as sql-jdbc.execute]
   [metabase.driver.sql-jdbc.sync.describe-table-test :as describe-table-test]
   [metabase.test.data.interface :as tx]
   [metabase.test.data.sql :as sql.tx :refer [qualify-and-quote]]
   [metabase.test.data.sql-jdbc :as sql-jdbc.tx]
   [metabase.test.data.sql-jdbc.execute :as sql-jdbc.test-execute]
   [metabase.test.data.sql-jdbc.load-data :as load-data]
   [metabase.test.data.sql-jdbc.spec :refer [dbdef->spec]]
   [metabase.test.data.sql.ddl :as ddl]
   [metabase.util.log :as log])
  (:import
   (java.sql PreparedStatement Time)
   (java.time LocalDate LocalTime OffsetTime ZoneOffset)
   (java.time.temporal ChronoField)
   (org.duckdb DuckDBPreparedStatement)))

(set! *warn-on-reflection* true)

(sql-jdbc.tx/add-test-extensions! :motherduck)

(doseq [[feature supported?] {:upload-with-auto-pk (not config/is-test?)
                              :test/time-type false
                              ::describe-table-test/describe-materialized-view-fields false ; motherduck has no materialized views
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
;;; |                                       Connection specs (pg + DuckDB JDBC)                                       |
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
  `.env`. Used both as the DuckDB JDBC connection token (loading data) and as the Postgres password
  (the MotherDuck pg gateway authenticates with the token)."
  []
  (or (not-empty (System/getenv "MOTHERDUCK_TOKEN"))
      (not-empty (get (dotenv) "MOTHERDUCK_TOKEN"))))

;; Details for the driver-under-test: real Postgres-wire connection to MotherDuck. Host/port/user
;; default to the us-east-1 endpoint and a (cosmetic) `metabase` user, overridable via
;; MB_MOTHERDUCK_TEST_{HOST,PORT,USER}. The password is the MotherDuck token (see above), overridable
;; via MB_MOTHERDUCK_TEST_PASSWORD. `dbname` is the test database name (only for the `:db` context).
;; `connection-details->spec :motherduck` turns these into a Postgres JDBC spec.
(defmethod tx/dbdef->connection-details :motherduck
  [_driver context {:keys [database-name]}]
  (merge
   {:host     (tx/db-test-env-var :motherduck :host "pg.us-east-1-aws.motherduck.com")
    :port     (tx/db-test-env-var :motherduck :port 5432)
    :user     (tx/db-test-env-var :motherduck :user "metabase")
    :password (or (tx/db-test-env-var :motherduck :password) (motherduck-token))
    :ssl      true}
   (when (= context :db)
     {:dbname database-name})))

(defn- duckdb-spec
  "A DuckDB JDBC db-spec for connecting to MotherDuck. `subname` is a `md:` URL — `\"md:\"` for a
  workspace connection that can see every database, or `\"md:<database-name>\"` to open a specific
  one. The MotherDuck token (see [[motherduck-token]]) is passed explicitly as the connection's
  `motherduck_token` property when present."
  [subname]
  (merge
   {:classname          "org.duckdb.DuckDBDriver"
    :subprotocol        "duckdb"
    :subname            subname
    "custom_user_agent" "metabase_test"}
   (when-let [token (motherduck-token)]
     {"motherduck_token" token})))

(defn- md-workspace-spec
  "DuckDB JDBC workspace-mode spec (`md:`). Used to create/destroy/enumerate test databases across
  the whole MotherDuck account."
  []
  (duckdb-spec "md:"))

;; All bulk writes go through DuckDB JDBC, NOT the pg endpoint. Context matters: a `md:<database-name>`
;; connection can only be opened once that database exists (MotherDuck attaches it at connection
;; startup and errors otherwise), so:
;;  - `:server` ("no DB in particular" — used to run CREATE/DROP DATABASE, possibly before it exists)
;;    connects in **workspace mode** (`md:`), which can see/create every database.
;;  - `:db` connects to the **specific** database (`md:<database-name>`), which exists by the time the
;;    `:db` connection is opened (the `:server` statements created it first).
(defmethod dbdef->spec :motherduck
  [_driver context {:keys [database-name]}]
  (duckdb-spec (if (= context :server)
                 "md:"
                 (format "md:%s" database-name))))

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

(defmethod sql.tx/pk-sql-type :motherduck [_] "INTEGER")

(defmethod sql.tx/create-db-sql :motherduck
  [driver {:keys [database-name]}]
  (format "CREATE DATABASE IF NOT EXISTS %s;" (qualify-and-quote driver database-name)))

(defmethod ddl/drop-db-ddl-statements :motherduck
  [driver {:keys [database-name]} & _]
  ;; A connection opened against `md:<database-name>` has that database in use, so we can't drop it
  ;; from under ourselves. Attach an in-memory database, switch to it, then drop the MotherDuck one.
  ["ATTACH IF NOT EXISTS ':memory:' AS memdb;"
   "USE memdb;"
   (format "DROP DATABASE %s CASCADE;" (qualify-and-quote driver database-name))])

;; DuckDB has no `ALTER TABLE ... ADD FOREIGN KEY`, so test datasets are created without FK
;; constraints (matches `:metadata/key-constraints false` in the driver).
(defmethod sql.tx/add-fk-sql :motherduck [& _] nil)

(defmethod load-data/row-xform :motherduck
  [_driver _dbdef tabledef]
  (load-data/maybe-add-ids-xform tabledef))

(defmethod tx/sorts-nil-first? :motherduck
  [_driver _base-type]
  false)

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                    Parameter binding (DuckDB JDBC load path)                                    |
;;; +----------------------------------------------------------------------------------------------------------------+

;; The `:motherduck` driver binds query parameters over the *Postgres* JDBC driver in production, but
;; the test-data loader inserts rows over the *DuckDB* JDBC driver. DuckDB's JDBC `setObject` rejects
;; the explicit `java.sql.Types` that the inherited Postgres binding passes for temporal values —
;; e.g. `setObject(i, <LocalDate>, Types.DATE)` throws `Unknown target type 91`. DuckDB instead wants
;; the value bound *without* a target type (and, for DATE/TIME, pre-converted).
;;
;; `set-parameter` dispatches only on `[driver value-class]`, so these methods would also intercept
;; the Postgres query path. We therefore branch on the concrete statement class: only rewrite the
;; binding for a `DuckDBPreparedStatement`, and delegate everything else to the Postgres impl. These
;; conversions are ported from the DuckDB community driver.
(defn- delegate-set-parameter
  "Invoke the binding that would apply if `:motherduck` had no override (i.e. the Postgres one)."
  [driver ps i t]
  ((get-method sql-jdbc.execute/set-parameter [:postgres (class t)]) driver ps i t))

(defn- local-time->sql-time ^Time [^LocalTime lt]
  (Time. (.getLong lt ChronoField/MILLI_OF_DAY)))

(defmethod sql-jdbc.execute/set-parameter [:motherduck LocalDate]
  [driver ^PreparedStatement ps i ^LocalDate t]
  (if (instance? DuckDBPreparedStatement ps)
    (.setObject ps i (.atStartOfDay t))          ; LocalDate -> LocalDateTime @ 00:00, no target type
    (delegate-set-parameter driver ps i t)))

(defmethod sql-jdbc.execute/set-parameter [:motherduck LocalTime]
  [driver ^PreparedStatement ps i ^LocalTime t]
  (if (instance? DuckDBPreparedStatement ps)
    (.setObject ps i (local-time->sql-time t))
    (delegate-set-parameter driver ps i t)))

(defmethod sql-jdbc.execute/set-parameter [:motherduck OffsetTime]
  [driver ^PreparedStatement ps i ^OffsetTime t]
  (if (instance? DuckDBPreparedStatement ps)
    (.setObject ps i (local-time->sql-time (.toLocalTime (.withOffsetSameInstant t ZoneOffset/UTC))))
    (delegate-set-parameter driver ps i t)))

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
  ;; Create the database itself over a workspace connection first, so the subsequent
  ;; `md:<database-name>` connections used for loading data can open it.
  (swap! created-databases conj (:database-name dbdef))
  (sql-jdbc.execute/do-with-connection-with-options
   driver
   (md-workspace-spec)
   {:write? true}
   (fn [^java.sql.Connection conn]
     (try (.setAutoCommit conn true)
          (catch Throwable _
            (log/debugf "`.setAutoCommit` failed with engine `%s`" (name driver))))
     (sql-jdbc.test-execute/execute-sql! driver conn (sql.tx/create-db-sql driver dbdef))))
  (apply load-data/create-db! driver dbdef options))

(defmethod tx/dataset-already-loaded? :motherduck
  [driver dbdef]
  ;; Check whether the dataset's first table already exists, by scanning `duckdb_tables()` over the
  ;; **workspace** connection. In workspace mode `duckdb_tables()` reports tables across every
  ;; database in the account without attaching/`USE`-ing a specific one, so we deliberately do NOT
  ;; open a `md:<database-name>` connection here.
  ;;
  ;; Why this matters: MotherDuck's DuckDB-JDBC client caches a per-database instance keyed by the
  ;; `md:<db>` URL and attaches that database's catalog at connection-open time. Under parallel test
  ;; execution + `after-run` cleanup, another session can drop/recreate the database, leaving that
  ;; cached instance stale — so opening `md:<db>` (or calling `.getTables` on it) fails hard with
  ;; `Catalog '<db>' has been deleted`. A catalog *scan* over the workspace connection just returns
  ;; zero rows for a missing database instead of throwing.
  ;;
  ;; NOTE: `database-name` lives on the *dbdef*; only `table-name` comes from the table definition.
  (let [database-name (:database-name dbdef)
        table-name    (:table-name (first (:table-definitions dbdef)))]
    ;; Track the name so `after-run` cleanup will drop it even if `create-db!` isn't subsequently
    ;; called (cleanup only ever touches databases in this set — see `drop-created-databases!`).
    (swap! created-databases conj database-name)
    (sql-jdbc.execute/do-with-connection-with-options
     driver
     (md-workspace-spec)
     {:write? false}
     (fn [^java.sql.Connection conn]
       (with-open [stmt (.prepareStatement
                         conn
                         "SELECT 1 FROM duckdb_tables() WHERE database_name = ? AND table_name = ? LIMIT 1")]
         (.setString stmt 1 database-name)
         (.setString stmt 2 table-name)
         (with-open [rset (.executeQuery stmt)]
           ;; a returned row means the table exists ⇒ dataset already loaded.
           (.next rset)))))))

(defn- drop-created-databases!
  "Drop only the databases this test run created (tracked in [[created-databases]]), leaving any
  other databases in the account — e.g. the built-in `sample_data` or a developer's own — untouched.
  Clears the set afterwards."
  [^java.sql.Connection conn]
  (doseq [db-name @created-databases]
    (with-open [stmt (.createStatement conn)]
      (.execute stmt (format "DROP DATABASE IF EXISTS \"%s\" CASCADE;" db-name))))
  (reset! created-databases #{}))

(defmethod tx/before-run :motherduck
  [_driver]
  ;; Nothing has been created yet, so there's nothing to drop; just make sure tracking starts from a
  ;; clean slate (e.g. after a previous run in the same REPL that didn't reach `after-run`).
  (reset! created-databases #{}))

(defmethod tx/after-run :motherduck
  [driver]
  (sql-jdbc.execute/do-with-connection-with-options
   driver
   (md-workspace-spec)
   {:write? true}
   drop-created-databases!))

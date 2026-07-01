(ns metabase.driver.motherduck-test
  "Connection smoke tests for the MotherDuck driver.

  These hit the *live* MotherDuck Postgres endpoint, so they need a password. It is read from the
  `PGPASSWORD` environment variable, falling back to a `PGPASSWORD=...` line in the repo-root `.env`
  file. If no password is found the live test is skipped (so CI without creds stays green)."
  (:require
   [clojure.java.io :as io]
   [clojure.java.jdbc :as jdbc]
   [clojure.string :as str]
   [clojure.test :refer :all]
   ;; ensure the driver (and its parent) are registered
   metabase.driver.motherduck
   [metabase.driver.sql-jdbc.connection :as sql-jdbc.conn]))

(defn- parse-dotenv
  "Parse simple KEY=VALUE lines from `.env` content into a map of string->string."
  [content]
  (into {}
        (for [line  (str/split-lines content)
              :let  [line (str/trim line)]
              :when (and (seq line)
                         (not (str/starts-with? line "#"))
                         (str/includes? line "="))
              :let  [[k v] (str/split line #"=" 2)]]
          [(str/trim k) (str/trim v)])))

(defn- dotenv
  "Read the repo-root `.env` into a map (empty map if it doesn't exist). The test runner's working
  directory is the repo root."
  []
  (let [f (io/file ".env")]
    (if (.exists f)
      (parse-dotenv (slurp f))
      {})))

(defn- pg-password
  "MotherDuck token used as the Postgres password: env var wins, then `.env`."
  []
  (or (not-empty (System/getenv "PGPASSWORD"))
      (not-empty (get (dotenv) "PGPASSWORD"))))

(defn- test-details
  "Connection details for the live MotherDuck pg endpoint. Host is fixed to the us-east-1 endpoint;
  the MotherDuck pg gateway accepts any username, so it's cosmetic. Everything is overridable via env."
  []
  {:host     (or (not-empty (System/getenv "MB_MOTHERDUCK_TEST_HOST")) "pg.us-east-1-aws.motherduck.com")
   :port     (Integer/parseInt (or (not-empty (System/getenv "MB_MOTHERDUCK_TEST_PORT")) "5432"))
   :dbname   (or (not-empty (System/getenv "MB_MOTHERDUCK_TEST_DB")) "my_db")
   :user     (or (not-empty (System/getenv "MB_MOTHERDUCK_TEST_USER")) "metabase")
   :password (pg-password)
   :ssl      true})

(deftest connection-spec-forces-sslmode-require-test
  (testing "the :motherduck connection spec forces sslmode=require"
    (let [spec (sql-jdbc.conn/connection-details->spec
                :motherduck
                (assoc (test-details) :password "placeholder"))]
      (is (= "require" (:sslmode spec)))
      (is (= "org.postgresql.Driver" (:classname spec)))
      (is (= "postgresql" (:subprotocol spec))))))

(deftest ^:mb/driver-tests live-connection-test
  (testing "can open an SSL connection to the live MotherDuck pg endpoint and query it"
    (if-not (pg-password)
      (println "SKIP live-connection-test: no PGPASSWORD in env or .env")
      (let [spec (sql-jdbc.conn/connection-details->spec :motherduck (test-details))]
        (testing "SELECT 1 succeeds (proves the TLS handshake + auth completed)"
          (is (= [{:one 1}]
                 (jdbc/query spec ["SELECT 1 AS one"]))))
        (testing "current_database() matches the requested dbname"
          (let [db (-> (jdbc/query spec ["SELECT current_database() AS db"]) first :db)]
            (println "connected to current_database() =" db)
            (is (= (:dbname (test-details)) db))))))))

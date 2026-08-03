(ns metabase.driver.motherduck-test
  "Connection smoke tests for the MotherDuck driver.

  These hit the *live* MotherDuck Postgres endpoint, so they need a password — the MotherDuck token,
  read by [[metabase.driver.motherduck-test.util/motherduck-token]]. If no token is found the live
  test is skipped (so CI without creds stays green)."
  (:require
   [clojure.java.jdbc :as jdbc]
   [clojure.test :refer :all]
   ;; ensure the driver (and its parent) are registered
   metabase.driver.motherduck
   [metabase.driver.motherduck-test.util :as motherduck-test.util]
   [metabase.driver.sql-jdbc.connection :as sql-jdbc.conn]))

(defn- test-details
  "Connection details for the live MotherDuck pg endpoint. Host is fixed to the us-east-1 endpoint;
  the MotherDuck pg gateway accepts any username, so it's cosmetic. Everything is overridable via env."
  []
  {:host     (or (not-empty (System/getenv "MB_MOTHERDUCK_TEST_HOST")) "pg.us-east-1-aws.motherduck.com")
   :port     (Integer/parseInt (or (not-empty (System/getenv "MB_MOTHERDUCK_TEST_PORT")) "5432"))
   :dbname   (or (not-empty (System/getenv "MB_MOTHERDUCK_TEST_DB")) "my_db")
   :user     (or (not-empty (System/getenv "MB_MOTHERDUCK_TEST_USER")) "metabase")
   :password (motherduck-test.util/motherduck-token)
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
    (if-not (motherduck-test.util/motherduck-token)
      (println "SKIP live-connection-test: no MOTHERDUCK_TOKEN in env or .env")
      (let [spec (sql-jdbc.conn/connection-details->spec :motherduck (test-details))]
        (testing "SELECT 1 succeeds (proves the TLS handshake + auth completed)"
          (is (= [{:one 1}]
                 (jdbc/query spec ["SELECT 1 AS one"]))))
        (testing "current_database() matches the requested dbname"
          (let [db (-> (jdbc/query spec ["SELECT current_database() AS db"]) first :db)]
            (println "connected to current_database() =" db)
            (is (= (:dbname (test-details)) db))))))))

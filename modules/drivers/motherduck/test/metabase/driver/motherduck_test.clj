(ns metabase.driver.motherduck-test
  "Connection tests for the MotherDuck driver.

  These tests connect to the live MotherDuck Postgres endpoint. Therefore they need a password. The
  password is the MotherDuck token. The [[metabase.driver.motherduck-test.util/motherduck-token]]
  function reads this token. If there is no token, the live test does not run. Then CI stays green
  without the credential."
  (:require
   [clojure.java.jdbc :as jdbc]
   [clojure.test :refer :all]
   ;; Load this namespace to register the driver and its parent driver.
   metabase.driver.motherduck
   [metabase.driver.motherduck-test.util :as motherduck-test.util]
   [metabase.driver.sql-jdbc.connection :as sql-jdbc.conn]
   [metabase.driver.sql.query-processor :as sql.qp]
   [metabase.util.honey-sql-2 :as h2x]))

(defn- test-details
  "Connection details for the live MotherDuck pg endpoint. The default host is the us-east-1
  endpoint. The MotherDuck pg gateway accepts all user names, thus the user name has no effect. An
  environment variable can replace each value."
  []
  {:host     (or (not-empty (System/getenv "MB_MOTHERDUCK_TEST_HOST")) "pg.us-east-1-aws.motherduck.com")
   :port     (Integer/parseInt (or (not-empty (System/getenv "MB_MOTHERDUCK_TEST_PORT")) "5432"))
   :dbname   (or (not-empty (System/getenv "MB_MOTHERDUCK_TEST_DB")) "my_db")
   :user     (or (not-empty (System/getenv "MB_MOTHERDUCK_TEST_USER")) "metabase")
   :password (motherduck-test.util/motherduck-token)
   :ssl      true})

;; This test only compiles SQL. It does not connect to the database. The
;; `metabase.driver.mysql-test/json-query-test` test and the
;; `metabase.driver.postgres-test/json-query-test` test have the same structure. This test makes
;; sure that the driver keeps the DuckDB JSON syntax. The parent driver cannot make this syntax. The
;; driver uses the `json_extract_string` function with an inline JSONPath. It does not use the
;; Postgres `#>>` operator with a `text[]` array.
(deftest ^:parallel json-query-test
  (let [identifier (h2x/identifier :field "boop" "bleh -> meh")]
    (testing "a nested field reference compiles to json_extract_string with an inlined JSONPath"
      (are [field expected] (= [expected]
                               (sql.qp/format-honeysql :motherduck (sql.qp/json-query :motherduck identifier field)))
        {:nfc-path [:bleh :meh] :database-type "text"}
        "CAST(JSON_EXTRACT_STRING(\"boop\".\"bleh\", '$.\"meh\"') AS text)"

        ;; The code changes the `decimal` type to `double`. In DuckDB, a `DECIMAL` type without
        ;; parameters truncates the value.
        {:nfc-path [:bleh :meh] :database-type "decimal"}
        "CAST(JSON_EXTRACT_STRING(\"boop\".\"bleh\", '$.\"meh\"') AS double)"

        {:nfc-path [:bleh "boop" :foobar 1234] :database-type "boolean"}
        "CAST(JSON_EXTRACT_STRING(\"boop\".\"bleh\", '$.\"boop\".\"foobar\".\"1234\"') AS boolean)"

        {:nfc-path [:bleh "meh"] :database-type "timestamp"}
        "CAST(JSON_EXTRACT_STRING(\"boop\".\"bleh\", '$.\"meh\"') AS timestamp)"))))

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

(ns ^:mb/driver-tests metabase.query-processor.nested-array-test
  {:clj-kondo/config '{:linters {:deprecated-var {:exclude {metabase.test.data/mbql-query {:namespaces [metabase.query-processor.nested-array-test]}}}}}}
  (:require
   [clojure.test :refer :all]
   [metabase.driver :as driver]
   [metabase.test :as mt]
   [metabase.test.data.interface :as tx]))

(defmulti native-nested-array-query
  {:arglists '([driver])}
  tx/dispatch-on-driver-with-test-extensions
  :hierarchy #'driver/hierarchy)

(defmethod native-nested-array-query :default
  [_driver]
  "select array[array[array['a', 'b'], array['c', 'd'] ], array[array['w', 'x'], array['y', 'z'] ]];")

(doseq [driver [:mysql :sqlite]]
  (defmethod native-nested-array-query driver
    [_driver]
    "select json_array(json_array(json_array('a', 'b'), json_array('c', 'd')), json_array(json_array('w', 'x'), json_array('y', 'z')));"))

(doseq [driver [:redshift :databricks]]
  (defmethod native-nested-array-query driver
    [_driver]
    "select array(array(array('a', 'b'), array('c', 'd')), array(array('w', 'x'), array('y', 'z')));"))

(defmethod native-nested-array-query :snowflake
  [_driver]
  "select array_construct(array_construct(array_construct('a', 'b'), array_construct('c', 'd')), array_construct(array_construct('w', 'x'), array_construct('y', 'z')));")

(defmulti native-nested-array-results
  {:arglists '([driver])}
  tx/dispatch-on-driver-with-test-extensions
  :hierarchy #'driver/hierarchy)

(defmethod native-nested-array-results :default
  [_driver]
  [[["a" "b"] ["c" "d"]] [["w" "x"] ["y" "z"]]])

(doseq [driver [:sqlite :databricks :redshift]]
  (defmethod native-nested-array-results driver
    [_driver]
    "[[[\"a\",\"b\"],[\"c\",\"d\"]],[[\"w\",\"x\"],[\"y\",\"z\"]]]"))

(defmethod native-nested-array-results :mysql
  [_driver]
  "[[[\"a\", \"b\"], [\"c\", \"d\"]], [[\"w\", \"x\"], [\"y\", \"z\"]]]")

(defmethod native-nested-array-results :snowflake
  [_driver]
  "[\n  [\n    [\n      \"a\",\n      \"b\"\n    ],\n    [\n      \"c\",\n      \"d\"\n    ]\n  ],\n  [\n    [\n      \"w\",\n      \"x\"\n    ],\n    [\n      \"y\",\n      \"z\"\n    ]\n  ]\n]")

;; `:motherduck` derives from `:postgres`, and top-level (single-dimension) Postgres arrays come back
;; over MotherDuck's pg-gateway with a proper array OID (1009 for `text[]`), parsed into Clojure
;; vectors like real Postgres. But *nested* arrays (depth >= 2, as constructed by this test) come back
;; tagged with a non-standard OID (17000, not a registered Postgres array type) instead — the gateway
;; can't represent a DuckDB `LIST(LIST(...))` as a real Postgres array (which is multi-dimensional but
;; homogeneous, not nested), so it falls back to DuckDB's own text rendering of the list
;; (`[[a, b], [c, d]]`, unquoted elements, not valid JSON). This is a genuine gateway wire-protocol
;; limitation, not a driver bug: verified directly against the gateway with `psycopg2` that
;; `array[array['a','b']]` reports OID 1009 (proper array, round-trips as a real list) while
;; `array[array[array['a','b']]]` reports OID 17000 and reads back as this same literal string.
(defmethod native-nested-array-results :motherduck
  [_driver]
  "[[[a, b], [c, d]], [[w, x], [y, z]]]")

(doseq [driver [:postgres :vertica :mysql :sqlite :redshift :databricks :snowflake]]
  (defmethod driver/database-supports? [driver :test/nested-arrays]
    [_driver _feature _database]
    true))

(deftest ^:parallel nested-array-query-test
  (testing "A nested array query should be returned in a readable format"
    (mt/test-drivers (mt/normal-driver-select {:+features [:test/nested-arrays]})
      (mt/dataset test-data
        (is (= [[(native-nested-array-results driver/*driver*)]]
               (->> (mt/native-query {:query (native-nested-array-query driver/*driver*)})
                    mt/process-query
                    mt/rows)))))))

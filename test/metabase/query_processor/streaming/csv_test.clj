(ns ^:mb/driver-tests metabase.query-processor.streaming.csv-test
  (:require
   [clojure.data.csv :as csv]
   [clojure.string :as str]
   [clojure.test :refer :all]
   [metabase.driver :as driver]
   [metabase.permissions.models.data-permissions :as data-perms]
   [metabase.permissions.models.permissions-group :as perms-group]
   [metabase.query-processor :as qp]
   [metabase.query-processor.streaming.interface :as qp.si]
   [metabase.test :as mt]
   [metabase.test.data.dataset-definitions :as defs]
   [metabase.util :as u])
  (:import
   (java.io BufferedOutputStream ByteArrayOutputStream)))

(set! *warn-on-reflection* true)

(defn- parse-and-sort-csv [response]
  (assert (some? response))
  (sort-by
   ;; ID in CSV is a string, parse it and sort it to get the first 5
   (comp #(Integer/parseInt %) first)
   ;; First row is the header
   (rest (csv/read-csv response))))

(defmethod driver/database-supports? [::driver/driver ::date-columns-should-be-emitted-without-time]
  [_driver _feature _database]
  true)

;; The following drivers are excluded from this test because their date types are acutally date times
(defmethod driver/database-supports? [:mongo ::date-columns-should-be-emitted-without-time]
  [_driver _feature _database]
  false)

(defmethod driver/database-supports? [:oracle ::date-columns-should-be-emitted-without-time]
  [_driver _feature _database]
  false)

(deftest ^:parallel date-columns-should-be-emitted-without-time
  (mt/test-drivers (mt/normal-drivers-with-feature ::date-columns-should-be-emitted-without-time)
    (is (= [["1" "April 7, 2014"      "5" "12"]
            ["2" "September 18, 2014" "1" "31"]
            ["3" "September 15, 2014" "8" "56"]
            ["4" "March 11, 2014"     "5" "4"]
            ["5" "May 5, 2013"        "3" "49"]]
           (let [result (mt/user-http-request :crowberto :post 200 "dataset/csv"
                                              {:query       (mt/mbql-query checkins {:order-by [[:asc $id]], :limit 5})
                                               :format_rows true})]
             (take 5 (parse-and-sort-csv result)))))))

(deftest errors-not-include-visualization-settings
  (testing "Queries that error should not include visualization settings"
    (mt/with-temp [:model/Card {card-id :id} {:dataset_query          (mt/mbql-query orders
                                                                        {:order-by [[:asc $id]], :limit 5})
                                              :visualization_settings {:column_settings {}
                                                                       :notvisiblekey   :notvisiblevalue}}]
      (mt/with-no-data-perms-for-all-users!
        (data-perms/set-database-permission! (perms-group/all-users)
                                             (u/the-id (mt/db))
                                             :perms/create-queries :query-builder)
        (let [results        (mt/user-http-request :rasta :post 200 (format "card/%d/query/csv" card-id))
              results-string (str results)
              illegal-strings ["notvisiblekey" "notvisiblevalue" "column_settings"
                               "visualization-settings" "viz-settings"]]
          (doseq [illegal illegal-strings]
            (is (false? (str/includes? results-string illegal)))))))))

(deftest check-an-empty-date-column
  (testing "NULL values should be written correctly"
    (mt/dataset defs/test-data-null-date
      (let [result (mt/user-http-request :crowberto :post 200 "dataset/csv"
                                         {:query        (mt/mbql-query checkins {:order-by [[:asc $id]], :limit 5})
                                          :format_rows true})]
        (is (= [["1" "April 7, 2014"      "" "5" "12"]
                ["2" "September 18, 2014" "" "1" "31"]
                ["3" "September 15, 2014" "" "8" "56"]
                ["4" "March 11, 2014"     "" "5" "4"]
                ["5" "May 5, 2013"        "" "3" "49"]]
               (parse-and-sort-csv result)))))))

(deftest datetime-fields-are-untouched-when-exported
  (mt/test-drivers (mt/normal-drivers)
    (let [result (mt/user-http-request :crowberto :post 200 "dataset/csv"
                                       {:query       (mt/mbql-query users {:order-by [[:asc $id]], :limit 5})
                                        :format_rows true})]
      (is (= [["1" "Plato Yeshua" "April 1, 2014, 8:30 AM"]
              ["2" "Felipinho Asklepios" "December 5, 2014, 3:15 PM"]
              ["3" "Kaneonuskatew Eiran" "November 6, 2014, 4:15 PM"]
              ["4" "Simcha Yan" "January 1, 2014, 8:30 AM"]
              ["5" "Quentin Sören" "October 3, 2014, 5:30 PM"]]
             (parse-and-sort-csv result))))))

(deftest geographic-coordinates-test
  (testing "Ensure CSV longitude and latitude values are correctly exported"
    (let [result (mt/user-http-request
                  :rasta :post 200 "dataset/csv"
                  {:query {:database (mt/id)
                           :type     :query
                           :query    {:source-table (mt/id :venues)
                                      :fields       [[:field (mt/id :venues :id) {:base-type :type/Integer}]
                                                     [:field (mt/id :venues :longitude) {:base-type :type/Float}]
                                                     [:field (mt/id :venues :latitude) {:base-type :type/Float}]]
                                      :order-by     [[:asc (mt/id :venues :id)]]
                                      :limit        5}}
                   :format_rows true})]
      (is (= [["1" "165.37400000° W" "10.06460000° N"]
              ["2" "118.32900000° W" "34.09960000° N"]
              ["3" "118.42800000° W" "34.04060000° N"]
              ["4" "118.46500000° W" "33.99970000° N"]
              ["5" "118.26100000° W" "34.07780000° N"]]
             (parse-and-sort-csv result))))))

(defn- csv-export
  "Given a seq of result rows, write it as a CSV, then read the CSV and return the resulting data."
  [rows]
  (driver/with-driver :h2
    (mt/with-metadata-provider (mt/id)
      (with-open [bos (ByteArrayOutputStream.)
                  os  (BufferedOutputStream. bos)]
        (let [results-writer (qp.si/streaming-results-writer :csv os)]
          (qp.si/begin! results-writer {:data {:ordered-cols [{:base_type :type/*}
                                                              {:base_type :type/*}
                                                              {:base_type :type/*}]}} {})
          (doall (map-indexed
                  (fn [i row] (qp.si/write-row! results-writer row i [] {}))
                  rows))
          (qp.si/finish! results-writer {:row_count (count rows)}))
        (let [bytea (.toByteArray bos)]
          (rest (csv/read-csv (String. bytea))))))))

(deftest lazy-seq-realized-test
  (testing "Lazy seqs within rows are automatically realized during exports (#26261)"
    (let [row (first (csv-export [[(lazy-seq [1 2 3])]]))]
      (is (= ["[1 2 3]"] row))))

  (testing "LocalDate in a lazy seq (checking that elements in a lazy seq are formatted correctly as strings)"
    (let [row (first (csv-export [[(lazy-seq [#t "2021-03-30T"])]]))]
      (is (= ["[\"2021-03-30\"]"] row)))))

(deftest format-datetimes-test
  (testing "Format datetime columns the way we expect (#10803)"
    (let [query   (str "SELECT cast(parsedatetime('2020-06-03', 'yyyy-MM-dd') AS timestamp) AS \"birth_date\",\n"
                       "       cast(parsedatetime('2020-06-03 23:41:23', 'yyyy-MM-dd HH:mm:ss') AS timestamp) AS \"created_at\"")
          results (qp/process-query (assoc (mt/native-query {:query query}) :middleware {:format-rows? false}))]
      (is (= [["2020-06-03" "2020-06-03T23:41:23"]]
             (csv-export (mt/rows results)))))))

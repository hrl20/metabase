(ns metabase.channel.render.body-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer :all]
   [clojure.walk :as walk]
   [clojure.zip :as zip]
   [hiccup.core :refer [html]]
   [hickory.select :as hik.s]
   [metabase.channel.render.body :as body]
   [metabase.channel.render.core :as channel.render]
   [metabase.config :as config]
   [metabase.formatter :as formatter]
   [metabase.notification.payload.execute :as notification.execute]
   [metabase.pulse.render.test-util :as render.tu]
   [metabase.query-processor :as qp]
   [metabase.test :as mt]
   [metabase.test.data.interface :as tx]
   [metabase.util :as u]))

(use-fixtures :each
  (fn warn-possible-rebuild
    [thunk]
    (testing "[PRO TIP] If this test fails, you may need to rebuild the bundle with `yarn build-static-viz`\n"
      (thunk))))

(def ^:private pacific-tz "America/Los_Angeles")

(def ^:private test-columns
  [{:name            "ID",
    :display_name    "ID",
    :base_type       :type/BigInteger
    :semantic_type   nil
    :visibility_type :normal}
   {:name            "latitude"
    :display_name    "Latitude"
    :base_type       :type/Float
    :semantic_type   :type/Latitude
    :visibility_type :normal}
   {:name            "last_login"
    :display_name    "Last Login"
    :base_type       :type/DateTime
    :semantic_type   nil
    :visibility_type :normal}
   {:name            "name"
    :display_name    "Name"
    :base_type       :type/Text
    :semantic_type   nil
    :visibility_type :normal}
   {:name            "desc_col"
    :display_name    "Description Column"
    :base_type       :type/Text
    :semantic_type   :type/Description
    :visibility_type :normal}])

(def ^:private example-test-data
  [[1 34.0996 "2014-04-01T08:30:00.0000" "Stout Burgers & Beers" "Desc 1"]
   [2 34.0406 "2014-12-05T15:15:00.0000" "The Apple Pan" "Desc 2"]
   [3 34.0474 "2014-08-01T12:45:00.0000" "The Gorbals" "Desc 3"]
   [4 0       "2018-09-01T19:32:00.0000" "The Tipsy Tardigrade" "Desc 4"]
   [5 nil     "2022-10-12T05:55:00.0000" "The Bungalow" "Desc 5"]])

(defn- col-counts [results]
  (set (map (comp count :row) results)))

(defn- number [num-str num-value]
  (formatter/map->NumericWrapper {:num-str   (str num-str)
                                  :num-value num-value}))

(defn- textwrap [text-str]
  (formatter/->TextWrapper text-str text-str))

(def ^:private default-header-result
  [{:row [(number "ID" "ID") (number "Latitude" "Latitude") "Last Login" "Name" "Description Column"]}
   #{5}])

(defn- prep-for-html-rendering'
  [cols rows]
  (let [results (#'body/prep-for-html-rendering pacific-tz {} {:cols cols :rows rows})]
    [(first results)
     (col-counts results)]))

(def ^:private detail-col      {:name            "detail_col"
                                :display_name    "Details Column"
                                :base_type       :type/Text
                                :semantic_type    nil
                                :visibility_type :details-only})

(def ^:private sensitive-col   {:name            "sensitive_col"
                                :display_name    "Sensitive Column"
                                :base_type       :type/Text
                                :semantic_type    nil
                                :visibility_type :sensitive})

(def ^:private retired-col     {:name            "retired_col"
                                :display_name    "Retired Column"
                                :base_type       :type/Text
                                :semantic_type    nil
                                :visibility_type :retired})

;; Testing the format of headers
(deftest header-result
  (is (= default-header-result
         (prep-for-html-rendering' test-columns example-test-data))))

(deftest header-result-2
  (let [cols-with-details (conj test-columns detail-col)
        data-with-details (mapv #(conj % "Details") example-test-data)]
    (is (= default-header-result
           (prep-for-html-rendering' cols-with-details data-with-details)))))

(deftest header-result-3
  (let [cols-with-sensitive (conj test-columns sensitive-col)
        data-with-sensitive (mapv #(conj % "Sensitive") example-test-data)]
    (is (= default-header-result
           (prep-for-html-rendering' cols-with-sensitive data-with-sensitive)))))

(deftest header-result-4
  (let [columns-with-retired (conj test-columns retired-col)
        data-with-retired    (mapv #(conj % "Retired") example-test-data)]
    (is (= default-header-result
           (prep-for-html-rendering' columns-with-retired data-with-retired)))))

(deftest prefers-col-visualization-settings-for-header
  (testing "Users can give columns custom names. Use those if they exist."
    (let [card    {:visualization_settings
                   {:column_settings {"[\"ref\",[\"field\",321,null]]" {:column_title "Custom Last Login"}
                                      "[\"name\",\"name\"]"            {:column_title "Custom Name"}}}}
          cols    [{:name            "last_login"
                    :display_name    "Last Login"
                    :base_type       :type/DateTime
                    :semantic_type    nil
                    :visibility_type :normal
                    :field_ref       [:field 321 nil]}
                   {:name            "name"
                    :display_name    "Name"
                    :base_type       :type/Text
                    :semantic_type    nil
                    :visibility_type :normal}]]

      (testing "card contains custom column names"
        (is (= {:row       ["Custom Last Login" "Custom Name"]}
               (first (#'body/prep-for-html-rendering pacific-tz
                                                      card
                                                      {:cols cols :rows []})))))

      (testing "card does not contain custom column names"
        (is (= {:row       ["Last Login" "Name"]}
               (first (#'body/prep-for-html-rendering pacific-tz
                                                      {}
                                                      {:cols cols :rows []}))))))))

;; When there are too many columns, #'body/prep-for-html-rendering show narrow it
(deftest narrow-the-columns
  (is (= [{:row [(number "ID" "ID") (number "Latitude" "Latitude")]}
          #{2}]
         (prep-for-html-rendering' (subvec test-columns 0 2) example-test-data))))

;; Basic test that result rows are formatted correctly (dates, floating point numbers etc)
(deftest format-result-rows
  (is (= [{:row [(number "1" 1) "34.09960000° N" "April 1, 2014, 8:30 AM" (textwrap "Stout Burgers & Beers") (textwrap "Desc 1")]}
          {:row [(number "2" 2) "34.04060000° N" "December 5, 2014, 3:15 PM" (textwrap "The Apple Pan") (textwrap "Desc 2")]}
          {:row [(number "3" 3) "34.04740000° N" "August 1, 2014, 12:45 PM" (textwrap "The Gorbals") (textwrap "Desc 3")]}
          {:row [(number "4" 4)  "0.00000000° N" "September 1, 2018, 7:32 PM" (textwrap "The Tipsy Tardigrade") (textwrap "Desc 4")]}
          {:row [(number "5" 5) "" "October 12, 2022, 5:55 AM" (textwrap "The Bungalow") (textwrap "Desc 5")]}]
         (rest (#'body/prep-for-html-rendering pacific-tz {} {:cols test-columns :rows example-test-data})))))

(defn- add-rating
  "Injects `RATING-OR-COL` and `DESCRIPTION-OR-COL` into `COLUMNS-OR-ROW`"
  [columns-or-row rating-or-col description-or-col]
  (vec
   (concat (subvec columns-or-row 0 2)
           [rating-or-col]
           (subvec columns-or-row 2)
           [description-or-col])))

(def ^:private test-columns-with-remapping
  (add-rating test-columns
              {:name         "rating"
               :display_name "Rating"
               :base_type    :type/Integer
               :semantic_type :type/Category
               :remapped_to  "rating_desc"}
              {:name          "rating_desc"
               :display_name  "Rating Desc"
               :base_type     :type/Text
               :semantic_type  nil
               :remapped_from "rating"}))

(def ^:private test-data-with-remapping
  (mapv add-rating
        example-test-data
        [1 2 3]
        ["Bad" "Ok" "Good"]))

;; With a remapped column, the header should contain the name of the remapped column (not the original)1
(deftest remapped-col
  (is (= [{:row [(number "ID" "ID") (number "Latitude" "Latitude") "Rating Desc" "Last Login" "Name" "Description Column"]}
          #{6}]
         (prep-for-html-rendering' test-columns-with-remapping test-data-with-remapping))))

;; Result rows should include only the remapped column value, not the original
(deftest include-only-remapped-column-name
  (is (= [[(number "1" 1) "34.09960000° N" "Bad" "April 1, 2014, 8:30 AM" (textwrap "Stout Burgers & Beers") (textwrap "Desc 1")]
          [(number "2" 2) "34.04060000° N" "Ok" "December 5, 2014, 3:15 PM" (textwrap "The Apple Pan") (textwrap "Desc 2")]
          [(number "3" 3) "34.04740000° N" "Good" "August 1, 2014, 12:45 PM" (textwrap "The Gorbals") (textwrap "Desc 3")]]
         (map :row (rest (#'body/prep-for-html-rendering pacific-tz
                                                         {}
                                                         {:cols test-columns-with-remapping :rows test-data-with-remapping}))))))

;; There should be no truncation warning if the number of rows/cols is fewer than the row/column limit
(deftest no-truncation-warnig
  (is (= ""
         (html (#'body/render-truncation-warning 100 10)))))

;; When there are more rows than the limit, check to ensure a truncation warning is present
(deftest truncation-warning-when-rows-exceed-max
  (is (true?
       (let [html-output (html (#'body/render-truncation-warning 10 100))]
         (boolean (re-find #"Showing.*10.*of.*100.*rows" html-output))))))

(def ^:private test-columns-with-date-semantic-type
  (update test-columns 2 merge {:base_type    :type/Text
                                :effective_type :type/DateTime
                                :coercion_strategy :Coercion/ISO8601->DateTime}))

(deftest cols-with-semantic-types
  (is (= [{:row [(number "1" 1) "34.09960000° N" "April 1, 2014, 8:30 AM" (textwrap "Stout Burgers & Beers") (textwrap "Desc 1")]}
          {:row [(number "2" 2) "34.04060000° N" "December 5, 2014, 3:15 PM" (textwrap "The Apple Pan") (textwrap "Desc 2")]}
          {:row [(number "3" 3) "34.04740000° N" "August 1, 2014, 12:45 PM" (textwrap "The Gorbals") (textwrap "Desc 3")]}
          {:row [(number "4" 4) "0.00000000° N" "September 1, 2018, 7:32 PM" (textwrap "The Tipsy Tardigrade") (textwrap "Desc 4")]}
          {:row [(number "5" 5) "" "October 12, 2022, 5:55 AM" (textwrap "The Bungalow") (textwrap "Desc 5")]}]
         (rest (#'body/prep-for-html-rendering pacific-tz
                                               {}
                                               {:cols test-columns-with-date-semantic-type :rows example-test-data})))))

(deftest error-test
  (testing "renders error"
    (is (= "An error occurred while displaying this card."
           (-> (body/render :render-error nil nil nil nil nil) :content last str))))
  (testing "renders card error"
    (is (= "There was a problem with this question."
           (-> (body/render :card-error nil nil nil nil nil) :content last)))))

(defn- render-scalar-value [results]
  (-> (body/render :scalar nil pacific-tz nil nil results)
      :content
      last))

(deftest scalar-test
  (testing "renders int"
    (is (= "10"
           (render-scalar-value {:cols [{:name         "ID",
                                         :display_name "ID",
                                         :base_type    :type/BigInteger
                                         :semantic_type nil}]
                                 :rows [[10]]}))))
  (testing "renders float"
    (is (= "10.12"
           (render-scalar-value {:cols [{:name         "floatnum",
                                         :display_name "FLOATNUM",
                                         :base_type    :type/Float
                                         :semantic_type nil}]
                                 :rows [[10.12345]]}))))
  (testing "renders string"
    (is (= "foo"
           (render-scalar-value {:cols [{:name         "stringvalue",
                                         :display_name "STRINGVALUE",
                                         :base_type    :type/Text
                                         :semantic_type nil}]
                                 :rows [["foo"]]}))))
  (testing "renders date"
    (is (= "April 1, 2014, 8:30 AM"
           (render-scalar-value {:cols [{:name         "date",
                                         :display_name "DATE",
                                         :base_type    :type/DateTime
                                         :semantic_type nil}]
                                 :rows [["2014-04-01T08:30:00.0000"]]}))))
  (testing "Includes raw text"
    (testing "for scalars"
      (let [results {:cols [{:name         "stringvalue",
                             :display_name "STRINGVALUE",
                             :base_type    :type/Text
                             :semantic_type nil}]
                     :rows [["foo"]]}]
        (is (= "foo"
               (:render/text (body/render :scalar nil pacific-tz nil nil results))))
        (is (=? {:attachments nil
                 :content     [:div
                               {:style string?}
                               "foo"]
                 :render/text "foo"}
                (body/render :scalar nil pacific-tz nil nil results)))))
    (testing "for smartscalars"
      (let [cols    [{:name         "value",
                      :display_name "VALUE",
                      :base_type    :type/Decimal}
                     {:name           "time",
                      :display_name   "TIME",
                      :base_type      :type/DateTime
                      :effective_type :type/DateTime}]
            results {:cols cols
                     :rows [[40.0 :this-month]
                            [30.0 :last-month]
                            [20.0 :month-before]]
                     :insights [{:previous-value 30.0
                                 :unit :month
                                 :last-change 1.333333
                                 :col "value"
                                 :last-value 40.0}]}
            sameres {:cols cols
                     :rows [[40.0 :this-month]
                            [40.0 :last-month]
                            [40.0 :month-before]]
                     :insights [{:previous-value 40.0
                                 :unit :month
                                 :last-change 1.0
                                 :col "value"
                                 :last-value 40.0}]}
            ;; by "dumb" it is meant "without nonnil insights"
            dumbres {:cols cols
                     :rows [[20.0 :month-before]]
                     :insights [{:previous-value nil
                                 :unit nil
                                 :last-change nil
                                 :col "value"
                                 :last-value 20.0}]}]
        (is (= "40\nUp 133.33% vs. previous month: 30"
               (:render/text (body/render :smartscalar nil pacific-tz nil nil results))))
        (is (= "40\nNo change vs. previous month: 40"
               (:render/text (body/render :smartscalar nil pacific-tz nil nil sameres))))
        (is (= "20\nNothing to compare to."
               (:render/text (body/render :smartscalar nil pacific-tz nil nil dumbres))))
        (is (=? {:attachments nil
                 :content     vector?
                 :render/text "40\nUp 133.33% vs. previous month: 30"}
                (body/render :smartscalar nil pacific-tz nil nil results)))))))

(defn- replace-style-maps [hiccup-map]
  (walk/postwalk (fn [maybe-map]
                   (if (and (map? maybe-map)
                            (contains? maybe-map :style))
                     :style-map
                     maybe-map)) hiccup-map))

(def ^:private render-truncation-warning'
  (comp replace-style-maps #'body/render-truncation-warning))

(deftest no-truncation-warnig-for-style
  (is (nil? (render-truncation-warning' 10 5))))

(deftest renders-truncation
  (is (= [:div
          :style-map
          [:div :style-map "Showing "
           [:strong :style-map "20"] " of " [:strong :style-map "21"] " rows."]]
         (render-truncation-warning' 20 21))))

;; Test rendering a bar graph
;;
;; These test render the bar graph to ensure no exceptions are thrown, then look at the flattened HTML data structures
;; to see if the column names for the columns we're graphing are present in the result

(defn- flatten-html-data
  "Takes the tree-based Clojure HTML data structure and flattens it to a seq"
  [html-data]
  (tree-seq coll? seq html-data))

(def ^:private default-columns
  [{:name         "Price",
    :display_name "Price",
    :base_type    :type/BigInteger
    :semantic_type nil}
   {:name         "NumPurchased",
    :display_name "NumPurchased",
    :base_type    :type/BigInteger
    :semantic_type nil}])

(def ^:private default-multi-columns
  [{:name         "Price",
    :display_name "Price",
    :base_type    :type/BigInteger
    :semantic_type nil}
   {:name         "NumPurchased",
    :display_name "NumPurchased",
    :base_type    :type/BigInteger
    :semantic_type nil}
   {:name         "NumKazoos",
    :display_name "NumKazoos",
    :base_type    :type/BigInteger
    :semantic_type nil}
   {:name         "ExtraneousColumn",
    :display_name "ExtraneousColumn",
    :base_type    :type/BigInteger
    :semantic_type nil}])

(defn has-inline-image? [rendered]
  (some #{:img} (flatten-html-data rendered)))

(defn- render-funnel [results]
  (body/render :funnel :inline pacific-tz render.tu/test-card nil results))

(deftest render-funnel-test
  (testing "Test that we can render a funnel with all valid values"
    (is (has-inline-image?
         (render-funnel
          {:cols         default-columns
           :rows         [[10.0 1] [5.0 10] [2.50 20] [1.25 30]]
           :viz-settings {}})))))

(deftest render-funnel-test-2
  (testing "Test that we can render a funnel with extraneous columns and also weird strings stuck in places"
    (is (has-inline-image?
         (render-funnel
          {:cols         default-multi-columns
           :rows         [[10.0 1 2 2] [5.0 10 "11.1" 1] ["2.50" 20 1337 0] [1.25 30 -2 "-2"]]
           :viz-settings {}})))))

(deftest render-funnel-test-3
  (testing "Test that we can have some nil values stuck everywhere"
    (is (has-inline-image?
         (render-funnel
          {:cols         default-columns
           :rows         [[nil 1] [11.0 nil] [nil nil] [2.50 20] [1.25 30]]
           :viz-settings {}})))))

(deftest render-funnel-visualizer
  (testing "Visualizer funnel charts render"
    (let [test-card-1 {:id 192 :entity_id "abc" :name "SCALAR 3"}
          test-card-2 {:id 191 :entity_id "def" :name "SCALAR 2"}
          test-card-3 {:id 190 :entity_id "ghi" :name "SCALAR 1"}
          test-dashcard {:series-results
                         [{:result {:data {:rows [[420]] :cols [{:name "count"}]}}
                           :card test-card-2}
                          {:result {:data {:rows [[2495]] :cols [{:name "count"}]}}
                           :card test-card-3}]
                         :visualization_settings
                         {:visualization
                          {:display "funnel",
                           :columns
                           [{:name "COLUMN_1",
                             :display_name "Count"}
                            {:name "DIMENSION",
                             :display_name "DIMENSION"}],
                           :columnValuesMapping
                           {:COLUMN_1
                            [{:sourceId "card:abc", :originalName "count", :name "COLUMN_1"}
                             {:sourceId "card:def", :originalName "count", :name "COLUMN_2"}
                             {:sourceId "card:ghi", :originalName "count", :name "COLUMN_3"}],
                            :DIMENSION
                            ["$_card:abc_name" "$_card:def_name" "$_card:ghi_name"]},
                           :settings
                           {:card.title "My new visualization",
                            :funnel.metric "COLUMN_1",
                            :funnel.dimension "DIMENSION",
                            :funnel.order_dimension "DIMENSION",
                            :funnel.rows
                            [{:key "SCALAR 1" :name "SCALAR 1" :enabled true}
                             {:key "SCALAR 2" :name "SCALAR 2" :enabled true}
                             {:key "SCALAR 3" :name "SCALAR 3" :enabled true}]}}}}
          test-data {:rows [[168]]
                     :cols [{:name "count" :display_name "Count"}]}]
      (is (has-inline-image?
           (body/render :funnel :inline pacific-tz test-card-1 test-dashcard test-data))))))

(defn- render-error?
  [pulse-body]
  (let [content (get-in pulse-body [0 :content 0 :content])]
    (str/includes? content "error occurred")))

(deftest render-funnel-text-row-labels-test
  (testing "Static-viz Funnel Chart with text keys in viz-settings renders without error (#26944)."
    (mt/dataset test-data
      (let [funnel-query (mt/mbql-query orders
                           {:aggregation [[:count]]
                            :breakout    [!month-of-year.created_at]})
            funnel-card  {:display       :funnel
                          :dataset_query funnel-query
                          :visualization_settings
                          {:funnel.rows
                           [{:key "December", :name "December", :enabled true}
                            {:key "November", :name "November", :enabled true}
                            {:key "October", :name "October", :enabled true}
                            {:key "September", :name "September", :enabled true}
                            {:key "August", :name "August", :enabled true}
                            {:key "July", :name "July", :enabled true}
                            {:key "June", :name "June", :enabled true}
                            {:key "May", :name "May", :enabled true}
                            {:key "April", :name "April", :enabled true}
                            {:key "March", :name "March", :enabled true}
                            {:key "February", :name "February", :enabled true}
                            {:key "January", :name "January", :enabled true}],
                           :funnel.order_dimension "CREATED_AT"}}]
        (mt/with-temp [:model/Card {card-id :id} funnel-card]
          (let [doc        (render.tu/render-card-as-hickory! card-id)
                pulse-body (hik.s/select
                            (hik.s/class "pulse-body")
                            doc)]
            (is (not (render-error? pulse-body)))))))))

(def ^:private funnel-rows
  [["cart" 1500]
   ["checkout" 450]
   ["homepage" 10000]
   ["product_page" 5000]
   ["purchase" 225]])

(tx/defdataset funnel-data
  [["stages"
    [{:field-name "stage", :base-type :type/Text}
     {:field-name "count", :base-type :type/Quantity}]
    funnel-rows]])

(deftest render-funnel-with-row-keys-test
  (testing "Static-viz Funnel Chart with text keys in viz-settings and text in returned
            rows renders without error and in the order specified by the viz-settings (#39743)."
    (mt/dataset funnel-data
      (let [funnel-query {:database (mt/id)
                          :type     :query
                          :query
                          {:source-table (mt/id :stages)
                           ;; we explicitly select the 2 columns because if we don't the query returns the ID as well.
                           ;; this is done here to construct the failing case resulting from the reproduction steps in issue #39743
                           :fields       [[:field (mt/id :stages :stage)]
                                          [:field (mt/id :stages :count)]]}}
            funnel-card  {:display       :funnel
                          :dataset_query funnel-query
                          :visualization_settings
                          {:funnel.rows
                           [{:key "homepage" :name "homepage" :enabled true}
                            {:key "product_page" :name "product_page" :enabled true}
                            {:key "cart" :name "cart" :enabled true}
                            {:key "checkout" :name "checkout" :enabled true}
                            {:key "purchase" :name "purchase" :enabled true}]}}]
        (mt/with-temp [:model/Card {card-id :id} funnel-card]
          (let [row-names      (into #{} (map first funnel-rows))
                doc            (render.tu/render-card-as-hickory! card-id)
                section-labels (->> doc
                                    (hik.s/select (hik.s/tag :tspan))
                                    (mapv (comp first :content))
                                    (filter row-names))]
            (is (= (map :key (get-in funnel-card [:visualization_settings :funnel.rows]))
                   section-labels))))))))

(deftest render-pie-chart-test
  (testing "The static-viz pie chart renders correctly."
    (mt/dataset test-data
      (let [q       (mt/mbql-query products
                      {:aggregation [[:count]]
                       :breakout    [$category]})
            colours {:Doohickey "#AAAAAA"
                     :Gadget    "#BBBBBB"
                     :Gizmo     "#CCCCCC"
                     :Widget    "#DDDDDD"}]
        (mt/with-temp [:model/Card {card-a-id :id} {:name                   "not-a-crumble"
                                                    :display                :pie
                                                    :visualization_settings {:pie.colors colours}
                                                    :dataset_query          q}
                       :model/Card {card-b-id :id} {:name                   "maybe-a-donut"
                                                    :display                :pie
                                                    :visualization_settings {:pie.show_legend false
                                                                             :pie.show_total  false
                                                                             :pie.colors      colours}
                                                    :dataset_query          q}]
          (let [card-a-doc (render.tu/render-card-as-hickory! card-a-id)
                card-b-doc (render.tu/render-card-as-hickory! card-b-id)]
            ;; The test asserts that all 4 slices exist by seeing that each path element has the colour assigned to that category
            ;; we should expect to see each of the 4 (and only those 4) colours.
            ;; This is also true of the colours for the legend circle elements.
            ;; When legend and Totals are disabled, we should expect those elements not to exist in the render
            (doseq [[doc test-str expectations] [[card-a-doc "Renders with legend and 'total'."
                                                  {:legend-els-colours #{"#AAAAAA" "#BBBBBB" "#CCCCCC" "#DDDDDD"}
                                                   :slice-els-colours  #{"#AAAAAA" "#BBBBBB" "#CCCCCC" "#DDDDDD"}
                                                   :total-els-text     #{"TOTAL"}}]
                                                 [card-b-doc "Renders legend even if disabled in viz-settings, so that static pie charts are legible, but does not render total if it is disabled."
                                                  {:legend-els-colours #{"#AAAAAA" "#BBBBBB" "#CCCCCC" "#DDDDDD"}
                                                   :slice-els-colours  #{"#AAAAAA" "#BBBBBB" "#CCCCCC" "#DDDDDD"}
                                                   :total-els-text     #{}}]]]
              (let [legend-elements (->> (hik.s/select (hik.s/tag :circle) doc)
                                         (map #(get-in % [:attrs :fill]))
                                         set)
                    slice-elements  (->> (hik.s/select (hik.s/tag :path) doc)
                                         (map #(get-in % [:attrs :fill]))
                                         set)
                    total-elements  (->> (hik.s/select (hik.s/find-in-text #"TOTAL") doc)
                                         (map (fn [el] (-> el :content first)))
                                         set)]
                (testing test-str
                  (is (= expectations
                         {:legend-els-colours legend-elements
                          :slice-els-colours  slice-elements
                          :total-els-text     total-elements})))))))))))

(deftest render-progress
  (let [col [{:name          "NumPurchased",
              :display_name  "NumPurchased",
              :base_type     :type/Integer
              :semantic_type nil}]
        render  (fn [rows]
                  (body/render :progress :inline pacific-tz
                               render.tu/test-card
                               nil
                               {:cols col :rows rows}))]
    (testing "Renders without error"
      (let [rendered-info (render [[25]])]
        (is (has-inline-image? rendered-info))))
    (testing "Renders negative value without error"
      (let [rendered-info (render [[-25]])]
        (is (has-inline-image? rendered-info))))))

(deftest ^:parallel format-percentage-test
  (are [value expected] (= expected
                           (body/format-percentage 12345.4321 value))
    ".," "1,234,543.21%"
    "^&" "1&234&543^21%"
    " "  "1,234,543 21%"
    nil  "1,234,543.21%"
    ""   "1,234,543.21%"))

(deftest add-dashcard-timeline-events-test-34924
  (testing "Timeline events should be added to the isomorphic renderer stages"
    (mt/dataset test-data
      (mt/with-current-user (mt/user->id :crowberto)
        (mt/with-temp [:model/Collection {collection-id :id :as _collection} {:name "Rasta's Collection"}
                       :model/Timeline tl-a {:name "tl-a" :collection_id collection-id}
                       :model/Timeline tl-b {:name "tl-b" :collection_id collection-id}
                       :model/TimelineEvent _ {:timeline_id (u/the-id tl-a) :name "un-1"}
                       :model/TimelineEvent _ {:timeline_id (u/the-id tl-a) :name "archived-1"}
                       :model/TimelineEvent _ {:timeline_id (u/the-id tl-b) :name "un-2"}
                       :model/TimelineEvent _ {:timeline_id (u/the-id tl-b) :name "archived-2"}
                       :model/Card {dataset-query :dataset_query
                                    :as           card} {:name          "Dashboard Test Card"
                                                         :collection_id collection-id
                                                         :dataset_query (mt/mbql-query orders
                                                                          {:breakout    [!month.created_at],
                                                                           :aggregation [[:sum $subtotal]
                                                                                         [:avg $subtotal]]})
                                                         :creator_id    (mt/user->id :crowberto)}]
          (let [data                   (qp/process-query dataset-query)
                combined-cards-results [(notification.execute/execute-card (:creator_id card) (:id card))]
                cards-with-data        (map
                                        (comp
                                         #'body/add-dashcard-timeline-events
                                         (fn [c d] {:card c :data d}))
                                        (cons card (map :card combined-cards-results))
                                        (cons data (map #(get-in % [:result :data]) combined-cards-results)))]
            (testing "The underlying add-dashcard-timeline-events call adds the timeline events to the card"
              (is (=? [tl-a tl-b] (:timeline_events (#'body/add-dashcard-timeline-events {:card card})))))
            (is (= 2 (count (:timeline_events (first cards-with-data)))))))))))

(deftest unknown-column-settings-test
  (testing "Unknown `:column_settings` keys don't break static-viz rendering with a Null Pointer Exception (#27941)."
    (mt/dataset test-data
      (let [q   (mt/mbql-query reviews
                  {:aggregation [[:sum $rating]],
                   :breakout    [!week.created_at $reviewer],
                   :filter      [:between $product_id 0 10]})
            viz {:pivot_table.column_split
                 {:rows    ["REVIEWER"],
                  :columns ["CREATED_AT"],
                  :values  ["sum"]}
                 :column_settings
                 {(format "[\"ref\",[\"field\",%s,{\"base-type\":\"type/DateTime\"}]]" (mt/id :reviews :created_at))
                  {:pivot_table.column_sort_order "ascending"}}}]
        (mt/dataset test-data
          (mt/with-temp [:model/Card                 {card-id :id} {:display                :pivot
                                                                    :dataset_query          q
                                                                    :visualization_settings viz}]
            (testing "the render succeeds with unknown column settings keys"
              (is (seq (render.tu/render-card-as-hickory! card-id))))))))))

(deftest trend-chart-renders-in-alerts-test
  (testing "Trend charts render successfully in Alerts. (#39854)"
    (mt/dataset test-data
      (let [q (mt/mbql-query orders
                {:aggregation [[:count]]
                 :breakout    [!month.created_at]})]
        ;; Alerts are set on Questions. They run through the 'pulse' code the same as subscriptions,
        ;; But will not have any Dashcard data associated, which caused an error in the static-viz render code
        ;; which implicitly expected a DashCard to exist
        ;; Here, we simulate an Alert (NOT a subscription) by only providing a card and not mocking a DashCard.
        (mt/with-temp [:model/Card {card-id :id} {:display       :smartscalar
                                                  :dataset_query q}]
          (let [doc       (render.tu/render-card-as-hickory! card-id)
                span-text (->> doc
                               (hik.s/select (hik.s/tag :span))
                               (mapv (comp first :content))
                               (filter string?)
                               (filter #(str/includes? % "vs.")))]
            ;; we look for content that we are certain comes from a
            ;; successfully rendered trend chart.
            (is (= 1 (count span-text)))))))))

(defn- content-selector
  [content-to-match]
  (fn [loc]
    (let [{:keys [content]} (zip/node loc)]
      (= content content-to-match))))

(defn- parse-transform [s]
  (let [numbers (-> (re-find #"matrix\((.+)\)" s)
                    second
                    (str/split #","))
        keys [:a :b :c :d :e :f]]
    (zipmap keys (map parse-double numbers))))

(deftest axis-selection-for-series-test
  (testing "When the user specifies all series to be on left or right, it will render. (#38839)"
    (mt/dataset test-data
      (let [q   (mt/mbql-query products
                  {:aggregation [[:count]]
                   :breakout    [$category
                                 [:field (mt/id :products :price)
                                  {:base-type :type/Float, :binning {:strategy :default}}]]})
            viz (fn [dir]
                  {:series_settings
                   {:Doohickey {:axis dir}
                    :Gadget    {:axis dir}
                    :Gizmo     {:axis dir}
                    :Widget    {:axis dir}},
                   :graph.dimensions ["PRICE" "CATEGORY"],
                   :graph.metrics    ["count"]})]
        (mt/with-temp [:model/Card {left-card-id :id} {:display                :bar
                                                       :visualization_settings (viz "left")
                                                       :dataset_query          q}
                       :model/Card {right-card-id :id} {:display                :bar
                                                        :visualization_settings (viz "right")
                                                        :dataset_query          q}]
          (testing "Every series on the left correctly only renders left axis."
            (let [doc                (render.tu/render-card-as-hickory! left-card-id)
                  axis-label-element (hik.s/select (content-selector ["Count"]) doc)
                  ;; the axis label has a :transform property like this: "matrix(0,1,-1,0,520,162.3245)"
                  ;; which is explained here: https://developer.mozilla.org/en-US/docs/Web/SVG/Attribute/transform
                  ;; If we assume the 'previous coords' for the label were 0 0, we can ignore most matrix entries,
                  ;; and only really care about the X position, which ends up being the :e entry in the matrix.
                  ;; the '200' is an arbitrary X value that is close-ish to the middle of the chart's graphics
                  ;; which should mean the assertions pass, even if the static-viz output changes a bit.
                  ;; Well, one can hope, at least :)
                  axis-y-transform   (-> axis-label-element
                                         (get-in [0 :attrs :transform])
                                         parse-transform
                                         :e)]
              (is (= 1 (count axis-label-element)))
              (is (> 200 axis-y-transform))))
          (testing "Every series on the right correctly only renders right axis."
            (let [doc                (render.tu/render-card-as-hickory! right-card-id)
                  axis-label-element (hik.s/select (content-selector ["Count"]) doc)
                  axis-y-transform   (-> axis-label-element
                                         (get-in [0 :attrs :transform])
                                         parse-transform
                                         :e)]
              (is (= 1 (count axis-label-element)))
              (is (< 200 axis-y-transform)))))))))

(deftest multiseries-dashcard-render-test
  (testing "Multi-series dashcards render with every series. (#42730)"
    (mt/dataset test-data
      (let [q (mt/mbql-query products
                {:aggregation [[:count]]
                 :breakout    [$category]})]
        (mt/with-temp [:model/Card {card-a-id :id} {:display       :bar
                                                    :dataset_query q}
                       :model/Card {card-b-id :id} {:display       :bar
                                                    :dataset_query q}
                       :model/Dashboard {dash-id :id} {}
                       :model/DashboardCard {dashcard-id :id} {:dashboard_id dash-id
                                                               :card_id      card-a-id}
                       :model/DashboardCardSeries _ {:dashboardcard_id dashcard-id
                                                     :card_id          card-b-id}]
          (mt/with-current-user (mt/user->id :rasta)
            (let [card-doc               (render.tu/render-card-as-hickory! card-a-id)
                  dashcard-doc           (render.tu/render-dashcard-as-hickory! dashcard-id)
                  card-path-elements     (hik.s/select (hik.s/tag :path) card-doc)
                  card-paths-count       (count card-path-elements)
                  dashcard-path-elements (hik.s/select (hik.s/tag :path) dashcard-doc)
                  expected-dashcard-paths-count (+ 4 card-paths-count)]
              ;; SVG Path elements are used to draw the bars in a bar graph.
              ;; They are also used to create the axes lines, so we establish a count of a single card's path elements
              ;; to compare against.
              ;; Since we know that the Products sample data has 4 Product categories, we can reliably expect
              ;; that Adding a series to the card that is identical to the first card will result in 4 more path elements.
              (is (= expected-dashcard-paths-count (count dashcard-path-elements))))))))))

(deftest multiseries-dashcard-render-filters-test
  (testing "Multi-series dashcards render with every series properly filtered (#39083)"
    (mt/dataset test-data
      (let [q (mt/mbql-query orders
                {:aggregation [[:count]]
                 :breakout    [!month.created_at]})]
        (mt/with-temp [:model/Card {card-a-id :id} {:name          "series_a"
                                                    :display       :bar
                                                    :dataset_query q}
                       :model/Card {card-b-id :id} {:name          "series_b"
                                                    :display       :bar
                                                    :dataset_query q}
                       :model/Dashboard {dash-id :id} {:parameters [{:name      "Date Filter"
                                                                     :id        "944bba5f"
                                                                     :type      :date/month-year
                                                                     :sectionId "date"}]}
                       :model/DashboardCard {dashcard-id :id}
                       {:dashboard_id dash-id
                        :card_id      card-a-id
                        :visualization_settings
                        {:graph.dimensions ["CREATED_AT"],
                         :series_settings  {"series_a" {:color "#AAA"}
                                            "series_b" {:color "#BBB"}}
                         :graph.metrics    ["count"]}
                        :parameter_mappings
                        [{:parameter_id "944bba5f"
                          :card_id      card-a-id
                          :target       [:dimension [:field (mt/id :orders :created_at) {:base-type :type/DateTime}]]}
                         {:parameter_id "944bba5f"
                          :card_id      card-b-id
                          :target       [:dimension [:field (mt/id :orders :created_at) {:base-type :type/DateTime}]]}]}
                       :model/DashboardCardSeries _ {:dashboardcard_id dashcard-id
                                                     :card_id          card-b-id}]
          (mt/with-current-user (mt/user->id :rasta)
            (let [dashcard-doc           (render.tu/render-dashcard-as-hickory!
                                          dashcard-id
                                          [{:value     "2019-05"
                                            :id        "944bba5f"
                                            :sectionId "date"
                                            :type      :date/month-year
                                            :target    [:dimension [:field (mt/id :orders :created_at) {:base-type :type/DateTime}]]}])
                  dashcard-path-elements (hik.s/select (hik.s/tag :path) dashcard-doc)
                  ;; the series bars each have distinct colours, so we can group by those attrs to get a count.
                  ;; and remove any paths that are 'transparent'
                  series-counts          (-> (group-by #(get-in % [:attrs :fill]) dashcard-path-elements)
                                             (dissoc "transparent")
                                             (update-vals count))]
              ;; The series count should be 1 for each series, since we're filtering by a single month of the year
              ;; and each question is set up with a breakout on :created_at by :month, so filtering on a single month produces just 1 bar.
              (is (= [1 1]
                     (vals series-counts))))))))))

(defn- render-card
  [render-type card data]
  (body/render render-type :attachment (channel.render/defaulted-timezone card) card nil data))

(deftest render-cards-are-thread-safe-test-for-js-visualization
  (mt/with-temp [:model/Card card {:dataset_query          (mt/mbql-query orders
                                                             {:aggregation [[:count]]
                                                              :breakout    [$orders.created_at]
                                                              :limit       1})
                                   :display                :line
                                   :visualization_settings {:graph.dimensions ["CREATED_AT"]
                                                            :graph.metrics    ["count"]}}]
    (let [data (:data (qp/process-query (:dataset_query card)))]
      (is (every? some? (mt/repeat-concurrently 3 #(render-card :javascript_visualization card data)))))))

(deftest render-cards-are-thread-safe-test-for-table
  (mt/with-temp [:model/Card card {:dataset_query (mt/mbql-query venues {:limit 1})
                                   :display       :table}]
    (let [data (:data (qp/process-query (:dataset_query card)))]
      (is (every? some? (mt/repeat-concurrently 3 #(render-card :table card data)))))))

(deftest table-renders-respect-dashcard-viz-settings
  (testing "Rendered Tables respect the provided viz-settings on the dashcard."
    (mt/dataset test-data
      (mt/with-temp [:model/Card {card-id :id} {:display       :table
                                                :dataset_query {:database (mt/id)
                                                                :type     :query
                                                                :query    {:source-table (mt/id :orders)}}
                                                :visualization_settings
                                                {:table.cell_column "SUBTOTAL"
                                                 :column_settings   {(format "[\"ref\",[\"field\",%d,null]]" (mt/id :orders :subtotal))
                                                                     {:column_title "SUB CASH MONEY"}}}}
                     :model/Dashboard {dashboard-id :id} {}
                     :model/DashboardCard {dashcard-id :id}  {:dashboard_id dashboard-id
                                                              :card_id      card-id
                                                              :visualization_settings
                                                              {:table.cell_column "TOTAL"
                                                               :column_settings   {(format "[\"ref\",[\"field\",%d,null]]" (mt/id :orders :total))
                                                                                   {:column_title "CASH MONEY"}}}}]
        (mt/with-current-user (mt/user->id :rasta)
          (let [card-doc        (render.tu/render-card-as-hickory! card-id)
                card-header-els (hik.s/select (hik.s/tag :th) card-doc)
                dashcard-doc    (render.tu/render-dashcard-as-hickory! dashcard-id)
                dash-header-els (hik.s/select (hik.s/tag :th) dashcard-doc)
                card-header     ["ID" "User ID" "Product ID" "SUB CASH MONEY" "Tax"
                                 "Total" "Discount ($)" "Created At" "Quantity"]
                dashcard-header ["ID" "User ID" "Product ID" "SUB CASH MONEY" "Tax"
                                 "CASH MONEY" "Discount ($)" "Created At" "Quantity"]]
            (is (= {:card     card-header
                    :dashcard dashcard-header}
                   {:card     (mapcat :content card-header-els)
                    :dashcard (mapcat :content dash-header-els)}))))))))

(deftest table-renders-respect-conditional-formatting
  (testing "Rendered Tables respect the conditional formatting on a card."
    (let [ids-to-colour [1 2 3 5 8 13]]
      (mt/dataset test-data
        (mt/with-temp [:model/Card {card-id :id} {:display       :table
                                                  :dataset_query {:database (mt/id)
                                                                  :type     :query
                                                                  :query    {:source-table (mt/id :orders)}}
                                                  :visualization_settings
                                                  {:table.column_formatting
                                                   (into []
                                                         (map-indexed
                                                          (fn [idx id-to-colour]
                                                            {:columns       ["ID"]
                                                             :type          "single"
                                                             :operator      "="
                                                             :value         id-to-colour
                                                             :color         "#A989C5"
                                                             :highlight_row false
                                                             :id            idx})
                                                          ids-to-colour))}}]
          (mt/with-current-user (mt/user->id :rasta)
            (let [card-doc     (render.tu/render-card-as-hickory! card-id)
                  card-row-els (hik.s/select (hik.s/tag :tr) card-doc)]
              (is (= (mapv str ids-to-colour)
                     (keep
                      (fn [{:keys [attrs] :as el}]
                        (let [style-str (:style attrs)]
                          (when (str/includes? style-str "background-color")
                            (-> el :content first))))
                      (mapcat :content (take 20 card-row-els))))))))))))

(deftest table-renders-conditional-formatting-even-with-hidden-column
  (testing "Rendered Tables respect the conditional formatting on a card."
    (let [ids-to-colour [1 2 3 5 8 13]]
      (mt/dataset test-data
        (mt/with-temp [:model/Card {card-id :id} {:display       :table
                                                  :dataset_query {:database (mt/id)
                                                                  :type     :query
                                                                  :query    {:source-table (mt/id :orders)}}
                                                  :visualization_settings
                                                  {:table.columns
                                                   [{:name "ID" :enabled false}
                                                    {:name "TOTAL" :enabled true}
                                                    {:name "TAX" :enabled true}
                                                    {:name "USER_ID" :enabled true}
                                                    {:name "CREATED_AT" :enabled true}
                                                    {:name "QUANTITY" :enabled true}
                                                    {:name "SUBTOTAL" :enabled true}
                                                    {:name "PRODUCT_ID" :enabled true}
                                                    {:name "DISCOUNT" :enabled true}]
                                                   :table.column_formatting
                                                   (into []
                                                         (map-indexed
                                                          (fn [idx id-to-colour]
                                                            {:columns       ["ID"]
                                                             :type          "single"
                                                             :operator      "="
                                                             :value         id-to-colour
                                                             :color         "#A989C5"
                                                             :highlight_row true
                                                             :id            idx})
                                                          ids-to-colour))}}]
          (mt/with-current-user (mt/user->id :rasta)
            (let [card-doc     (render.tu/render-card-as-hickory! card-id)
                  card-row-els (hik.s/select (hik.s/tag :tr) card-doc)]
              (is (=  ids-to-colour
                      (keep
                       (fn [[id row-els]]
                         (let [{:keys [attrs]} (first row-els)
                               style-str       (:style attrs)]
                           (when (str/includes? style-str "background-color")
                             id)))
                       (map vector
                            (range)
                            (map :content (take 20 card-row-els)))))))))))))

(deftest table-renders-excludes-pivot-grouping
  (testing "Rendered Tables respect the provided viz-settings on the dashcard."
    (mt/dataset test-data
      (mt/with-temp [:model/Card {card-id :id}
                     {:display                :pivot
                      :visualization_settings {:pivot_table.column_split
                                               {:rows    ["CATEGORY"]
                                                :columns ["CREATED_AT"]
                                                :values  ["sum"]}
                                               :column_settings
                                               {"[\"name\",\"sum\"]" {:number_style       "currency"
                                                                      :currency_in_header false}}}
                      :dataset_query          (mt/mbql-query products
                                                {:aggregation [[:sum $price]]
                                                 :breakout    [$category !year.created_at]})}]
        (mt/with-current-user (mt/user->id :rasta)
          (let [card-doc        (render.tu/render-pivot-card-as-hickory! card-id)
                card-header-els (hik.s/select (hik.s/tag :th) card-doc)]
            (is (=  ["Category" "Created At: Year" "Sum of Price"]
                    (mapv (comp first :content) card-header-els)))))))))

(deftest render-sankey-chart-test
  (testing "The static-viz sankey chart renders correctly."
    (mt/dataset test-data
      (let [q       (mt/mbql-query products
                      {:aggregation  [[:count]]
                       :breakout     [$category
                                      [:field (mt/id :products :price) {:base-type :type/Float
                                                                        :binning    {:strategy :default}}]]})
            card    {:name           "sankey-test"
                     :display        :sankey
                     :dataset_query  q
                     :visualization_settings
                     {:sankey.source "CATEGORY"
                      :sankey.target "PRICE"
                      :sankey.value  "count"}}]
        (mt/with-temp [:model/Card {card-id :id} card]
          (let [doc (render.tu/render-card-as-hickory! card-id)
                category-text (->> (hik.s/select (hik.s/find-in-text #"Doohickey") doc)
                                   (map (fn [el] (-> el :content first)))
                                   first)]
            (testing "Renders with at least one category name visible"
              (is (= "Doohickey" category-text)))))))))

(deftest render-correct-day-of-week-test
  (testing "The static-viz bar chart renders with the correct start of the week."
    (mt/with-temporary-setting-values [start-of-week "monday"]
      (mt/dataset test-data
        (let [q    (mt/mbql-query products
                     {:aggregation [[:sum $price]]
                      :breakout    [$category
                                    !day-of-week.created_at]})
              card {:name                   "bar-test"
                    :display                :bar
                    :dataset_query          q
                    :visualization_settings {:graph.dimensions ["CREATED_AT"]
                                             :graph.metrics ["sum"]}}]
          (mt/with-temp [:model/Card {card-id :id} card]
            (let [doc            (render.tu/render-card-as-hickory! card-id)
                  first-day-text (->> (hik.s/select (hik.s/tag :text) doc)
                                      (map (fn [el] (-> el :content first)))
                                      (take-last 7)
                                      (map str/trim)
                                      first)]
              (testing "Renders with correct day of week first"
                (is (= "Monday" first-day-text))))))))))

(deftest render-correct-custom-date-style
  (testing "The static-viz respects custom formatting for temporal axis label"
    (mt/with-temporary-setting-values [custom-formatting {:type/Temporal
                                                          {:date_style "YYYY/M/D"
                                                           :date_separator "/"}}]
      (mt/dataset test-data
        (let [q    (mt/mbql-query products
                     {:aggregation [[:count]]
                      :breakout    [!month.created_at]})
              card {:name                   "bar-test"
                    :display                :bar
                    :dataset_query          q
                    :visualization_settings {:graph.dimensions ["CREATED_AT"]
                                             :graph.metrics ["count"]}}]
          (mt/with-temp [:model/Card {card-id :id} card]
            (let [doc    (render.tu/render-card-as-hickory! card-id)
                  label  (->> (hik.s/select (hik.s/tag :text) doc)
                              (map (fn [el] (-> el :content first)))
                              (take-last 3)
                              (map str/trim)
                              first)]
              (testing "Renders with correct day of week first"
                (is (= "2017/1" label))))))))))

(deftest render-correct-whitelabel-colors
  (when config/ee-available?
    (testing "The static-viz respects custom whitelabel colors"
      (mt/with-premium-features #{:whitelabel}
        (mt/with-temporary-setting-values [application-colors {:accent0 "#0005FF"}]
          (mt/dataset test-data
            (let [q    (mt/mbql-query products
                         {:aggregation [[:count]]
                          :breakout    [!month.created_at]})
                  card {:name                   "bar-test"
                        :display                :bar
                        :dataset_query          q
                        :visualization_settings {:graph.dimensions ["CREATED_AT"]
                                                 :graph.metrics ["count"]}}]
              (mt/with-temp [:model/Card {card-id :id} card]
                (let [doc    (render.tu/render-card-as-hickory! card-id)
                      svg    (html doc)]
                  (testing "Renders with custom whitelabel color"
                    (is (str/includes? svg "#0005FF"))))))))))))

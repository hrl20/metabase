(ns metabase.lib.underlying
  "Helpers for getting at \"underlying\" or \"top-level\" queries and columns.
  This logic is shared by a handful of things like drill-thrus."
  (:require
   [clojure.set :as set]
   [metabase.lib.aggregation :as lib.aggregation]
   [metabase.lib.breakout :as lib.breakout]
   [metabase.lib.equality :as lib.equality]
   [metabase.lib.field :as lib.field]
   [metabase.lib.metadata :as lib.metadata]
   [metabase.lib.metadata.calculation :as lib.metadata.calculation]
   [metabase.lib.ref :as lib.ref]
   [metabase.lib.schema :as lib.schema]
   [metabase.lib.schema.metadata :as lib.schema.metadata]
   [metabase.lib.util :as lib.util]
   [metabase.util.malli :as mu]))

(mu/defn- pop-until-aggregation-or-breakout :- [:tuple [:maybe ::lib.schema/query] [:int {:max -1}]]
  "Strips off any trailing stages that do not contain aggregations or breakouts.

  Returns a tuple of [query, stage-number] where `query` is the first stage with aggregations or breakouts and
  `stage-number` is the (negative) index of that stage relative to the original query.

  If there are no such stages, returns [nil, -1]."
  [query :- ::lib.schema/query]
  (loop [query query
         i -1]
    (if (and (empty? (lib.aggregation/aggregations query -1))
             (empty? (lib.breakout/breakouts query -1)))
      ;; No aggregations or breakouts in the last stage, so pop it off and recur.
      (let [popped (update query :stages pop)]
        (if (seq (:stages popped))
          (recur popped (dec i))
          [nil, -1]))
      [query, i])))

(mu/defn top-level-query :- ::lib.schema/query
  "Returns the \"top-level\" query for the given query.

  That means dropping any trailing filters, fields, etc. to get back to the last stage that has an aggregation or
  breakout. If there are no such stages, the original query is returned.

  If the database does not support nested queries, this also returns the original query."
  [query :- ::lib.schema/query]
  (or (when (lib.metadata/database-supports? query :nested-queries)
        (first (pop-until-aggregation-or-breakout query)))
      query))

(mu/defn top-level-stage-number :- :int
  "Returns the stage-number of the [[top-level-query]] for the given query.

  If there are no such stages, or if the database does not supported nested queries, returns -1."
  [query :- ::lib.schema/query]
  (or (when (lib.metadata/database-supports? query :nested-queries)
        (second (pop-until-aggregation-or-breakout query)))
      -1))

(def ^:private TopLevelColumnOptions
  [:map
   [:rename-superflous-options? {:optional true} :boolean]])

(mu/defn top-level-column :- [:maybe ::lib.schema.metadata/column]
  "Given a column, returns the \"top-level\" equivalent.

  Top-level means to find the corresponding column in the [[top-level-query]], which requires walking back through the
  stages finding the equivalent column at each one.

  Returns nil if the column can't be traced back to the top-level query."
  ([query  :- ::lib.schema/query
    column :- ::lib.schema.metadata/column]
   (top-level-column query column :rename-superflous-options? true))
  ([query                                  :- ::lib.schema/query
    column                                 :- ::lib.schema.metadata/column
    & {:keys [rename-superflous-options?]} :- TopLevelColumnOptions]
   (let [top-query (top-level-query query)]
     (if (= query top-query)
       column ;; Unchanged if this is already a top-level query. That includes keeping the "superfluous" options!
       (loop [query  query
              column column]
         (if (= query top-query)
           (cond-> column
             rename-superflous-options?
             ;; Once we've found it, rename superfluous options, unless disabled by the caller, because under normal
             ;; circumstances, you will not need them. On the off chance you do need them, they'll still be available.
             (set/rename-keys {::lib.field/temporal-unit ::temporal-unit
                               ::lib.field/binning       ::binning}))
           (let [prev-cols (lib.metadata.calculation/returned-columns query -2 (lib.util/previous-stage query -1))
                 prev-col  (lib.equality/find-matching-column query -2 (lib.ref/ref column) prev-cols)]
             (when prev-col
               (recur (update query :stages pop) prev-col)))))))))

(mu/defn has-aggregation-or-breakout?
  "Whether the `query` has an aggregation or breakout clause in some query stage."
  [query :- ::lib.schema/query]
  (some? (first (pop-until-aggregation-or-breakout query))))

(mu/defn- has-source-or-underlying-source-fn :- [:function
                                                 [:-> [:maybe ::lib.schema.metadata/column] :boolean]
                                                 [:->
                                                  ::lib.schema/query
                                                  [:maybe ::lib.schema.metadata/column]
                                                  :boolean]]
  [source :- :keyword]
  (fn has-source?
    ([column]
     (= (:lib/source column) source))
    ([query column]
     (boolean
      (and (seq column)
           (or (has-source? column)
               (has-source? (top-level-column query column))))))))

(def aggregation-sourced?
  "Does column or top-level-column have :source/aggregations?"
  (has-source-or-underlying-source-fn :source/aggregations))

(def breakout-sourced?
  "Does column or top-level-column have :source/breakouts?"
  (has-source-or-underlying-source-fn :source/breakouts))

(mu/defn strictly-underlying-aggregation? :- :boolean
  "Does the [[top-level-column]] (but not `column` itself) in `query` have :source/aggregations?"
  [query  :- ::lib.schema/query
   column :- [:maybe ::lib.schema.metadata/column]]
  (and (not (aggregation-sourced? column))
       (aggregation-sourced? query column)))

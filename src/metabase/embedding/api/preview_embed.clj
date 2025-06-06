(ns metabase.embedding.api.preview-embed
  "Endpoints for previewing how Cards and Dashboards will look when embedding them.
   These endpoints are basically identical in functionality to the ones in `/api/embed`, but:

   1.  Require admin access
   2.  Ignore the values of `:enabled_embedding` for Cards/Dashboards
   3.  Ignore the `:embed_params` whitelist for Card/Dashboards, instead using a field called `:_embedding_params` in
       the JWT token itself.

   Refer to the documentation for those endpoints for further details."
  (:require
   [metabase.api.common :as api]
   [metabase.api.common.validation :as validation]
   [metabase.api.macros :as api.macros]
   [metabase.embedding.api.common :as api.embed.common]
   [metabase.embedding.jwt :as embed]
   [metabase.query-processor.pivot :as qp.pivot]
   [metabase.util.malli.schema :as ms]
   [ring.util.codec :as codec]))

(defn- check-and-unsign [token]
  (api/check-superuser)
  (validation/check-embedding-enabled)
  (embed/unsign token))

(api.macros/defendpoint :get "/card/:token"
  "Fetch a Card you're considering embedding by passing a JWT `token`."
  [{:keys [token]} :- [:map
                       [:token ms/NonBlankString]]]
  (let [unsigned-token (check-and-unsign token)]
    (api.embed.common/card-for-unsigned-token unsigned-token
                                              :embedding-params (embed/get-in-unsigned-token-or-throw unsigned-token [:_embedding_params]))))

(def ^:private max-results
  "Embedding previews need to be limited in size to avoid performance issues (#20938)."
  2000)

(api.macros/defendpoint :get "/card/:token/query"
  "Fetch the query results for a Card you're considering embedding by passing a JWT `token`."
  [{:keys [token]} :- [:map
                       [:token ms/NonBlankString]]
   query-params]
  (let [unsigned-token (check-and-unsign token)
        card-id        (embed/get-in-unsigned-token-or-throw unsigned-token [:resource :question])]
    (api.embed.common/process-query-for-card-with-params
     :export-format    :api
     :card-id          card-id
     :token-params     (embed/get-in-unsigned-token-or-throw unsigned-token [:params])
     :embedding-params (embed/get-in-unsigned-token-or-throw unsigned-token [:_embedding_params])
     :constraints      {:max-results max-results}
     :query-params     (api.embed.common/parse-query-params query-params))))

(api.macros/defendpoint :get "/card/:token/params/:param-key/remapping"
  "Embedded version of api.card filter values endpoint."
  [{:keys [token param-key]} :- [:map
                                 [:token     string?]
                                 [:param-key string?]]
   {:keys [value]}           :- [:map [:value :string]]]
  (let [unsigned-token (check-and-unsign token)
        card           (api.embed.common/card-for-unsigned-token
                        unsigned-token
                        :embedding-params (embed/get-in-unsigned-token-or-throw unsigned-token [:_embedding_params]))]
    (api.embed.common/card-param-remapped-value {:unsigned-token unsigned-token
                                                 :card           card
                                                 :param-key      param-key
                                                 :value          (codec/url-decode value)})))

(api.macros/defendpoint :get "/dashboard/:token"
  "Fetch a Dashboard you're considering embedding by passing a JWT `token`. "
  [{:keys [token]} :- [:map
                       [:token ms/NonBlankString]]]
  (let [unsigned-token (check-and-unsign token)]
    (api.embed.common/dashboard-for-unsigned-token unsigned-token
                                                   :embedding-params (embed/get-in-unsigned-token-or-throw unsigned-token [:_embedding_params]))))

(api.macros/defendpoint :get "/dashboard/:token/params/:param-key/values"
  "Embedded version of chain filter values endpoint."
  [{:keys [token param-key]}
   query-params]
  (api.embed.common/dashboard-param-values token
                                           param-key
                                           nil
                                           (api.embed.common/parse-query-params query-params)
                                           {:preview true}))

(api.macros/defendpoint :get "/dashboard/:token/params/:param-key/remapping"
  "Embedded version of the remapped dashboard param value endpoint."
  [{:keys [token param-key]}
   {:keys [value]}]
  (api.embed.common/dashboard-param-remapped-value token param-key (codec/url-decode value) {:preview true}))

(api.macros/defendpoint :get "/dashboard/:token/dashcard/:dashcard-id/card/:card-id"
  "Fetch the results of running a Card belonging to a Dashboard you're considering embedding with JWT `token`."
  [{:keys [token dashcard-id card-id]} :- [:map
                                           [:token       ms/NonBlankString]
                                           [:dashcard-id ms/PositiveInt]
                                           [:card-id     ms/PositiveInt]]
   query-params]
  (let [unsigned-token   (check-and-unsign token)
        dashboard-id     (embed/get-in-unsigned-token-or-throw unsigned-token [:resource :dashboard])
        embedding-params (embed/get-in-unsigned-token-or-throw unsigned-token [:_embedding_params])
        token-params     (embed/get-in-unsigned-token-or-throw unsigned-token [:params])]
    (api.embed.common/process-query-for-dashcard
     :export-format    :api
     :dashboard-id     dashboard-id
     :dashcard-id      dashcard-id
     :card-id          card-id
     :embedding-params embedding-params
     :token-params     token-params
     :query-params     (api.embed.common/parse-query-params query-params))))

(api.macros/defendpoint :get "/pivot/card/:token/query"
  "Fetch the query results for a Card you're considering embedding by passing a JWT `token`."
  [{:keys [token]} :- [:map
                       [:token ms/NonBlankString]]
   query-params]
  (let [unsigned-token (check-and-unsign token)
        card-id        (embed/get-in-unsigned-token-or-throw unsigned-token [:resource :question])]
    (api.embed.common/process-query-for-card-with-params
     :export-format    :api
     :card-id          card-id
     :token-params     (embed/get-in-unsigned-token-or-throw unsigned-token [:params])
     :embedding-params (embed/get-in-unsigned-token-or-throw unsigned-token [:_embedding_params])
     :query-params     (api.embed.common/parse-query-params query-params)
     :qp               qp.pivot/run-pivot-query)))

(api.macros/defendpoint :get "/pivot/dashboard/:token/dashcard/:dashcard-id/card/:card-id"
  "Fetch the results of running a Card belonging to a Dashboard you're considering embedding with JWT `token`."
  [{:keys [token dashcard-id card-id]} :- [:map
                                           [:token       ms/NonBlankString]
                                           [:dashcard-id ms/PositiveInt]
                                           [:card-id     ms/PositiveInt]]
   query-params]
  (let [unsigned-token   (check-and-unsign token)
        dashboard-id     (embed/get-in-unsigned-token-or-throw unsigned-token [:resource :dashboard])
        embedding-params (embed/get-in-unsigned-token-or-throw unsigned-token [:_embedding_params])
        token-params     (embed/get-in-unsigned-token-or-throw unsigned-token [:params])]
    (api.embed.common/process-query-for-dashcard
     :export-format    :api
     :dashboard-id     dashboard-id
     :dashcard-id      dashcard-id
     :card-id          card-id
     :embedding-params embedding-params
     :token-params     token-params
     :query-params     (api.embed.common/parse-query-params query-params)
     :qp               qp.pivot/run-pivot-query)))

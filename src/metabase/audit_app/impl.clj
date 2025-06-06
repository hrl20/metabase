(ns metabase.audit-app.impl
  "Namespace for anything related to the Audit subsystem (aka Metabase Analytics) which needs to be accessible in the
  OSS product. EE-only code is located in `metabase-enterprise.audit-app.audit`."
  (:require
   [metabase.audit-app.settings :as audit.settings]
   [metabase.db :as mdb]
   [toucan2.core :as t2]))

;; NOTE: Constants like `audit-db-id` and the entity IDs of audit collections are placed in OSS code because audit
;; content may be loaded on an OSS build in the case of an EE->OSS downgrade. In these situations, we still need
;; access to audit identifiers in order to enforce permission checks properly.

(def audit-db-id
  "ID of Audit DB which is loaded when running an EE build."
  13371337)

(def ^:private default-audit-collection-entity-id
  "Default audit collection entity (instance analytics) id."
  "vG58R8k-QddHWA7_47umn")

(def ^:private default-custom-reports-entity-id
  "Default custom reports entity id."
  "okNLSZKdSxaoG58JSQY54")

(def ^{:arglists '([checksum model entity-id])
       :private  true} memoized-select-audit-entity*
  (mdb/memoize-for-application-db
   (fn [checksum model entity-id]
     (when checksum
       (t2/select-one model :entity_id entity-id)))))

(defn memoized-select-audit-entity
  "Returns the object from entity id and model. Memoizes from entity id.
  Should only be used for audit/pre-loaded objects."
  [model entity-id]
  (memoized-select-audit-entity* (audit.settings/last-analytics-checksum) model entity-id))

(defn default-custom-reports-collection
  "Default custom reports collection."
  []
  (memoized-select-audit-entity :model/Collection default-custom-reports-entity-id))

(defn default-audit-collection
  "Default audit collection (instance analytics) collection."
  []
  (memoized-select-audit-entity :model/Collection default-audit-collection-entity-id))

(defn is-collection-id-audit?
  "Check if an id is one of the audit collection ids."
  [id]
  (contains? (set [(:id (default-audit-collection)) (:id (default-custom-reports-collection))]) id))

(defn is-parent-collection-audit?
  "Check if an instance's parent collection is the audit collection."
  [instance]
  (let [parent-id (:collection_id instance)]
    (and (some? parent-id) (is-collection-id-audit? parent-id))))

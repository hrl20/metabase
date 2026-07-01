(ns metabase.driver.motherduck
  "MotherDuck driver.

  MotherDuck speaks the Postgres wire protocol, so this driver reuses the Postgres JDBC client and
  the Postgres driver's query-execution behavior by parenting on `:postgres`. The one thing that is
  *not* Postgres-compatible is the catalog: the backend is DuckDB, and a single connection can see
  many databases. Sync/metadata multimethods are therefore overridden (see PLAN.md, phase T5) to use
  DuckDB `duckdb_*` metadata functions scoped to `database_name = current_database()`.

  This namespace currently contains registration + the MotherDuck-specific TLS handling needed to
  connect. Metadata overrides land in T5."
  (:require
   [metabase.driver :as driver]
   ;; ensure the parent driver is loaded before we register against it
   metabase.driver.postgres
   [metabase.driver.sql-jdbc.connection :as sql-jdbc.conn]))

(driver/register! :motherduck, :parent :postgres)

(defmethod sql-jdbc.conn/connection-details->spec :motherduck
  [_driver details]
  ;; MotherDuck's Postgres endpoint (pg.<region>-aws.motherduck.com:5432) *requires* an encrypted
  ;; connection; a plaintext attempt just hangs until the client times out. Force SSL on and use
  ;; `sslmode=require`, which encrypts the connection but does NOT attempt to verify the server
  ;; certificate or hostname.
  ;;
  ;; `verify-full` (encrypt + validate the cert chain and hostname against the JVM trust store) was
  ;; tried first but the connection would hang/time out against the MotherDuck endpoint. `require` is
  ;; confirmed working with the Postgres JDBC driver against MotherDuck, so we start there.
  ;;
  ;; Passing `:ssl true` to the Postgres `connection-details->spec` already yields `sslmode=require`
  ;; when no explicit ssl-mode is set; we set it explicitly here to be unambiguous.
  (-> (sql-jdbc.conn/connection-details->spec :postgres (assoc details :ssl true))
      (assoc :sslmode "require")))

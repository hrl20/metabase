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
  ;; connection; a non-SSL attempt just hangs until the client times out. Build the normal Postgres
  ;; JDBC spec with SSL forced on, then set the two SSL params the Postgres *JDBC* driver needs:
  ;;
  ;;   sslmode=verify-full  -> encrypt AND verify the server certificate + hostname
  ;;   sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory
  ;;                        -> validate against the JVM's trust store, which already trusts
  ;;                           Let's Encrypt's ISRG Root X1 (MotherDuck's CA).
  ;;
  ;; NOTE: the libpq `sslrootcert=system` value from the MotherDuck docs is a libpq feature and is
  ;; NOT understood by the Postgres JDBC driver; DefaultJavaSSLFactory is the JDBC equivalent.
  (-> (sql-jdbc.conn/connection-details->spec :postgres (assoc details :ssl true))
      (assoc :sslmode    "verify-full"
             :sslfactory "org.postgresql.ssl.DefaultJavaSSLFactory")))

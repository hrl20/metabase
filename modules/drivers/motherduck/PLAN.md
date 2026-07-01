# MotherDuck Driver — Implementation Plan & Task Checklist

> Goal: a first-party Metabase driver named **`motherduck`** that connects to MotherDuck
> over the **Postgres wire protocol** (reusing the Postgres JDBC client and the Postgres
> driver's query-execution behavior), but replaces every **catalog / sync metadata query**
> with equivalent queries built on **DuckDB `duckdb_*` metadata functions**, scoped to a
> single database via `database_name = current_database()`.
>
> ## Progress (2026-06-30)
- ✅ **T1–T3 done.** Module scaffolded (`deps.edn`, `resources/metabase-plugin.yaml`,
  `src/metabase/driver/motherduck.clj` = minimal `register! :motherduck :parent :postgres`); wired
  into `modules/drivers/deps.edn` and top-level `deps.edn` `:drivers-dev`.
- ✅ **T0 was needed after all.** The duckdb submodule's `metabase.driver.motherduck` (parent
  `:duckdb`) collides on the shared classpath — the first build registered `:motherduck` with parent
  `:duckdb`. Resolved by removing the two duplicate duckdb-submodule files
  (`src/metabase/driver/motherduck.clj`, `test/metabase/test/data/motherduck.clj`; both untracked,
  preserved as references in the scratchpad). `./bin/build-driver.sh motherduck` now registers
  `:motherduck (parents: [:postgres])` and passes manifest validation.
- ▶️ **Next:** boot `clojure -M:run:dev:drivers` to eyeball the driver in the UI (T3 manual check),
  then T4 smoke-check → T5 metadata rewrite → T6–T8 tests.

Two milestones:
> 1. **Minimal connector** — `./bin/build-driver.sh motherduck && clojure -M:run:dev:drivers`
>    shows the MotherDuck driver in local Metabase and can connect.
> 2. **Green integration test** — `DRIVERS=motherduck clojure -X:dev:drivers:drivers-dev:test`
>    passes, loading test data into MotherDuck via the DuckDB JDBC client (test-only dep).

---

## 1. Architecture decisions (settled during research)

| Decision | Choice | Rationale |
|---|---|---|
| Parent driver | **`:parent :postgres`** | User wants "largely the same as postgres": inherit `connection-details->spec` (postgresql subprotocol + SSL), all `sql.qp/*` query generation, `read-column-thunk`, `unprepare`, etc. Only override sync/metadata + type mapping + feature flags. |
| JDBC driver | **`org.postgresql.Driver`** (already on core classpath) | Core Metabase already depends on org.postgresql (postgres is a core driver in `src/`, not a module). The motherduck **module needs no prod `:deps`**. |
| Connection props | host / port / dbname / user / password / ssl (same as postgres) | Inherited from postgres connection spec; declared in plugin yaml. `dbname` selects the single MotherDuck database that becomes the Metabase "Database". |
| Metadata scoping | every rewritten query filters `WHERE database_name = current_database()` | A single MotherDuck (pg) connection sees many databases (`duckdb_databases()`). `current_database()` returns the connection's db; scoping to it cleanly excludes the `system` and `temp` databases and all built-in/internal objects. **Validated** (see §3). |
| Test data loading | load via **DuckDB JDBC client** (`org.duckdb/duckdb_jdbc`, `md:` connection, workspace mode) as a **test-only** dependency; the driver-under-test connects via the pg endpoint | The pg endpoint may be unreliable/limited for bulk writes; DuckDB JDBC is the proven path (existing `metabase.test.data.motherduck`). Keeps prod jar free of duckdb_jdbc. |

### Relationship to the duckdb submodule (reference only)
The **duckdb community driver** (`modules/drivers/duckdb/`, a separate submodule) also registers a
test-only `:motherduck` (`:parent :duckdb`) and ships
`modules/drivers/duckdb/test/metabase/test/data/motherduck.clj` — the "got it working most of the
way" data-loader (DuckDB JDBC, workspace mode, `duckdb_databases()` cleanup). **Treat that submodule
as a read-only reference to copy patterns from — do not build the plan around dismantling it.** It is
not shipped as a plugin (no `driver: name: motherduck` in any yaml).

The only practical concern: if *both* the duckdb submodule and our new module are on the same dev/test
classpath, `:motherduck` gets double-registered. That's a **local dev-env** matter, not a project
phase — if it bites, disable the submodule's one-line `driver/register! :motherduck` locally. Our new
module owns the `:motherduck` keyword for the shipped driver.

---

## 2. Reference: Postgres metadata methods to override

Parenting on `:postgres` means we inherit these; each must be **overridden** for `:motherduck`
(or disabled via a feature flag). Source: `src/metabase/driver/postgres.clj`.

| Postgres method | Line | Motherduck action |
|---|---|---|
| `driver/describe-database` / `describe-database*` | ~304 | **Override** with `duckdb_tables`/`duckdb_views` query (§4.1). Postgres version also does per-table privilege probes — drop that. |
| `sql-jdbc.sync/describe-fields-sql` | ~310 | **Override** (§4.2). |
| `sql-jdbc.sync/describe-fields-pre-process-xf` | ~450 | **Override** back to the `:sql-jdbc` default (removes Postgres enum tagging). |
| `sql-jdbc.sync/describe-fks-sql` | ~397 | **Override** (§4.3), gated on `:metadata/key-constraints`. |
| `sql-jdbc.sync/describe-indexes-sql` | ~423 | **Disable** feature `:describe-indexes` (don't override the SQL). |
| `sql-jdbc.sync/current-user-table-privileges` | ~1219 | **Disable** feature `:table-privileges` (or override — not needed for tests). |
| `sql-jdbc.sync/database-type->base-type` | ~887 | **Override** with DuckDB type map (§5). |
| `driver/dynamic-database-types-lookup` | ~876 | **Override** → return `nil` (no Postgres enums). |
| `sql-jdbc.sync/column->semantic-type` | ~891 | Optional override; DuckDB `JSON` → `:type/SerializedJSON`. |
| `sql-jdbc.sync/excluded-schemas` | ~963 | **Override** → `#{"information_schema" "pg_catalog"}` (they live in the `system` db anyway, already filtered by `current_database()`, so this is belt-and-suspenders). |
| `driver/db-default-timezone` | ~148 | Inherits Postgres `show timezone;` — likely works over pg endpoint; **validate**, override only if broken. |

**Feature flags** (`defmethod driver/database-supports? [:motherduck …]`): start conservative.
- `true`: `:describe-fields`, `:schemas`, `:set-timezone` (validate), `:basic-aggregations`, plus whatever the standard sql-jdbc test suite requires.
- `false` initially: `:describe-indexes`, `:table-privileges`, `:actions`, `:actions/custom`, `:actions/data-editing`, `:uploads`, `:persist-models`, `:database-replication`, `:database-routing`, `:connection-impersonation`, `:transforms/*`, `:nested-field-columns`, `:metadata/key-constraints` (see FK decision in §6).
- Query-execution features (`:convert-timezone`, `:datetime-diff`, `:now`, `:expressions/*`) — inherit from postgres; flip off individually only if a query fails on DuckDB (then port the DuckDB impl from `modules/drivers/duckdb/src/metabase/driver/duckdb.clj`).

### `describe-*-sql` return contract (verified in `src/metabase/driver/sql_jdbc/sync/describe_table.clj`)
- `describe-fields-sql` must return `[sql & params]`. The result rows are consumed by `describe-fields-xf` (line 207), which reads keys: `:name`, `:database-type`, `:database-position`, `:table-schema`, `:table-name`, `:pk?`, and optionally `:field-comment`, `:database-default`, `:database-required`, `:database-is-auto-increment`, `:database-is-generated`, `:database-is-nullable`. **Mirror the exact column aliases Postgres emits** (Postgres works today) so identifier→keyword conversion matches. Minimum viable columns: `name`, `database_type`, `database_position`, `table_schema`, `table_name`, `pk?`.
- `describe-fks-sql` must return `[sql & params]` producing columns: `fk_table_schema`, `fk_table_name`, `fk_column_name`, `pk_table_schema`, `pk_table_name`, `pk_column_name` (consumed by `describe-fks`, line 434).

---

## 3. Validated facts about DuckDB metadata functions (probed on DuckDB v1.5.3)

- `SELECT current_database()` returns the connected database name.
- Scoping `WHERE database_name = current_database()` on `duckdb_columns()` returned **only** the user db's rows (11) and excluded the `system` db (527 rows incl. `pg_catalog`, `information_schema`, `sqlite_master`, and the `duckdb_*` table-functions themselves). This is the key filter.
- `duckdb_tables()` and `duckdb_views()` return **only real user objects** (the `duckdb_*` functions do *not* appear in them). Both have `internal` and `temporary` boolean columns.
- `duckdb_columns()` **does** include rows for internal/system objects → also filter `internal = false`.
- User column rows have `internal = false`, correct `is_nullable`, and `column_index` is **1-based** (subtract 1 for `database_position`).
- **Caveat:** the `main` schema is reported `internal = true` even in a user database — so **do not** filter *schemas* by `internal`; filter tables/columns by `database_name` (+ `internal=false` on columns).
- `data_type` spellings observed: `INTEGER`, `VARCHAR`, `DECIMAL(10,2)`, `DOUBLE`, `TIMESTAMP`, `TIMESTAMP WITH TIME ZONE`, `INTEGER[]`, `JSON`, `UUID`, `BLOB`, `STRUCT(a INTEGER, b VARCHAR)`. Note precision/scale is embedded (`DECIMAL(10,2)`), arrays carry `[]`, and `TIMESTAMPTZ` renders as `TIMESTAMP WITH TIME ZONE` → the type map must normalize the leading base word / strip parameters.
- FK info lives in `duckdb_constraints()` where `constraint_type='FOREIGN KEY'`: columns `constraint_column_names VARCHAR[]`, `referenced_table VARCHAR`, `referenced_column_names VARCHAR[]`. **No referenced *schema* column** → assume same schema as the FK table. Use `UNNEST(...)` to expand multi-column lists to one row per column.
- PK info: `duckdb_constraints()` where `constraint_type='PRIMARY KEY'` → `constraint_column_names VARCHAR[]`.
- **DuckDB does not support `ALTER TABLE ... ADD CONSTRAINT FOREIGN KEY`** (probe errored); FKs must be declared inline at `CREATE TABLE`. Relevant to the FK-loading decision (§6).

---

## 4. Validated rewrite SQL (ran green on DuckDB v1.5.3)

### 4.1 describe-database (tables + views)
```sql
SELECT schema_name AS "schema", table_name AS "name"
FROM duckdb_tables()
WHERE database_name = current_database()
UNION ALL
SELECT schema_name, view_name
FROM duckdb_views()
WHERE database_name = current_database() AND internal = false;
```
Build the `{:tables #{ {:schema .. :name .. :description ..} ...}}` set from this. (Optionally add `comment AS description` and, for tables, `estimated_size AS estimated_row_count`.)

### 4.2 describe-fields (columns + pk flag)
```sql
SELECT col.schema_name                                   AS "table_schema",
       col.table_name                                    AS "table_name",
       col.column_name                                   AS "name",
       col.data_type                                     AS "database_type",
       (col.column_index - 1)                            AS "database_position",
       (pk.pk_cols IS NOT NULL
        AND list_contains(pk.pk_cols, col.column_name))  AS "pk?",
       col.comment                                       AS "field_comment",
       (NOT col.is_nullable)                             AS "database_required",
       col.is_nullable                                   AS "database_is_nullable"
FROM duckdb_columns() col
LEFT JOIN (
  SELECT schema_name, table_name, constraint_column_names AS pk_cols
  FROM duckdb_constraints()
  WHERE constraint_type = 'PRIMARY KEY' AND database_name = current_database()
) pk
  ON pk.schema_name = col.schema_name AND pk.table_name = col.table_name
WHERE col.database_name = current_database()
  AND col.internal = false
  -- optional, when args supply them:
  -- AND col.schema_name IN (:schema-names)
  -- AND col.table_name  IN (:table-names)
ORDER BY col.schema_name, col.table_name, col.column_index;
```
Confirm the final `:pk?`/`:database-type` keyword mapping matches `describe-fields-xf` by mirroring Postgres's aliases exactly.

### 4.3 describe-fks
```sql
SELECT schema_name                       AS "fk_table_schema",
       table_name                        AS "fk_table_name",
       UNNEST(constraint_column_names)   AS "fk_column_name",
       schema_name                       AS "pk_table_schema",   -- assume same schema
       referenced_table                  AS "pk_table_name",
       UNNEST(referenced_column_names)   AS "pk_column_name"
FROM duckdb_constraints()
WHERE constraint_type = 'FOREIGN KEY'
  AND database_name = current_database();
```

---

## 5. DuckDB type → Metabase base-type map (port from duckdb driver)

Port `sql-jdbc.sync/database-type->base-type` from
`modules/drivers/duckdb/src/metabase/driver/duckdb.clj` (~lines 217–270). Key mappings:
```
BOOLEAN/BOOL/LOGICAL                              -> :type/Boolean
BIGINT/INT8/LONG/HUGEINT/UBIGINT                  -> :type/BigInteger
INTEGER/INT/INT4/SIGNED/SMALLINT/TINYINT/U*       -> :type/Integer
DECIMAL                                           -> :type/Decimal
DOUBLE/FLOAT/FLOAT4/FLOAT8/REAL/NUMERIC           -> :type/Float
VARCHAR/CHAR/BPCHAR/TEXT/STRING                   -> :type/Text
JSON                                              -> :type/JSON
BLOB/BYTEA/BINARY/VARBINARY                       -> :type/*
UUID                                              -> :type/UUID
TIMESTAMPTZ / "TIMESTAMP WITH TIME ZONE"          -> :type/DateTimeWithTZ (or ...WithLocalTZ)
TIMESTAMP/DATETIME/TIMESTAMP_S/_MS/_NS            -> :type/DateTime
DATE                                              -> :type/Date
TIME                                              -> :type/Time
```
**Must** normalize the incoming string first: strip `(...)` (e.g. `DECIMAL(10,2)`) and any `[]` suffix, uppercase, before lookup. Default unmatched → `:type/*`.

---

## 6. FK loading decision (integration test)

DuckDB can *read* FKs (§4.3) but the standard test-data loader in the existing
`metabase.test.data.motherduck` sets `sql.tx/add-fk-sql → nil`, and DuckDB can't `ALTER ADD FK`.
So test datasets are created **without** FK constraints → `describe-fks` finds nothing.

**Recommended default:** set `:metadata/key-constraints false` for the first green run
(mirrors the duckdb driver). Metabase then won't sync/expect FKs and FK-dependent tests are
skipped. Still **ship `describe-fks-sql`** (§4.3) — it is correct whenever FKs exist.

**Stretch task (optional):** enable FK loading by emitting inline `REFERENCES` in
`create-table-sql` with topologically-ordered table creation, then flip
`:metadata/key-constraints true`. Track separately; do not block milestone 2 on it.

---

## 7. Task checklist

Tasks are ordered; T1–T4 = milestone 1 (minimal connector), T5–T8 = milestone 2 (tests).
Each is written to be executed by an agent with only this document as context.

---

### T0 — (Only if needed) avoid `:motherduck` double-registration in local dev
**Spec:** The duckdb submodule is a **reference**, not a target. Do **not** delete or restructure it.
Only if both modules end up on your local dev/test classpath and Metabase errors on duplicate
`:motherduck` registration, disable the submodule's single line
`modules/drivers/duckdb/src/metabase/driver/motherduck.clj:6` locally. Otherwise skip this task.
**Acceptance:** `clojure -M:run:dev:drivers` boots without a duplicate-driver error for `:motherduck`.

---

### T1 — Create the module skeleton & wiring
**Files (new):**
- `modules/drivers/motherduck/deps.edn` → `{:paths ["src" "resources"]}` (no prod `:deps`; org.postgresql is on the core classpath).
- `modules/drivers/motherduck/resources/metabase-plugin.yaml` (T2).
- `modules/drivers/motherduck/src/metabase/driver/motherduck.clj` (T3).
**Files (edit):**
- `modules/drivers/deps.edn` → add `metabase/motherduck {:local/root "motherduck"}` (mirror the `duckdb` entry just added).
- `deps.edn` (top-level) `:drivers-dev` `:extra-paths` → add `"modules/drivers/motherduck/test"` (mirror the duckdb line).
**Acceptance:**
- `clojure -Sdeps` / `clojure -M:run:dev:drivers -e '(println :ok)'`-style classpath resolution succeeds (no deps errors).
- Directory tree matches the duckdb module's layout.
**Context:** `modules/drivers/deps.edn` and top-level `deps.edn` were both just modified to add duckdb (see `git diff`); copy those exact patterns.

---

### T2 — Plugin manifest (`metabase-plugin.yaml`)
**File:** `modules/drivers/motherduck/resources/metabase-plugin.yaml`
**Spec:** Declare the driver with `name: motherduck`, `display-name: MotherDuck`,
`lazy-load: true`, `parent: sql-jdbc` **or** `parent: postgres` (use `postgres` to match the
runtime parent; confirm the plugin loader allows a core driver as yaml parent — postgres-derived
modules like redshift use `parent: postgres`). Connection properties: host, port (placeholder for
MotherDuck pg endpoint), dbname, user, password, ssl (default the endpoint's requirement — likely
`true`), ssh-tunnel, advanced-options. Model closely on
`modules/drivers/redshift/resources/metabase-plugin.yaml`.
`init:` steps:
```yaml
init:
  - step: load-namespace
    namespace: metabase.driver.postgres
  - step: load-namespace
    namespace: metabase.driver.motherduck
  - step: register-jdbc-driver
    class: org.postgresql.Driver
```
**Acceptance:** yaml parses; after T3 build, the driver appears in the local Metabase "Add a
database" list as **MotherDuck** with host/port/db/user/password/ssl fields.
**Context:** connection-property building blocks (`host`, `port`, `dbname`, `user`, `password`,
`ssl`, `ssh-tunnel`, `additional-options`, `advanced-options-start`, `default-advanced-options`)
are defined in `src/metabase/driver/common.clj`.

---

### T3 — Minimal driver namespace (no metadata rewrite yet)
**File:** `modules/drivers/motherduck/src/metabase/driver/motherduck.clj`
**Spec:** Minimal, buildable driver:
```clojure
(ns metabase.driver.motherduck
  (:require [metabase.driver :as driver]
            [metabase.driver.postgres]))   ; ensure parent is loaded
(driver/register! :motherduck :parent :postgres)
```
No metadata overrides yet (milestone 1 just needs it to build, load, and connect). If connecting
requires forcing SSL or a default port, add a thin `connection-details->spec` override, otherwise
inherit postgres's.
**Acceptance:**
- `./bin/build-driver.sh motherduck` produces `resources/modules/motherduck.metabase-driver.jar`.
- `clojure -M:run:dev:drivers` boots; MotherDuck shows in the driver list.
- (Manual/likely) A connection to a real MotherDuck pg endpoint with valid creds saves & the
  "test connection" succeeds. **This is the first validation of the core assumption that MotherDuck
  speaks the pg wire protocol and that org.postgresql.Driver can reach it.**

---

### T4 — Smoke-check the live MotherDuck pg endpoint (quick, not a gate)
**Status:** the core assumption — MotherDuck exposes a Postgres wire endpoint reachable by
`org.postgresql.Driver`, and `duckdb_*` functions run over it — is **confirmed by the user.** This
is now a quick sanity check, not a go/no-go spike.
**Spec:** With real credentials, connect via the pg endpoint and record the working **host / port /
ssl** settings (feeds T2 defaults), then confirm `SELECT current_database();` matches the connection
`dbname` and that the §4 rewrite queries run and return sane rows.
**Acceptance:** a short note with the working host/port/ssl and a confirmed run of the §4 queries.
**Fallback (unlikely, keep in back pocket):** if any `duckdb_*` function ever misbehaves over the
endpoint, DuckDB `information_schema` scoped by `table_catalog = current_database()` is a drop-in
substitute — see the duckdb driver's `describe-database`/`describe-table`.

---

### T5 — Metadata rewrite (the core of the task)
**File:** `modules/drivers/motherduck/src/metabase/driver/motherduck.clj`
**Spec:** Implement all overrides from §2 using the validated SQL in §4 and the type map in §5:
1. `driver/describe-database` (or `describe-database*` — match whichever postgres overrides) → §4.1.
2. `sql-jdbc.sync/describe-fields-sql :motherduck` → §4.2. Honor optional `:schema-names` /
   `:table-names` args (add `IN (…)` filters when present).
3. `sql-jdbc.sync/describe-fields-pre-process-xf :motherduck` → delegate to the `:sql-jdbc` default
   (drop Postgres enum tagging).
4. `sql-jdbc.sync/describe-fks-sql :motherduck` → §4.3.
5. `sql-jdbc.sync/database-type->base-type :motherduck` → §5 (with string normalization).
6. `driver/dynamic-database-types-lookup :motherduck` → `nil`.
7. `sql-jdbc.sync/excluded-schemas :motherduck` → `#{"information_schema" "pg_catalog"}`.
8. `driver/database-supports?` feature flags per §2 (enable `:describe-fields`; disable
   `:describe-indexes`, `:table-privileges`, `:actions*`, `:uploads`, `:persist-models`,
   `:metadata/key-constraints` (see §6), etc.).
**Acceptance:**
- Against a MotherDuck db with a couple of tables/views, Metabase **sync** populates tables, fields
  with correct base-types, and PK flags — driven by the rewritten SQL (verify via logs:
  `describe-fields sql query:` should show the duckdb_* query, not pg_catalog).
- No pg_catalog/`information_schema.columns` query is issued for sync (grep debug logs).
- Sync does not error on multi-database MotherDuck accounts (only `current_database()` objects appear).

---

### T6 — Test-data extensions (`metabase.test.data.motherduck`) for the pg-based driver
**File:** `modules/drivers/motherduck/test/metabase/test/data/motherduck.clj`
(port from the T0-preserved copy of the duckdb module's version).
**Spec:** Keep loading data via the **DuckDB JDBC client** (`md:` connection, workspace mode) but
decouple it from the driver's own connection spec, because the driver now connects via Postgres:
1. **`tx/dbdef->connection-details :motherduck`** → return **Postgres** details for the
   driver-under-test: `{:host … :port … :dbname database-name :user … :password … :ssl …}` sourced
   from env vars (e.g. `MB_MOTHERDUCK_TEST_HOST/PORT/USER/PASSWORD`, `dbname` = the test db name).
2. **`dbdef->spec :motherduck`** (in `metabase.test.data.sql-jdbc.spec`) → return a **DuckDB JDBC**
   spec (`{:classname "org.duckdb.DuckDBDriver" :subprotocol "duckdb" :subname (str "md:" database-name)}`
   + `custom_user_agent`, token via `MOTHERDUCK_TOKEN`). This is what the parent `load-data/create-db!`
   uses to run CREATE TABLE / INSERT — so **all writes go through DuckDB JDBC, not the pg endpoint.**
   Verify in `metabase.test.data.sql-jdbc.load-data` that loading uses `dbdef->spec` (not the driver's
   `connection-details->spec`); if it uses the latter, instead override `tx/create-db!` to run the
   full load over a DuckDB workspace connection.
3. Keep `create-db!`, `before-run`, `after-run`, `dataset-already-loaded?`, `delete-old-databases!`
   from the existing file, but build their connections from a **dedicated DuckDB workspace spec**
   helper (not `connection-details->spec :motherduck`, which is now postgres).
4. Keep `field-base-type->sql-type` (DuckDB dialect: STRING/TIMESTAMPTZ/…), `pk-sql-type "INTEGER"`,
   `add-fk-sql → nil` (per §6), `create-db-sql`/`drop-db-ddl-statements` (CREATE/DROP DATABASE),
   `row-xform`, `sorts-nil-first? false`.
5. Feature flags for tests: `:test/cannot-destroy-db true`, `:test/time-type false`,
   `:upload-with-auto-pk (not config/is-test?)`, `describe-materialized-view-fields false`.
**Acceptance:**
- The DuckDB JDBC coordinate is **test-only** (not in `modules/drivers/motherduck/deps.edn`); it is
  available at test time via the duckdb module (already on the `:drivers` classpath) or an explicit
  test alias — pick one and document it (T7).
- `metabase.test.data.motherduck` loads without referencing the driver's pg spec for writes.

---

### T7 — Test dependency & env wiring
**Files:** top-level `deps.edn` (and/or `modules/drivers/motherduck/deps.edn` `:aliases`).
**Spec:** Ensure `org.duckdb/duckdb_jdbc` is on the classpath **only for tests**. Simplest: rely on
the duckdb module already present under `:drivers` (it declares `org.duckdb/duckdb_jdbc 1.5.3.0`).
If that coupling is undesirable, add a `:test` alias in the motherduck module's deps.edn with
`:extra-deps {org.duckdb/duckdb_jdbc {:mvn/version "1.5.3.0"}}` and include it in the test invocation.
Document required env vars for the run: `MB_MOTHERDUCK_TEST_HOST/PORT/USER/PASSWORD`, `MOTHERDUCK_TOKEN`.
**Acceptance:** `./bin/build-driver.sh motherduck` (prod jar) does **not** bundle duckdb_jdbc
(inspect the jar); the test classpath **does** have `org.duckdb.DuckDBDriver`.

---

### T8 — Green integration test
**Command:** `DRIVERS=motherduck clojure -X:dev:drivers:drivers-dev:test`
(with T7 env vars set).
**Spec:** Iterate until the standard driver test suite passes. Expected failure buckets & fixes:
- **Query-execution divergence** (Postgres SQL that DuckDB rejects: date functions, casts, regex,
  `median`, interval math, etc.) → override the specific `sql.qp/*` / `read-column-thunk`
  multimethods for `:motherduck`, porting the DuckDB implementations from
  `modules/drivers/duckdb/src/metabase/driver/duckdb.clj` (it already has correct `date`,
  `datetime-diff`, `add-interval-honeysql-form`, `unix-timestamp->honeysql`, regex).
- **Feature-gated tests** for unsupported features → confirm the feature flag is `false` so the
  test is skipped.
- **Metadata mismatches** → adjust §4 SQL / §5 type map.
**Acceptance:** the suite passes for `DRIVERS=motherduck`. Record any intentionally-disabled
features and any skipped tests with justification.

---

## 8. Open questions / risks
1. ✅ **CONFIRMED (user):** MotherDuck exposes a Postgres wire endpoint reachable by
   `org.postgresql.Driver`, and `duckdb_*` functions run over it. (Still record exact host/port/ssl/auth
   in T4.)
2. **Does the pg endpoint allow the writes the test loader would otherwise do?** Mitigated by
   loading via DuckDB JDBC (T6), so the driver's pg path is read-only in tests.
3. **Postgres query generation compatibility** with DuckDB SQL — surfaced by T8; fix per-method by
   porting the DuckDB `sql.qp/*` impls.
4. **`db-default-timezone`** (`show timezone;`) over the endpoint — validate; override if needed.

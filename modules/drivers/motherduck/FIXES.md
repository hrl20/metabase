# MotherDuck integration test fixes

Tracking file for the fix-all-failures session (2026-07-05). Baseline from `junit-failures.jsonl`:
35 failures + 232 errors across ~22 namespaces.

Format: one section per namespace; each fixed test listed with brief notes on the fix.

## Status

| namespace | baseline fail/err | status |
|---|---|---|
| metabase.query-processor.date-time-zone-functions-test | 0/113 | ✅ all pass |
| metabase.query-processor.date-bucketing-test | 0/60 | pending |
| metabase.channel.render.body-test (+card-test, pulse-integration-test) | 12/6 | pending |
| metabase.permissions.models.collection.graph-test | 16/0 | pending |
| metabase.query-processor.expressions-test | 0/13 | pending |
| metabase.query-processor.cast-test | 0/8 | pending |
| metabase.query-processor.filter-test | 0/8 | pending |
| metabase.query-processor.alternative-date-test | 0/7 | pending |
| metabase.query-processor.explicit-joins-test (+implicit-joins-test) | 0/7+ | pending |
| metabase.driver-test | 3/2 | pending |
| hooks.clojure.test-test | 2/0 | pending |
| metabase.driver.sql-jdbc.sync.describe-table-test | 1/1 | pending |
| metabase.driver.sql-jdbc-test | 0/2 | pending |
| metabase.core.modules-test | 0/1 | pending |
| metabase.driver.sql-jdbc.connection-test | 1/0 | pending |
| metabase.indexed-entities.models.model-index-test | 0/1 | pending |
| metabase.query-processor.case-test | 0/1 | pending |
| metabase.query-processor.coercion-test | 0/1 | pending |
| metabase.query-processor.cumulative-aggregation-test | 0/1 | pending |

## Fixes

(appended as agents complete)

### metabase.query-processor.date-time-zone-functions-test — ✅ 41 tests, 0 fail/err

Root causes & fixes (all in `modules/drivers/motherduck/src/metabase/driver/motherduck.clj`, no core changes):

1. **`No matching clause: :motherduck` from `metabase.util.honey-sql-2/current-datetime-honeysql-form`** — the h2x helper `case`s on the bare driver keyword (no hierarchy), so the inherited `:postgres` impl blows up. Fix: `sql.qp/current-datetime-honeysql-form :motherduck` delegating to the helper with `:postgres`.
   - Fixed: `now-test`, `now-test-2..5`, `now-with-extract-test`
2. **h2x `add-interval-honeysql-form` dispatches on bare keyword → `:default` throw** — same pattern as (1). Fix: `sql.qp/add-interval-honeysql-form :motherduck` delegating with `:postgres` (DuckDB accepts the same `expr + INTERVAL 'n unit'` SQL).
   - Fixed: `datetime-math-tests`, `datetime-math-with-extract-test`, `datetime-math-string-to-date-test`, `datetime-diff-expressions-test`, `temporal-arithmetic-with-literal-date-test`, `temporal-extraction-with-datetime-arithmetic-expression-tests`, `extraction-function-timestamp-with-time-zone-test`, `nested-convert-timezone-test-2`, `nested-convert-timezone-test-6`
3. **pg-gateway "ambiguous result column types" on `TIMEZONE(?, TIMEZONE(?, expr))`** — Postgres compiles tz names / literal datetimes as untyped `?` params. Fix: `->honeysql [:motherduck :convert-timezone]`, a copy of the Postgres impl with tz names inlined via `h2x/literal` and the inner expr wrapped in `h2x/->pg-timestamp` (`CAST(? AS TIMESTAMP)` for literal-datetime args, no-op for typed columns).
   - Fixed: `convert-timezone-test`, `convert-timezone-test-1c`, `convert-timezone-test-3`, `nested-convert-timezone-test`, `nested-convert-timezone-test-3..5b`

**Pattern for other agents:** any Postgres method that delegates to an h2x helper (they dispatch on the *bare* db-type keyword) needs an explicit `:motherduck` defmethod passing `:postgres`. Gateway ambiguous-param errors: inline string literals (`h2x/literal`), cast temporal params (`h2x/->pg-timestamp`).

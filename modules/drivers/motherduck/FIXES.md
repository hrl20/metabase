# MotherDuck integration test fixes

Tracking file for the fix-all-failures session (2026-07-05). Baseline from `junit-failures.jsonl`:
35 failures + 232 errors across ~22 namespaces.

Format: one section per namespace; each fixed test listed with brief notes on the fix.

## Status

| namespace | baseline fail/err | status |
|---|---|---|
| metabase.query-processor.date-time-zone-functions-test | 0/113 | ✅ all pass |
| metabase.query-processor.date-bucketing-test | 0/60 | ✅ all pass (no new fix needed — cured by others' prior commits) |
| metabase.channel.render.body-test (+card-test, pulse-integration-test) | 12/6 | pending |
| metabase.permissions.models.collection.graph-test | 16/0 | ✅ all pass (see notes — no code fix needed) |
| metabase.query-processor.expressions-test | 0/13 | ✅ all pass |
| metabase.query-processor.cast-test | 0/8 | pending |
| metabase.query-processor.filter-test | 0/8 | pending |
| metabase.query-processor.alternative-date-test | 0/7 | pending |
| metabase.query-processor.explicit-joins-test (+implicit-joins-test) | 0/7+ | pending |
| metabase.driver-test | 3/2 | pending |
| hooks.clojure.test-test | 2/0 | ✅ all pass |
| metabase.driver.sql-jdbc.sync.describe-table-test | 1/1 | pending |
| metabase.driver.sql-jdbc-test | 0/2 | pending |
| metabase.core.modules-test | 0/1 | ⚠️ partial — 2 assertions still fail, blocked on untracked `src/metabase/db/connection-pool` (see notes) |
| metabase.driver.sql-jdbc.connection-test | 1/0 | pending |
| metabase.indexed-entities.models.model-index-test | 0/1 | pending |
| metabase.query-processor.case-test | 0/1 | ✅ all pass |
| metabase.query-processor.coercion-test | 0/1 | ✅ all pass |
| metabase.query-processor.cumulative-aggregation-test | 0/1 | ✅ all pass |

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

### metabase.query-processor.expressions-test — ✅ 120 tests, 0 fail/err (+ case-test, coercion-test, cumulative-aggregation-test, all ✅)

The temporal-arithmetic tests in this cluster were already cured by the prior `add-interval-honeysql-form`/`current-datetime-honeysql-form` fix (see above); re-running after that commit left 11 errors, all new root causes:

1. **Gateway "ambiguous result column types" on *any* bare string parameter, not just top-level literal expressions** — the existing `::sql.qp/expression-literal-text-value` fix only covered `[:value "foo" ...]` clauses used directly as a `:fields`/`:expressions` value. It missed plain strings reaching `->honeysql` from other contexts: `:case`/`:if` branch values (`if-test`, `weekday-numbers-and-names-test`/day-name-desugared-to-case), and non-column args to string functions like `CONCAT(col, ?)` (`expression-with-duplicate-column-name`, `case-with-literal-expression-test`, `nested-and-filtered-literal-expression-test`, `literal-expressions-inside-nested-and-filtered-aggregations-test`).
   - **Fix**: replaced the narrow `::sql.qp/expression-literal-text-value` defmethod in `motherduck.clj` with a blanket `sql.qp/->honeysql [:motherduck String]` — every raw string literal that flows through `sql.qp/->honeysql` gets `CAST(? AS text)`, regardless of which clause produced it. This subsumes the old fix (that clause compiles to a plain string too) with less code than adding per-clause overrides.
   - Fixed: `if-test` (case-test), `weekday-numbers-and-names-test`, `case-with-literal-expression-test`, `expression-with-duplicate-column-name`, `nested-and-filtered-literal-expression-test`, `literal-expressions-inside-nested-and-filtered-aggregations-test`, `string-operations-from-subquery` (partially — see #3)
2. **Test-data loader: "A result was returned when none was expected" on multi-row `INSERT`** — `load-data/do-insert!` (in `test/metabase/test/data/motherduck.clj`) called `jdbc/execute!`, whose default path calls pgjdbc's `executeUpdate`/`executeBatch`. Those reject any returned result set — and the gateway returns one for every statement, INSERT included (see AGENTS.md). The driver already special-cases this for the *non-parameterized* `execute-sql!` path (raw `Statement.execute`), but `do-insert!`'s parameterized path still went through `jdbc/execute!`.
   - **Fix**: rewrote `do-insert!` to build a `PreparedStatement` directly (`.prepareStatement` + `sql-jdbc.execute/set-parameters!` + `.execute`), bypassing `clojure.java.jdbc` entirely for this call so no code path invokes `executeUpdate`. Dropped the now-unused `clojure.java.jdbc` require.
   - Fixed (were blocked at data-load time, not query time): `float-to-integer-coercion-test` (coercion-test), `offset-function-expression-breakout-test` (cumulative-aggregation-test), `coercion-with-expression-test`, `coercion-with-expression-test-2`
3. **DuckDB has no Postgres `substring(str FROM pattern)` two-arg regex overload** — Postgres's `:regex-match-first` compiles to `substring(expr FROM ?)`, a POSIX-regex-specific overload that only Postgres has; DuckDB's `substring` only takes positional `(str, start[, len])` args, so it tries to coerce the pattern string to an integer position and DuckDB itself throws (`Conversion Error: Could not convert string '...' to INT64` — not a gateway ambiguous-param error, a real DuckDB runtime error).
   - **Fix**: `sql.qp/->honeysql [:motherduck :regex-match-first]` emits `regexp_extract(expr, pattern)` instead — DuckDB's native regex function, default group 0 (whole match) matching `:regex-match-first` semantics. Copied verbatim from the sibling DuckDB community driver (`modules/drivers/duckdb/src/metabase/driver/duckdb.clj`), which already solved the identical problem — same backend, so no exploration needed.
   - Fixed: `string-operations-from-subquery`

**Pattern for other agents:** if you see "ambiguous result column types" from a query with a bare string constant *anywhere* in the query (not just as a returned field), check whether the blanket `[:motherduck String]` `->honeysql` override (added here) already covers it before writing a narrower fix — it should catch any string literal, in any clause position. For DuckDB-function-signature mismatches with Postgres (not gateway/param issues, but genuine SQL semantic differences), check `modules/drivers/duckdb/src/metabase/driver/duckdb.clj` first — same backend, likely already solved.

### metabase.query-processor.date-bucketing-test — ✅ 60 tests, 0 fail/err (no new code — verification only)

Assigned baseline was 60 errors, all originating from test-data load failures (`sad-toucan-incidents`, dynamically-generated `checkins:N-per-*` datasets), not query-processor logic: every test in this namespace loads a fresh dataset, and the loader's `INSERT` blew up with `"A result was returned when none was expected"` before any query ran.

Root cause and fix were *already* diagnosed and applied by another concurrent agent, landed in commit `925643a41e` (also documented under `expressions-test` fix #2 above, same file): `load-data/do-insert!` in `test/metabase/test/data/motherduck.clj` used to call `jdbc/execute!`, which for a param-less/param-only single-row-worth-of-values `INSERT` statement resolves to pgjdbc's `PreparedStatement.executeUpdate`/`executeBatch` — both reject the result-set-for-every-statement the gateway always returns. The fix (already in place) builds the `PreparedStatement` directly and calls raw `.execute()` instead, bypassing `clojure.java.jdbc`'s update/batch paths entirely.

By the time this agent's second full run was kicked off, that commit was already in the tree; re-running `metabase.query-processor.date-bucketing-test` confirmed **60/60 tests pass, 170 assertions, 0 failures, 0 errors** — no additional driver or test-data code needed for this namespace.

**Timezone note for other agents:** the pg gateway's `SET SESSION TIMEZONE TO '<name>'` / `SET TimeZone='<name>'` only accepts canonical IANA zone names (e.g. `America/Los_Angeles`, `Asia/Hong_Kong`) — legacy three-letter-region aliases like `US/Pacific`, `US/Eastern` are rejected with `invalid value for parameter "TimeZone"` even though they appear in `pg_timezone_names()`. This namespace's Pacific/Eastern tests already use canonical names (`America/Los_Angeles`, `America/Indiana/Indianapolis`, etc. via `mt/with-report-timezone-id`), so it wasn't hit here, but any other namespace that binds a `US/*`-style timezone from a JVM `TimeZone` short name will need to normalize to the canonical IANA name before it reaches `SET SESSION TIMEZONE`.

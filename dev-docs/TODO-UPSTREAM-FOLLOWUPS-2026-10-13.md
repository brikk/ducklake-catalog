# Upstream follow-ups

Remaining native verification and candidate investigations only. For other review
work use [BACKLOG.md](BACKLOG.md); consumer support lives in
[compatibility](../docs/COMPATIBILITY.md). Completed investigations and dated
shipping checkpoints are in [the history](archive/UPSTREAM-FOLLOWUPS-HISTORY-2026-10-28.md).

## Published-artifact checkpoint

**Checked October 28, 11:52 UTC:** latest stable DuckDB/JDBC remain 2.0.0/2.0.0.0.
Fresh official `v2.0.0/linux_amd64` DuckLake, Quack, PostgreSQL and MySQL extension
downloads are byte-identical to the qualified pins; DuckLake remains `417a9117f5`.
DuckLake main advanced to `96c3a21f`, including snapshot tags/new `2.0-dev1` format
work, but those source changes are not these published binaries. No installation
or repin occurred. Evidence: `/tmp/opencode/catalog-upstream-review-2026-10-28/`.

- [ ] Verify R02/R04 native regressions on relevant approved published artifacts.
  Their scoped reader implementations are complete, not queued for reimplementation.
- [ ] Resolve the interval persistence, native MySQL and Doris long-partition-path
  producer/backend gates. Preserve each original failure rather than assuming all
  are fixed upstream or merely awaiting release.
- [ ] Complete the remaining [1.1 acceptance TODO](TODO-DUCKLAKE-1.1-SUPPORT.md),
  with write work separately deferred. Keep 1.1 writes disabled until approved and
  qualified; native CI is not consumer acceptance.

Exact gate names, original failure counts and upgrade constraints are in
[the current checkpoint](R07-SCOPE-AND-QUALITY-CHECKPOINT.md). Existing strict native
failures remain gates; completed local investigations do not waive them.

## Pending candidates — reproduce before fixing

These are not confirmed JVM defects. Native/connector-only failures become strict
gates and handoffs; connector agents own Trino/Doris code. The artifact checkpoint
takes precedence over this optional queue. UF-05–09 have not started.

| ID | Scope | Upstream |
|---|---|---|
| UF-05 | Sorted compaction across size rotation/repeated rewrites; independently verify row IDs | [#1634](https://github.com/duckdb/ducklake/pull/1634) |
| UF-06 | One-sided numeric bounds/null information without inventing missing endpoints; preserve conservative unknown-bound semantics | [#1713](https://github.com/duckdb/ducklake/pull/1713) |
| UF-07 | Rename+flush/lifecycle and old readers; requires safe MySQL inline preparation/publication rather than implicitly committing DDL | [#1737](https://github.com/duckdb/ducklake/pull/1737), [#1722](https://github.com/duckdb/ducklake/pull/1722), [#1764](https://github.com/duckdb/ducklake/pull/1764) |
| UF-08 | Unflushable intervals/scalar or nested VARIANT; PG TIME_NS precision; reject or qualify a safe storage path | [#1727](https://github.com/duckdb/ducklake/pull/1727), [#1769](https://github.com/duckdb/ducklake/pull/1769), [#1724](https://github.com/duckdb/ducklake/pull/1724) |
| UF-09 | MAP/hive field mapping, Parquet annotations and encrypted-import refusal; catalog validation/native gates here | [#1767](https://github.com/duckdb/ducklake/pull/1767), [#1700](https://github.com/duckdb/ducklake/pull/1700), [#1729](https://github.com/duckdb/ducklake/pull/1729), [#1726](https://github.com/duckdb/ducklake/pull/1726) |

Watch without speculative porting: batched inline commits #1762/#1765, compaction
lookup SQL #1763, SQLite retry #1753 (not a new JVM backend), and later import/
statistics/migration batches. Benchmark first; replacing giant SQL with an unbounded
metadata fetch is not a fix.

## Execution rules

Preserve production/preview/corpus pins and public consumer contracts unless an
upgrade is approved. Verify targeted regressions and full checks for fixes; commit
each verified step before proceeding, and push only with authorization. Bound SQL
pages/path transfers and traversal before destructive effects. Leave sibling code
to its agents and stop session-owned infrastructure. See [the agent guide](AGENT-GUIDE.md).

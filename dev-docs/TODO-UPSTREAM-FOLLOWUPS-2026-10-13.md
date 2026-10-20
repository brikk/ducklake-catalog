# Upstream follow-ups

Active queue; stable UF IDs are unchanged. For other review work use
[BACKLOG.md](BACKLOG.md). Consumer support lives in [compatibility](../docs/COMPATIBILITY.md).

## Next checkpoint — shipping artifacts (target October 21)

- [ ] Refresh DuckDB 2.0 release calendar and usable RC/final/JDBC/Python artifacts.
- [ ] Record the **actual** core/DuckLake/Quack hashes and catalog layout separately
  from release-branch source pins; rerun the [acceptance gates](DUCKLAKE-1.1-ACCEPTANCE.md).
- [ ] Qualify richer 1.1 metadata, other backends and any future write scope beyond
  the limited read tier. Keep writes disabled until separately approved and qualified;
  do not infer support from preview CI.

**Executed October 20 target:** staged CLI alpha49096, core `2ec7e8401c`, signed
DuckLake `417a9117f5`, signed Quack `e7a9be3f8c`. Shipping/JDBC artifact availability
must still be refreshed; no maintained or preview lock was moved. October 21 is the
release target directly confirmed to the requester. The legacy-inline read/flush
work moved from #1590 to [PeterVanHolland/ducklake#34](https://github.com/PeterVanHolland/ducklake/pull/34);
qualify actual old catalogs rather than treating a new-format smoke test as closure.

**October 20 direct-Java checkpoint:** [alpha49096 qualification](QUACK-2.0-QUALIFICATION.md)
verifies released `quack-jdbc 0.8.0` on actual protocol v3, operating on 1.0 metadata
without DuckDB JDBC. The subsequent [read tier](FINAL-1.1-READ-RESULTS.md) enables
limited, explicit final-1.1 reads; all 1.1 writes remain rejected. This does not
qualify branch-tip Quack `f96420ed`, final shipping artifacts or general 1.1 support.

## Pending candidates — reproduce before fixing

These are not confirmed JVM defects. Native/connector-only failures become strict
gates and handoffs; connector agents own Trino/Doris code. The artifact checkpoint
takes precedence over this optional queue. UF-05–09 have not started.

| ID | Scope | Upstream |
|---|---|---|
| UF-05 | Sorted compaction across size rotation/repeated rewrites; independently verify row IDs | [#1634](https://github.com/duckdb/ducklake/pull/1634) |
| UF-06 | One-sided numeric bounds/null information without inventing missing endpoints; retain C03 safety | [#1713](https://github.com/duckdb/ducklake/pull/1713) |
| UF-07 | Rename+flush/lifecycle and old readers; coordinate with C06 MySQL implicit-DDL atomicity | [#1737](https://github.com/duckdb/ducklake/pull/1737), [#1722](https://github.com/duckdb/ducklake/pull/1722), [#1764](https://github.com/duckdb/ducklake/pull/1764) |
| UF-08 | Unflushable intervals/scalar or nested VARIANT; PG TIME_NS precision; reject or qualify a safe storage path | [#1727](https://github.com/duckdb/ducklake/pull/1727), [#1769](https://github.com/duckdb/ducklake/pull/1769), [#1724](https://github.com/duckdb/ducklake/pull/1724) |
| UF-09 | MAP/hive field mapping, Parquet annotations and encrypted-import refusal; catalog validation/native gates here | [#1767](https://github.com/duckdb/ducklake/pull/1767), [#1700](https://github.com/duckdb/ducklake/pull/1700), [#1729](https://github.com/duckdb/ducklake/pull/1729), [#1726](https://github.com/duckdb/ducklake/pull/1726) |

Watch without speculative porting: batched inline commits #1762/#1765, compaction
lookup SQL #1763, SQLite retry #1753 (not a new JVM backend), and later import/
statistics/migration batches. Benchmark first; replacing giant SQL with an unbounded
metadata fetch is not a fix.

## Completed investigations and local fixes

Checked means the scoped catalog work is executed, **not that native engines are fixed**.

| Done | ID / scope | Commit / evidence |
|---|---|---|
| [x] | Original 1: legacy inline schema/expiry boundaries | `b89d870` |
| [x] | Original 2: unknown/acknowledged COMMIT classifications | `c586309`; consumer cleanup adoption still required |
| [x] | Original 3: lineage/statistics/BLOB strict native gates | `fb47ded`; native failures retained |
| [x] | Original 4: PostgreSQL page-owner lookup planning | `7023be5` |
| [x] | C05 / remaining C14: coordinated ANALYZE publication and one-connection diagnostics | `3ae1c18` |
| [x] | UF-01: transient CDC candidates and reader handoff | `e7d262b` |
| [x] | UF-02: CDC user-name collisions / JVM identity projection | `512b8df` |
| [x] | UF-03: five-route ceilings; local DuckDB/Java Quack timestamp fix | `10b4a4a` |
| [x] | UF-04: unsafe bucket-pruning gates; stored buckets unchanged | `b8dded3` |

Evidence: [October 14](archive/UPSTREAM-FOLLOWUPS-RESULTS-2026-10-14.md),
[C05](archive/FIX-C05-ANALYZE-PUBLICATION-2026-10-15.md),
[UF-01–04](archive/UPSTREAM-FOLLOWUPS-RESULTS-2026-10-15.md).
Last forced code verification: **597 JUnit tests / 9,697 corpus records**; native
gates remain separate. No stable release after 0.12.0 is implied by these snapshots.

## Execution rules

Preserve production/preview/corpus pins and public consumer contracts unless an
upgrade is approved. Verify targeted regressions and full checks for fixes; commit
each verified step before proceeding, and push only with authorization. Bound SQL
pages/path transfers and traversal before destructive effects. Leave sibling code
to its agents and stop session-owned infrastructure. See [the agent guide](AGENT-GUIDE.md).

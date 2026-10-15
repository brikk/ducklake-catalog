# Upstream follow-ups — short TODO

Baseline: released catalog **0.12.0**, main **0.13.0-SNAPSHOT**. Findings and
provenance: [October 13 survey](UPSTREAM-SURVEY-2026-10-13.md).

## Work in order

1. [x] **Old inline schemas, expiration and flushes** (#1590/#1679).
   Reproduce missing schema-version rows with native 1.5.6 catalogs; cover ALTER,
   rename, unrelated DDL, multiple inline versions and snapshot expiration.
   Audit JVM schema resolution; fix only demonstrated local defects. Preserve
   historical values and distinguish native read/flush bugs from JVM failures.
2. [x] **Unknown COMMIT outcomes and cleanup** (DataFusion #369).
   Fault-inject failure before COMMIT versus a lost response after a successful
   commit. Specify/classify definite rollback versus unknown outcome; ensure the
   caller is not told to delete committed files or blindly republish. Coordinate
   downstream cleanup obligations without editing sibling repositories.
3. [x] **Lineage, statistics and BLOB regression gates** (#1611/#1717/#1695).
   Cover sorted/updated-row merges, deleted inline rows becoming falsely exact
   MIN/MAX bounds, and plain/nested BLOBs with `binary_as_string` enabled. Use
   independent expected IDs/rows/bytes; retain NULL semantics and explicit native
   failures rather than weakening goldens or copying old-engine bugs.
4. [x] **PostgreSQL planning at scale** (DataFusion #373).
   Exercise custom and generic prepared plans for file/statistics pages; measure
   fan-out and retain bounded SQL/path transfers. Don't port LATERAL/ASOF blindly
   across backends. C05 concurrent ANALYZE publication is tracked separately;
   the query-performance change itself did not fix it.
5. [ ] **Deferred RC refresh — October 17–18, if the calendar holds.**
   Confirm final engine/JDBC/extension pins and which fixes are actually bundled;
   rerun acceptance gates before explicitly scoping real 1.1 implementation.

## New queue — October 15 upstream refresh

Stable **UF-xx** work IDs below do not renumber the canonical C/H/B/N review IDs.
These are reproduction/parity candidates, **not confirmed JVM defects**. Fix only
what fails locally; native/connector-only failures become explicit gates/handoffs.
The scheduled RC refresh above takes precedence on October 17–18.

| Done | ID | Priority / next investigation | Upstream reference |
|---|---|---|---|
| [x] | **UF-01** | **Catalog candidates verified; strict native gate fails.** Same-snapshot versions require reader-side suppression before pairing; later/tagged deletes covered. [Evidence/handoff](UPSTREAM-FOLLOWUPS-RESULTS-2026-10-15.md#uf-01--transient-cdc-rows-1675). | [#1675](https://github.com/duckdb/ducklake/pull/1675) |
| [x] | **UF-02** | **JVM field-ID/identity projection verified; native gate fails.** Four expected images become eight natively. Payload suffix compatibility documented; no local renames. [Evidence/handoff](UPSTREAM-FOLLOWUPS-RESULTS-2026-10-15.md#uf-02--cdc-user-name-collisions-1723). | [#1723](https://github.com/duckdb/ducklake/pull/1723) |
| [x] | **UF-03** | **Five-route JVM ceilings verified; timestamp lookup fixed** for local DuckDB/Java Quack. Native Quack leak remains a strict failing gate, not a JVM transaction defect. [Evidence](UPSTREAM-FOLLOWUPS-RESULTS-2026-10-15.md#uf-03--quack-snapshot-ceilings-1756). | [#1756](https://github.com/duckdb/ducklake/pull/1756) |
| [ ] | **UF-04** | **P1 — unsafe bucket pruning:** NaNs, nested signed zeros and partly foldable IN lists must keep matching files. Do not rehash existing partitions. Catalog/native checks here; engine hashing/pruning belongs to connector agents. | [#1696](https://github.com/duckdb/ducklake/pull/1696) |
| [ ] | **UF-05** | **P2 — sorted compaction:** retain configured order across target-size rotation, repeated rewrites and merges; independently check row identity too. | [#1634](https://github.com/duckdb/ducklake/pull/1634) |
| [ ] | **UF-06** | **P2 — one-sided numeric stats:** preserve usable null information/known bounds without inventing missing endpoints or empty ranges. Keep C03's coverage/NaN safety rules; distinguish plan improvements from wrong results. | [#1713](https://github.com/duckdb/ducklake/pull/1713) |
| [ ] | **UF-07** | **P2 — inline rename/lifecycle:** same-transaction rename+flush uses the current entry, and superseded empty tables are reclaimed without breaking old readers. Coordinate with canonical C06; no implicit MySQL DDL commits. | [#1737](https://github.com/duckdb/ducklake/pull/1737), [#1722](https://github.com/duckdb/ducklake/pull/1722), [#1764](https://github.com/duckdb/ducklake/pull/1764) |
| [ ] | **UF-08** | **P2 — flushable inline values:** negative intervals and unsupported scalar/nested VARIANT encodings must be safely rejected or stored via a qualified path; verify PG TIME_NS text precision. No 1.1 enablement. | [#1727](https://github.com/duckdb/ducklake/pull/1727), [#1769](https://github.com/duckdb/ducklake/pull/1769), [#1724](https://github.com/duckdb/ducklake/pull/1724) |
| [ ] | **UF-09** | **P2 — import/type mapping:** verify MAP child position versus names, top-level-only hive mappings, new Parquet annotations and encrypted-import refusal. Catalog payload validation/native canaries here; Parquet import implementation remains a connector handoff. | [#1767](https://github.com/duckdb/ducklake/pull/1767), [#1700](https://github.com/duckdb/ducklake/pull/1700), [#1729](https://github.com/duckdb/ducklake/pull/1729), [#1726](https://github.com/duckdb/ducklake/pull/1726) |

**Execution order: UF-01 → UF-02 → UF-03 → UF-04.** UF-01–03 are investigated;
native failures remain explicit. Continue at UF-04 with independent expected rows/IDs.

Lower-priority upstream watch: collection/batched inline commits
([#1762](https://github.com/duckdb/ducklake/pull/1762), [#1765](https://github.com/duckdb/ducklake/pull/1765)),
bounded compaction lookup SQL ([#1763](https://github.com/duckdb/ducklake/pull/1763))
and SQLite conflict-check retry ([#1753](https://github.com/duckdb/ducklake/pull/1753)).
Benchmark before proposing local changes; SQLite isn't a new qualified JVM backend.
Avoid giant SQL text without replacing it with an unbounded metadata fetch.

Refresh provenance: DuckLake main `a457bcc918ce5c66d1495853b707f0d4dced4395`;
maintained v1.5 `343df36a` and 2.0 bundle `80e6155e` are unchanged. #1590 remains
open, latest JDBC is 1.5.6.0, newest format is still 1.1-dev1. These main-only merges
do not prove fixes are in our maintained or final shipping artifacts. Recheck at RC.

## Rules / handoff

- Verify targeted regressions and full checks for fixes; commit each verified step
  with its results before proceeding. Do not push without permission.
- Keep 1.1 writes disabled and current production/preview pins unchanged unless
  separately approved. Preserve public consumer contracts; use disposable fixtures.
- Leave Trino/Doris work to their agents; stop session-owned test infrastructure.
- User approved execution October 14. Items 1–4 are executed locally; item 3 has
  strict gates with explicit native failures, not native fixes. See
  [execution evidence](UPSTREAM-FOLLOWUPS-RESULTS-2026-10-14.md).
  Downstream cleanup/error adoption is a handoff, not a claimed sibling fix.
  Commits: item 1 `b89d870`, item 2 `c586309`, item 3 `fb47ded`; item 4 is the
  final log/plan-check commit `7023be5`. That run passed 576 tests / 9,697 records.
  Resume at item 5 in the October 17–18 RC window. C05 was subsequently fixed
  separately in `3ae1c18` October 15, with 589 tests / 9,697 records verified; see
  [its compatibility report](FIX-C05-ANALYZE-PUBLICATION-2026-10-15.md).
  The UF queue is the next optional regression/fix work; do not postpone the RC refresh for it.

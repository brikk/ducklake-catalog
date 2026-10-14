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
   across backends. C05 concurrent ANALYZE publication remains a separate backlog
   item, not implicitly fixed by a query-performance change.
5. [ ] **Deferred RC refresh — October 17–18, if the calendar holds.**
   Confirm final engine/JDBC/extension pins and which fixes are actually bundled;
   rerun acceptance gates before explicitly scoping real 1.1 implementation.

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
  final log/plan-check commit. Final forced verification: 576 tests / 9,697 records.
  Resume at item 5 in the October 17–18 RC window. C05 remains separate and open.

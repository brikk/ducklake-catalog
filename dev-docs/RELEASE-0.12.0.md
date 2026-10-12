# 0.12.0 — regression and compatibility preparation

All three modules (`ducklake-catalog`, `ducklake-test-corpus-replay`, `slt-format`)
and catalog test fixtures are released together at 0.12.0.

**This is preparation, not DuckLake 1.1 support or final DuckDB 2.0 qualification.**
There are no production implementation, public API/record, runtime dependency or
corpus-pin changes relative to 0.11.0. The maintained lane remains DuckLake 1.0,
DuckDB/JDBC 1.5.6/1.5.6.0 and optional direct Java Quack JDBC 0.8.0. Applications
still supply their own runtime driver. All 1.1 writes remain disabled.

## Included preparation

- Repeated-update/delete histories across inline flushes, verifying deletion
  snapshots by physical file position rather than reused absolute row IDs.
- JVM inline/file-delete merge coverage and expiration fixtures with 3,000
  snapshots, 1,000 data files and 1,000 delete files on local DuckDB/native Quack.
- Immutable, separately labelled maintained/preview binary and bundle/main source
  layout fixtures. CI regenerates the locked local preview fixtures; a dev format
  string alone is not a capability guarantee.
- A Java 17 consumer compiled against published 0.11.0, record-component locks and
  wire controls guarding existing constructors/accessors and unknown/null semantics.
- Cross-process native migration and backup-restore rehearsals, unsupported-version
  guard coverage, and an acceptance runbook for the release-candidate window.
- README support boundaries and transport bootstrap behavior clarified.

## Important limits and consumer guidance

Both sampled native engines still fail the strict upstream change-feed (#1587)
and same-transaction delete/flush (#1662) probes. These are explicitly recorded
failing native qualification gates, not fixed by this release or waived by passing
JVM tests.

The locked preview keeps default DuckLake format 1.0, but a write/checkpoint can
upgrade local metadata storage so 1.5.6 cannot reopen it. **Rollback requires a
tested, consistent backup**, not an older JDBC JAR or a catalog-version edit.
The old preview lacks persisted parent-schema IDs that newer source declares,
despite both describing 1.1-dev1. Actual 1.1 APIs/migrations/writes await explicit
release-candidate qualification. Sibling connector qualification is separate.

## Verification

At preparation baseline `105a325`, [CI](https://github.com/brikk/ducklake-catalog/actions/runs/38304781981),
[snapshot publication](https://github.com/brikk/ducklake-catalog/actions/runs/38304782397)
and both [pinned native-preview jobs](https://github.com/brikk/ducklake-catalog/actions/runs/38304788845)
passed. The new snapshot artifacts were publicly downloaded and verified.

Forced local checks passed **533 JUnit tests**, zero failures/errors/skipped JUnit
tests, **9,697 corpus records**, detekt and Java 17 checkAbi. Existing 31 corpus
file skips and 12 record skips remain. Four Python fixture-contract tests, five
exact fixture comparisons and all 12 native migration rehearsal checks passed;
the strict native correctness gates above remain failing.

Local release-version checks at 0.12.0 also passed; generated POMs and Gradle
metadata for all three published modules contain 0.12.0 and no snapshot references.

See [the execution evidence](DUCKLAKE-1.1-PREP-RESULTS.md),
[capability matrix](DUCKLAKE-1.1-CAPABILITIES.md),
[consumer contract](DUCKLAKE-1.1-CONSUMER-CONTRACT.md) and
[migration/rollback acceptance runbook](DUCKLAKE-1.1-ACCEPTANCE.md).
The [0.11.0 notes](RELEASE-0.11.0.md) retain the maintenance/transport behavior
changes; consumers predating 0.10.0 must still adopt its nullable row-ID contract.

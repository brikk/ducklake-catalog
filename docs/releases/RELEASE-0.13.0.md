# 0.13.0 — DuckDB 2.0 lane, opt-in DuckLake 1.1 reads, correctness repairs

All three modules (`ducklake-catalog`, `ducklake-test-corpus-replay`, `slt-format`)
and catalog test fixtures are released together at 0.13.0.

## Runtime lane

- Maintained DuckDB **2.0.0**, JDBC **2.0.0.1**; native peers signed DuckLake
  `417a9117f5`, native Quack `f96420ed46`. Applications still supply their drivers.
- Optional direct Java Quack JDBC **0.12.1**. It fixes intermittent
  `Current transaction is aborted` failures in read sessions after a result is closed
  early ([diagnosis](../../dev-docs/archive/QUACK-TRANSACTION-DIAGNOSIS-2026-11-03.md)).
  Do not pair direct read sessions with quack-jdbc 0.12.0 or older.
- Catalog format remains **DuckLake 1.0** for read/write. All DuckLake 1.1 writes
  remain disabled. Published libraries target Java 17.

## Upgrade notes (behavior changes from 0.12.0)

- [Upgrading to 0.13.0](../SNAPSHOT-UPGRADE.md): DuckDB 2.0 runtime alignment and
  initializing new native lakes at DuckLake 1.0.
- [Materialization snapshots](../MATERIALIZATION-SNAPSHOTS.md): mutation overloads
  that cannot identify the planning snapshot now reject before mutation; use the
  listed replacements. JVM signatures remain linkable.
- [Commit outcomes](../COMMIT-OUTCOME-CONTRACT.md): unknown-outcome and post-commit
  exceptions must not be blindly retried or have their output deleted.
- [Nested type signatures](../NESTED-TYPE-SIGNATURES.md): unsafe nested field names
  are now quoted unambiguously; consumers should use the shared parser.

## Added

- Explicit, default-off [final DuckLake 1.1 reader](../READING-1.1.md) on qualified
  routes ([backends](../READ-BACKENDS.md)), with an additive
  [read-metadata companion](../READ-METADATA.md), [schema hierarchy](../SCHEMA-HIERARCHY.md),
  [controlled read sessions](../READ-SESSION-CONTROL.md) and SQL-bounded pages for
  [files](../READ-FILE-PAGES-1.1.md), [deletions](../READ-DELETION-PAGES-1.1.md),
  [inline rows](../READ-INLINE-PAGES-1.1.md) and [planning metadata](../READ-PLANNING-METADATA-1.1.md).
- [Initial-default projection](../FILE-INITIAL-DEFAULTS-1.1.md) for missing file fields.

## Fixed (selection)

Catalog: identifier matching and missing-table classification; namespace/partition
validation and redacted diagnostic URLs; nested column shapes on rename; unsafe
MySQL inline schema mutations refused before metadata writes; legacy inline initial
defaults; logical float precision and end-of-day time in pruning statistics;
statistics aggregation types; stale ordinary rewrites fenced against newer native
inline deletes; message-required snapshot writes refused before mutation; expiry
criteria validation; fail-closed SQL-server read-session setup.

Replay/SLT harness: SQL-type-aware golden comparison, structured mirror comparison,
separately measured oracle/mirror coverage, no manufactured values for unreadable
cells, typed Boolean hashes, JVM-independent floating text, single-execution DML
RETURNING, bounded parsing/expansion resources, per-term skip policies, loop
termination and ordered loop frames, raw-plus-keyword golden cells, attachment
state after successful SQL, timed-out worker quarantine. See
[COMPATIBILITY.md](../COMPATIBILITY.md) for the replay contracts.

## Known limits

- Six strict upstream all-types corpus records with native `TIME 24:00:00` cannot
  be represented by the pinned DuckDB JDBC driver ([duckdb-java#448](https://github.com/duckdb/duckdb-java/issues/448));
  they stay visibly failing, not skipped or faked.
- Native qualification gates for some upstream DuckDB/DuckLake behaviors remain
  failing and recorded (see the [backlog](../../dev-docs/BACKLOG.md)).
- quack-jdbc can refuse very large, highly compressible single-response results under
  its default decode budget ([driver follow-up](https://github.com/brikk/duckdb-quack-jdbc/blob/v0.12.1/dev-docs/DECODE_BUDGET_FOLLOWUP.md)).
  Catalog metadata reads are small and unaffected.
- Trino/Doris connector qualification of this artifact is separate.

## Verification

Latest forced full run at `7ebc2f3`: 1,177 catalog, 91 direct-Java Quack and 133 SLT
tests pass; replay 291/292, failing only on the six TIME records above (17,596 corpus
records pass). Driver adoption at `556dd17`: 93/93 Quack, 35 catalog direct-driver
and 27 replay direct-Quack mirror tests pass
([adoption record](../../dev-docs/archive/UPGRADE-QUACK-JDBC-0.12.1-2026-11-03.md)).

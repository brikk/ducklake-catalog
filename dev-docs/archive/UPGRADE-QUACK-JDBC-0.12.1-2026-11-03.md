# Java Quack JDBC 0.12.1 adoption — November 3, 2026

Owner requested adopting `dev.brikk.duckdb:quack-jdbc:0.12.1`, the driver release
made for the [diagnosed Quack transaction aborts](QUACK-TRANSACTION-DIAGNOSIS-2026-11-03.md).
Base `7ebc2f3`. Catalog publication, connector adoption and native/server upgrades
remain outside this request.

**Status: adopted and verified on every Quack-touching suite.** Full corpus
replay was intentionally not rerun: this change only moves the optional direct-driver
pin, and the corpus identity run does not load that driver.

## Artifact and scope

- Shared Java driver pin **0.12.0 → 0.12.1**: version catalog, actual-version
  assertions, fixture provenance and exact JAR checksum. Historical reports, the
  diagnosis archive (which reproduces against 0.12.0) and wire samples keep their
  original versions/results.
- Central JAR SHA256:
  `fcf2a7a54ef302044569504216c546088b7ab3521f599911861359c044bf72c3`.
  Independently downloaded bytes match Central's `.jar.sha256`. All **107 classes**
  target Java17-or-lower (major61); no native libraries or bundled `org.duckdb`
  classes. The POM's only dependency is test-scoped JUnit. Evidence:
  `build/verification/quack-jdbc-0.12.1/artifact.json`.
- Driver release `cca8445`, tag [v0.12.1](https://github.com/brikk/duckdb-quack-jdbc/releases/tag/v0.12.1).
  Change: closing a result whose statement the server still reports unfinished now
  drains and discards the remaining batches (one held at a time) before the next
  request, in both commit modes. Completed results send no extra FETCH. Errors
  surface; no SQL replay or implicit CANCEL. Closing a large unread result can
  therefore take time. Decoder behavior and limits are unchanged; the separate
  [decode-budget limitation](https://github.com/brikk/duckdb-quack-jdbc/blob/v0.12.1/dev-docs/DECODE_BUDGET_FOLLOWUP.md)
  remains open in the driver.
- Embedded JDBC stays **2.0.0.1**, core **2.0.0 / d54cbbe3df**, signed DuckLake
  **417a9117f5**, native Quack **f96420ed46**. Library runtime, format/write guards,
  corpus and connector code are unchanged.

## Desired-behavior regression

New opt-in `TestDirectQuackEarlyClose` (direct driver, signed v3 peer, no catalog):

- `closingAnUnfinishedResultKeepsTheTransactionUsable`: in manual-commit mode, read
  the first row of the valid `SELECT 1; SELECT sum(sqrt(i::DOUBLE)) FROM range(10000000) t(i)`,
  close, then read in the same transaction. **Red on 0.12.0** with the original
  `Current transaction is aborted (please ROLLBACK)` (`before.log`, `before-xml/`).
- `closingSingleRowProbesRepeatedlyKeepsTheTransactionUsable`: 200 catalog-shaped
  one-row probes closed after their first row, each followed by a read. This race is
  narrow, so it passes on 0.12.0 too; it is coverage, not a red baseline.

## Verification (Java17 direct-Quack launcher, signed v2.0.0 peer)

- **Opt-in Quack group: 93/93** (the existing91 plus both new tests), zero
  failures/errors/skips. Includes every earlier failing class
  (`TestFinal11ReadPreview`, `TestFinal11LegacyStructOrder`).
- **Catalog direct-driver tests: 35/35** (`TestJdbcDucklakeCatalogDirectQuack`,
  `TestFinal11DuckDbBackends`, `TestJdbcDucklakeCatalogOnQuackSmoke`); catalog lint passes.
- **Replay final-1.1 mirrors on direct Quack: 27/27** (Evolution8, Defaults3,
  Hierarchy4, TemporalValues3, DuckDbBackends6, ReadMetadata3). The Evolution
  cases are where the JDBC2.0.0.1 run's aborts appeared.
- Evidence: `build/verification/quack-jdbc-0.12.1/{focused.log,focused-xml/,focused-summary.json,replay-quack.log,replay-quack-xml/,source-hashes.json}`.
  Tested source hashes match the committed files.

Not rerun, by owner direction: the full corpus identity run and unrelated modules,
whose last forced run (`7ebc2f3`) retained only the six parked TIME records.
A later passing run is not, by itself, proof that a race is gone; the deterministic
regression is the evidence that the driver behavior changed.

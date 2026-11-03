# Java Quack JDBC 0.13.0 adoption — November 3, 2026

Owner requested adopting `dev.brikk.duckdb:quack-jdbc:0.13.0` (already used by the
Trino adoption lane) and releasing it as catalog 0.13.2. Base: v0.13.1 plus the
snapshot resume (`bfd66dd`).

## Artifact and scope

- Driver pin **0.12.1 → 0.13.0**: version catalog, actual-version assertions, fixture
  provenance and exact JAR checksum. Earlier reports keep their versions.
- Central JAR SHA256 `0935b75d3aa86a320b9f1d0330fbd8dfa551c4c2505aa11cc1be612dfdf455e0`,
  matching Central's `.jar.sha256`. 111 classes, all Java17-or-lower (major61); no
  native libraries or bundled `org.duckdb` classes; only a test-scoped JUnit dependency.
  Evidence: `build/verification/quack-jdbc-0.13.0/artifact.json`.
- Driver release `aad8bdf` descends from 0.12.1 (`cca8445`), so it keeps the
  transaction-safe result close. Its only change keeps large primitive SEQUENCE and
  CONSTANT vectors compact, addressing the default decode-budget limit described in
  the driver's `DECODE_BUDGET_FOLLOWUP.md`.
- Embedded JDBC 2.0.0.1, core 2.0.0 / d54cbbe3df, signed DuckLake 417a9117f5, native
  Quack f96420ed46 and corpus 417a9117f590e8141742ac0579d02f6a90162e37 are unchanged.

## Verification (Java17 direct-Quack launcher, signed v2.0.0 peer)

- Opt-in Quack group **93/93**, including `TestDirectQuackEarlyClose` (the transaction
  regression) and the earlier failing read-preview/legacy-STRUCT classes.
- Catalog direct-driver tests **35/35**; catalog lint passes.
- Replay final-1.1 direct-Quack mirrors **27/27** (Evolution8, Defaults3, Hierarchy4,
  TemporalValues3, DuckDbBackends6, ReadMetadata3).
- Zero failures/errors/skips. Evidence: `build/verification/quack-jdbc-0.13.0/{focused.log,xml/,summary.json}`.

Unrelated modules and the full corpus were not rerun: only the optional driver pin moved.

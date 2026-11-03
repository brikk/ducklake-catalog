# 0.13.2 — direct Quack JDBC 0.13.0

All three modules (`ducklake-catalog`, `ducklake-test-corpus-replay`, `slt-format`)
and catalog test fixtures are released together at 0.13.2. Library code is unchanged
from [0.13.1](RELEASE-0.13.1.md), including the replay `ReplaySetupFormat` option.

## Changed

- The optional direct Java Quack driver is now qualified at **`dev.brikk.duckdb:quack-jdbc:0.13.0`**
  (previously 0.12.1). It keeps 0.12.1's fix for read transactions aborting after an
  early result close, and keeps large primitive sequence/constant results compact, so
  very large compressible results no longer hit the default decode budget as early.
  Applications still supply this driver at runtime; do not use 0.12.0 or older.
- Other pins unchanged: DuckDB 2.0.0 / JDBC 2.0.0.1, signed DuckLake `417a9117f5`, native
  Quack `f96420ed46`, corpus `417a9117f590e8141742ac0579d02f6a90162e37`.

## Verification

See the [adoption record](../../dev-docs/archive/UPGRADE-QUACK-JDBC-0.13.0-2026-11-03.md):
every direct-Quack suite (opt-in Quack group, catalog direct-driver tests, replay
final-1.1 direct-Quack mirrors) on the Java 17 launcher against the signed peer.

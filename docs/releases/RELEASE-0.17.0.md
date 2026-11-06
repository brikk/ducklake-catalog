# 0.17.0 — O(columns) planning bounds and fewer commit round trips

All three modules and catalog test fixtures are released together at 0.17.0, built from the
Trino-verified source `5377b5390b48de730d23f1043c3e006e211fb848` (snapshot
`0.17.0-20261106.162112-2`). Only the catalog changes; replay and SLT are as in
[0.16.0](RELEASE-0.16.0.md). Pins unchanged. No existing API changed.

## Added

- `getCurrentColumnBounds(tableId): List<DucklakeColumnBounds>` (`columnId`, `containsNull`,
  `containsNan`, `minValue`, `maxValue`): one read of `ducklake_table_column_stats` per table
  instead of aggregating every active file's statistics. Latest snapshot only, inside
  `readSession`. Bounds are a gross superset (deleted rows can keep them wide): fine for pruning
  and estimates, not MIN/MAX answers; treat `containsNan = true` as non-pruning. A column with no
  row is omitted (unknown); fall back to `getColumnStats`, which remains the exact, per-snapshot
  aggregation and the only choice for time travel.

## Changed

- Every write reads its guard metadata (version, encrypted, require_commit_message) in one
  locking query instead of three: two fewer round trips per commit, same semantics. Measured
  commit cost is 16–23 statements regardless of how many files a commit registers.

## Verified, no change needed

- A drop that commits after a concurrent rename of the same table or view (upstream
  duckdb/ducklake#1904) is already refused: the writer retries against the latest snapshot.
  Regression tests now guard it.

## Verification

Targeted tests: `TestCurrentColumnBounds` (natively written catalog), write-guard and commit-outcome
suites on all five backends, `TestConcurrentDropVsRename`, detekt. Full suite in CI.
Trino 483/JDK 25 on the snapshot JARs: 1,085 tests, zero failures/skips.
Consumer notes: [dev-docs/CONSUMER-NOTES-0.17.md](../../dev-docs/CONSUMER-NOTES-0.17.md).

# 0.11.0 — maintenance correctness, DuckDB 1.5.6 and direct Java Quack

All three published modules (`ducklake-catalog`, `ducklake-test-corpus-replay`,
`slt-format`) and catalog test fixtures are released together at 0.11.0.

## Included changes

- Retain expired snapshots' conflict history so native stale DELETEs and JVM retry
  loops do not miss intervening compaction/DDL conflicts. Snapshot/file GC remains intact.
- Revalidate version guards on every mutation attempt/owned connection, including
  DDL, stats, expiry and cleanup; remove the stale instance-level guard cache.
- Use explicit geometry lambda SQL compatible with the maintained lane and native
  2.0 previews; preserve golden/mirror BLOB and NULL semantics.
- Centralize exact known inline metadata-column layouts, including renamed dev
  columns, without enabling dev-format writes. Avoid expected missing-table SQL
  failures that poison pinned read sessions.
- Upgrade DuckDB JDBC to **1.5.6.0**, match the Quack server to **1.5.6**, and pin
  the corpus to that release's DuckLake extension SHA
  `ac7595b0a1305bea3d4cfaca763b0ce964c763a2`.
- Qualify released **`dev.brikk.duckdb:quack-jdbc:0.8.0`** after first testing its
  published snapshot. Support direct `jdbc:quack://` dialect inference and standard
  JDBC STRUCT decoding, preserving the existing embedded/native Quack route.

## Consumer notes

No new public method/record signature changes relative to **0.10.0**. Consumers
upgrading from older versions must still adopt the [0.10.0 nullable-row-ID contract](RELEASE-0.10.0.md).

Intentional behavior changes:

- `listSnapshotChanges()` includes retained conflict records whose snapshots have
  expired; `getSnapshot()` for those IDs is still null. Do not assume a one-to-one
  relationship with the time-travel snapshot listing.
- Every mutation rejects unsupported catalog formats; earlier file-only guards
  could permit unsafe metadata mutations. **DuckLake 1.1 writes remain disabled.**
- Selected cleanup acknowledgements now commit their bounded chunks atomically
  inside one guarded transaction. Exact matching, page/path limits and traversal
  responsibilities are unchanged.
- Drain old in-flight writers when deploying the conflict-history fix: previously
  deleted records cannot be restored, and older expiration implementations can
  independently remove history.

Applications must supply their selected runtime driver. The Java driver is an
optional/test dependency here, not a forced transitive dependency of the catalog:

```kotlin
runtimeOnly("dev.brikk.duckdb:quack-jdbc:0.8.0") // direct jdbc:quack://
// OR, for local DuckDB / embedded native Quack:
runtimeOnly("org.duckdb:duckdb_jdbc:1.5.6.0")
```

The replay artifact resolves DuckDB JDBC 1.5.6.0. Align any separately pinned native
client/server fixtures in consumers too. Quack transports metadata here; readers
still need access to Parquet data. See [the 1.5.6 upgrade report](UPGRADE-DUCKDB-1.5.6-2026-10-10.md)
for direct-driver coverage and exact artifact provenance.

## Verification and limits

Pre-release verification at upgrade commit `6409b4c`:
[CI](https://github.com/brikk/ducklake-catalog/actions/runs/38075474054),
[snapshot publication](https://github.com/brikk/ducklake-catalog/actions/runs/38075474092),
and [native preview](https://github.com/brikk/ducklake-catalog/actions/runs/38075481672)
all passed. Local release checks and POM/module metadata generation also passed.

Final upgrade verification passed **525 JUnit tests** (434 catalog, 51 SLT, 40 replay),
zero failures/errors/skipped JUnit tests; detekt and Java 17 checkAbi passed.
The full release corpus passed **9,697 records**, zero failures (existing harness
file/record skips remain documented in the upgrade report).

The native preview CI checks pinned local DuckDB, PostgreSQL and matched native
Quack peers. This remains **preparation, not full JVM/final DuckDB 2.0 certification**.
Full 1.1 schema/type/nested-schema and protocol qualification gates remain open.

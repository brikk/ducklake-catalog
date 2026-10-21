# Migration, rollback and RC acceptance runbook

**Limited read tier, not full support.** [Opt-in final-1.1 reads](../docs/READING-1.1.md)
are qualified on the official 2.0 direct-Java peer. The [shipping upgrade](UPGRADE-DUCKDB-2.0.0.md)
qualifies JDBC/local/native-Quack/Java-Quack 1.0 access and native migration rehearsals.
Richer 1.1 metadata, other 1.1 backends and all 1.1 writes remain gated below.
The preview results further down are historical; dates do not replace artifact verification.

## Before touching a real catalog

1. Record exact core/JDBC/extension/server hashes, DuckLake format, metadata backend
   and physical storage version. Use the verified official 2.0 release lock, not
   branch-tip identities or a moving `latest` pin in production/CI.
2. Stop/drain every writer and maintenance worker, including older native clients
   and remote Trino/Doris processes. PG/MySQL guard-row locks protect only scoped
   mutations; they do not coordinate arbitrary online catalog DDL migration.
3. Back up catalog metadata **and** the referenced data namespace consistently.
   Test restore on copies with the exact old engine. Local `.db` format and Quack
   server storage are separate from DuckLake catalog format; 1.0 is not a native
   storage downgrade guarantee. Include WALs via a consistent backup, not by copying
   an open `.db` file alone.
4. Keep automatic migration off for ordinary readers. Explicitly resolve reserved
   user-column collisions before migrating. Don't edit the version string as a
   substitute for a schema migration or as a rollback strategy.

## Prepared acceptance cases

| Case | Prepared check | Required RC result |
|---|---|---|
| Old Parquet files and row identity | `ducklake_11_migration.py`, real maintained-engine seed | Current rows, old file snapshot and original row IDs preserved |
| Mixed inline schema versions | Same probe: insert → ADD COLUMN → insert | Both physical versions migrate, old snapshot/value semantics preserved |
| Metadata/user-name collisions | Same probe: user `_ducklake_row_id` column | Explicit atomic rejection or deliberately qualified lossless resolution; no partial metadata rewrite |
| Live older JVM client | `TestJdbcDucklakeCatalogFileWriteGuards` | Fresh guard rejects after external version change and between preflight/owned mutation |
| Unsupported current mutations | Same test class, plus `TestInlinedMetadataLayouts` | Dev/final/unknown formats cannot be enabled by a version-string change; no snapshot/DDL side effects |
| Old short/canonical constructors and JSON | `TestDucklake11ConsumerCompatibility`, `TestJacksonWireFormat` | Frozen 0.11.0 Java consumer links, existing wire shapes remain unchanged |
| Native storage rollback | Migration probe: default 1.0 write/checkpoint, explicit migration, old reopen, pristine backup reopen | Document actual reopen failures and prove backup restore; never promise downgrade by swapping JDBC |
| Native change feeds/delete+flush | `ducklake_11_regressions.py` | Both strict gates must pass on the actual shipping engine/extension |
| Transient CDC versions (UF-01) | `ducklake_cdc_transient.py` | Same-snapshot insert/delete emits no version; update/delete retains the true preimage only; later tagged deletes remain visible |
| CDC user-name collisions (UF-02) | `ducklake_cdc_names.py` | User rowid/snapshot_id and existing suffixes do not shadow metadata or multiply update pairs; payload names remain unambiguous |
| Quack transaction ceilings (UF-03) | `ducklake_quack_snapshot_ceiling.py`, five-route JVM read-session contract | Listings/time travel/CDC never reveal commits newer than the pin; explicit future versions rejected natively; post-session reads advance |
| Safe bucket pruning (UF-04) | `ducklake_bucket_pruning.py` | FLOAT/DOUBLE NaNs, nested signed zeros/NaNs and mixed IN lists match unpartitioned controls; no stored bucket rehash |
| Old inline schemas | `ducklake_old_inline_schema.py` | Known values survive unrelated DDL and native flush; do not substitute NULL |
| Merge lineage, exact stats and BLOB bytes | `ducklake_lineage_stats_blob.py` | Current/history/update images, live MIN/MAX and plain/nested BLOB bytes survive maintenance and session settings |
| Expiration/cleanup | Scale fixture and five-backend bounded-cleanup tests | Liveness intact; larger memory-profiled scale gate and consumer traversal budgets remain required |

The JVM migration tests change/rename **synthetic metadata** to exercise guards and
layout reads. They are not an actual 1.1 migration. Native probes use separate old
and candidate processes and disposable copies; they are not JVM qualification.

## Run the native rehearsal

Prepare two isolated, deliberately pinned Python environments. For the sampled
targets these are 1.5.6 and 2.0.0.dev2610011535; use RC-specific environments later
without silently editing existing production pins or preview locks.

```sh
python3 dev-docs/probes/ducklake_11_migration.py \
  --old-python <maintained>/bin/python \
  --candidate-python <candidate>/bin/python \
  --temp-root /tmp/opencode > /tmp/opencode/catalog-11-migration.json
<candidate>/bin/python dev-docs/probes/ducklake_11_regressions.py --temp-root /tmp/opencode
<candidate>/bin/python dev-docs/probes/ducklake_old_inline_schema.py --temp-root /tmp/opencode
<candidate>/bin/python dev-docs/probes/ducklake_lineage_stats_blob.py --temp-root /tmp/opencode
<candidate>/bin/python dev-docs/probes/ducklake_cdc_transient.py --temp-root /tmp/opencode
<candidate>/bin/python dev-docs/probes/ducklake_cdc_names.py --temp-root /tmp/opencode
<candidate>/bin/python dev-docs/probes/ducklake_quack_snapshot_ceiling.py --temp-root /tmp/opencode
<candidate>/bin/python dev-docs/probes/ducklake_bucket_pruning.py --temp-root /tmp/opencode
```

The migration rehearsal records downgrade attempts as observations and fails on
lost rows/history, bad layout migration, altered default format, a failed backup
restore or a non-atomic collision rejection. It doesn't accept arbitrary user DB
paths in normal operation; only internal workers receive the temporary paths. Each
native worker has a 90-second timeout and is reaped before copying/reopening files.
No worker runs physical cleanup; all generated catalogs/data are removed at exit.

## Sampled rollback boundary

The locked preview keeps existing catalogs at 1.0 on default attach and preserves
old Parquet plus two inline schema versions. Explicit migration preserves the
same rows/snapshots and renames the inline metadata columns. A colliding user column
is rejected with an instruction to rename/flush first; logical catalog contents
remain unchanged. These statements describe the sampled preview only.

**After a default 1.0 write/checkpoint, 1.5.6 cannot reopen the local metadata file:**
old storage version 7 is lower than the file's version 69. The explicitly migrated
copy also cannot reopen. The untouched 1.5.6 backup reopens with all original rows,
schema versions and snapshots. Restore is the rollback mechanism, not an old JDBC
JAR, catalog-version edit or reverse inline-column rename.

PostgreSQL/MySQL avoid this `.db` storage-format boundary, but require separate
real migration/restore tests (including nested text inline types and DDL semantics).
Quack must qualify the matched native peers **and** direct Java driver against the
actual shipping server/storage, including cancellation, leases and network recovery.
The [shipping 2.0 qualification](UPGRADE-DUCKDB-2.0.0.md) verifies v3 without
DuckDB JDBC and the conservative final-1.1 read tier. This closes the tested artifact
gates, not richer 1.1 metadata, other 1.1 backends, connector or general failure-recovery gates.

## Go/no-go at T−3/T−4

- Refresh final schema/layout fixtures and source-vs-binary provenance; inspect
  upstream changes since the captured bundle/main, not just a version string.
- Explicitly scope real 1.1 reader/model/type/schema/migration/write work, using
  [the consumer contract](DUCKLAKE-1.1-CONSUMER-CONTRACT.md). Implement required
  features or reject unsupported cases before enabling any 1.1 mutation.
- Run real native↔JVM round trips on PG/MySQL/local/native Quack/direct Java Quack,
  fresh and migrated catalogs, snapshots/change feeds/stats/null/geometry cases,
  full checks and the actual release corpus. Preserve unknown/null semantics.
- Re-run all strict native regression gates, including old inline schemas,
  row lineage, false-exact aggregates, BLOB preservation and UF-01–04 CDC/Quack/
  pruning cases. Results depend on exact sampled builds; newer binaries pass more
  gates than the frozen preview. Neither unchanged DDL nor a source merge waives a
  failing case or qualifies every shipping engine/connector.
- Coordinate sibling-engine rollout and mixed worker/coordinator wire behavior;
  get their agents' verification, not an inferred result from this repository.
- Publish support only when the shipping artifacts and all claimed cases pass.
  Otherwise keep the maintained 1.0 lane and disabled 1.1 write guards.

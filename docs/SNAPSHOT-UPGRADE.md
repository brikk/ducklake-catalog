# Upgrading to 0.13.0-SNAPSHOT

These changes are on development snapshots after 0.12.0, not in that stable release.
They do not enable DuckLake 1.1 writes. Development now uses DuckDB **2.0.0** /
JDBC **2.0.0.0**, matching native Quack peers and corpus `417a9117f5`.

## DuckDB 2.0 runtime upgrade

- Applications supply their runtime drivers; align separately pinned DuckDB JDBC
  and native Quack peers with 2.0.0. Direct Java Quack is now **0.11.1** (protocol v3);
  see the [driver verification](../dev-docs/archive/UPGRADE-QUACK-JDBC-0.11.1-2026-10-30.md).
- New native DuckDB 2.0 lakes default to DuckLake 1.1. For catalog read/write access,
  initialize with `DUCKLAKE_VERSION '1.0', AUTOMATIC_MIGRATION FALSE`; the native
  Quack bootstrap does this explicitly. Never change a stored version string.
- DuckLake 1.0 read/write access remains the maintained scope. Final 1.1 reads are
  limited and default-off; **all 1.1 writes remain rejected**.
- A 2.0 write/checkpoint upgrades local metadata storage even without a 1.1 migration.
  A tested, consistent metadata/data backup is the rollback route, not driver downgrade.
- Replay consumers now receive DuckDB 2.0's BLOB/nested-backslash text rendering;
  preserve bytes/NULLs and update adapter renderers rather than rewriting golden rows.

## Required producer behavior

Preserve the new unknown-COMMIT and acknowledged-post-commit classifications across
coordinator/worker error mapping. Do not automatically republish or delete output
for either outcome. See the [COMMIT outcome contract](COMMIT-OUTCOME-CONTRACT.md).
Sibling connector cleanup/error mapping must be verified separately.

## Intentional ANALYZE behavior change

Successful `analyzeTable` now creates **one metadata-only snapshot** through the
normal commit/retry protocol. Its `changes_made` ledger row is empty; no data
change type is invented. Data files, schema version and allocator high-water marks
are unchanged. Global statistics remain mutable, not historical snapshot statistics.

If another writer wins, stale computed statistics are rolled back and recomputed.
Consumers must no longer assume ANALYZE leaves the snapshot head unchanged or that
every snapshot contains data changes. Normal conflict exhaustion and uncertain
COMMIT handling apply. The deprecated row-count argument remains ignored.

## Other changes

- Final-1.1 reads can be [explicitly enabled](READING-1.1.md) for direct Java Quack.
  The mode requires `readSession`, supports no nested-schema history, omits legacy
  bounds/range pruning and rejects unqualified inline projections. It does not
  migrate a catalog or enable any 1.1 writes; the flag defaults off.

- The final-1.1 reader now supplies qualified scalar **initial defaults** for fields
  absent from older inline schemas, in ordinary reads and CDC. Stored NULLs and
  literal `NULL` strings remain distinct. Defaults are values, never SQL expressions;
  invalid casts fail. Remaining unqualified projections still fail explicitly.

- Guarded final-1.1 inline evolution now maps nested fields by stable IDs and physical
  STRUCT values by name, with qualified widening conversions and literal nested-field
  defaults. It preserves NULL containers, BLOB bytes and historical/CDC identity;
  narrowing, malformed payloads and ambiguous expression-default histories fail.
  This does **not** repair shipping native STRUCT/default/flush bugs: newer upstream
  strict gates remain red, including a native crash. See the
  [current R02 status](../dev-docs/TODO-DUCKLAKE-1.1-SUPPORT.md) before rollout.

- Older inline schema-version resolution and snapshot-expiry boundary preservation
  are safer. Ambiguous evidence fails explicitly rather than silently dropping rows.
- PostgreSQL cleanup hierarchy lookups are bounded per page owner, without changing
  other backends' SQL semantics.
- Timestamp snapshot lookup renders a supported cast for local DuckDB and direct
  Java Quack. Read-session consistency semantics are unchanged.

Catalog regressions do not repair separate native engine defects. In particular,
CDC candidates still require reader-side per-row snapshot filtering, transient
version suppression and update pairing on internal identity, not user column names.
Stored bucket values must not be rehashed to hide unsafe pruning.

For dated reproduction evidence and commit SHAs, consult the
[developer archive](../dev-docs/archive/README.md).

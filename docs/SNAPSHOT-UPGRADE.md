# Upgrading to 0.13.0-SNAPSHOT

These changes are on development snapshots after 0.12.0, not in that stable release.
They do not enable DuckLake 1.1 writes or change the maintained dependency pins.

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

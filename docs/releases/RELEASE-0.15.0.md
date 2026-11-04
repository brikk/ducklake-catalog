# 0.15.0 — bounded, resumable snapshot expiry

All three modules (`ducklake-catalog`, `ducklake-test-corpus-replay`, `slt-format`)
and catalog test fixtures are released together at 0.15.0. Only the catalog changes;
the replay and SLT libraries are as in [0.14.0](RELEASE-0.14.0.md). DuckLake 1.0 remains
the supported read/write format; final 1.1 stays explicit, read-only and limited, and
all 1.1 writes stay disabled. Pins are unchanged.

## Behavior change: expiry is no longer one transaction

`expireSnapshots(ids)` deletes the snapshot rows in one short transaction. It then cleans up
dead data/delete files and dead table/view/macro/schema metadata in pages of at most 1,000
files, each committed on its own; file paths are copied in SQL and never fetched. The call
still finishes cleanup before returning.

- A file is dead when no snapshot survives in its `[begin_snapshot, end_snapshot)`. Deadness
  never reverts, so partial progress is safe and the next run finishes the job. No live or
  reachable file is ever scheduled.
- If cleanup fails after the snapshot deletion committed, the snapshots **stay expired** and
  `expireSnapshots` throws `DucklakeExpiryCleanupIncompleteException`. Do not retry the expiry;
  call `cleanUpExpiredObjects`.
- Concurrent cleanups can queue the same dead file twice. Identical queue tuples are
  equivalent, and physical deletion must tolerate an already-deleted path.

## New APIs

| API | Use |
|---|---|
| `expireSnapshots(ids, maxCleanupFiles)` | Hard per-call cleanup budget; `0` expires only |
| `cleanUpExpiredObjects(maxFiles)` | Resume or continue catalog-wide cleanup; idempotent |
| `cleanUpExpiredObjects(tableId, maxFiles)` | Clean one table's dead files (and its metadata if fully expired) |
| `listTablesWithExpiredFiles(afterTableId, limit ≤ 1000)` | Bounded paging of tables still needing cleanup |
| `ExpireSnapshotsResult.cleanupComplete` | False when the budget stopped cleanup early |
| `DucklakeExpiryCleanupIncompleteException` | Cleanup failed after a committed expiry |

Snapshots stay catalog-wide; per-table work is cleanup only. Incremental pattern:
`expireSnapshots(ids, 0)`, then `cleanUpExpiredObjects(tableId, budget)` per table from
`listTablesWithExpiredFiles`, and occasionally an unscoped `cleanUpExpiredObjects` for
view/macro/schema metadata. The queries are designed around `ensureRecommendedIndexes()`.

`DucklakeCatalog` gained these methods; wrappers that implement it without delegation must add
them. The 0.14 `ExpireSnapshotsResult` constructor and `copy` signatures still link.

## Verification

- **Consumer trials** of snapshot `0.15.0-20261104.182540-2`:
  - Trino 483/484: 1,075 tests each, zero failures.
  - Doris: Java 25 319, Java 17 276 and build logic 8 tests, zero failures.
  - Both exercised expiry-failure recovery, table-scoped cleanup and duplicate/missing-file deletion.
- **Post-trial fixes:** the typed error, the strict budget and `copy` binary compatibility were
  added after the trials, at both teams' request, with dedicated regressions.
- **Catalog:** full suite 1,197 tests, zero failures; `checkAbi` (Java 17) and detekt clean. The full corpus was not rerun; replay
  is unchanged.
- **Consumer notes:** [dev-docs/CONSUMER-NOTES-0.15.md](../../dev-docs/CONSUMER-NOTES-0.15.md).

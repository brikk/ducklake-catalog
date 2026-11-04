# Consumer notes for the next release (0.15.0) — trino-ducklake and doris-ducklake

Running list of what both plugins should review when adopting the release that follows
0.14.0. Updated with each round of scale work (R1–R5 in
[RESEARCH-SCALE-OPTIMIZATIONS.md](RESEARCH-SCALE-OPTIMIZATIONS.md)). Nothing here is
released yet; main is `0.15.0-SNAPSHOT`.

## Must review

### Snapshot expiry is no longer one transaction (R1, `46442ee`)

- `expireSnapshots(ids)` deletes the snapshot rows in one short transaction, then cleans up
  dead files and metadata in pages of 1,000, each committed separately. The call still runs
  cleanup to completion, so existing callers keep working unchanged.
- Visible difference: if cleanup fails partway (crash, timeout, lost connection), **the
  snapshots stay expired** and some unreachable files remain unscheduled until the next cleanup.
  Before, the whole expiry rolled back. No live or reachable file is ever scheduled.
- Error handling: a failure after phase 1 should not be retried as "expiry failed, retry
  the same ids". The snapshots are already gone. Retry with `cleanUpExpiredObjects(...)`.
- Concurrent cleanups can schedule the same dead file twice. Queue consumers already treat
  identical tuples as equivalent (`listScheduledFileBatch`); physical deletion must tolerate
  an already-deleted path.

### New APIs

| API | Use |
|---|---|
| `expireSnapshots(ids, maxCleanupFiles)` | Bound one maintenance call (hard limit); `maxCleanupFiles = 0` expires only |
| `cleanUpExpiredObjects(maxFiles)` | Resume or continue catalog-wide cleanup; idempotent |
| `cleanUpExpiredObjects(tableId, maxFiles)` | Clean one table's dead files (and its metadata if the table is fully expired) (R3) |
| `listTablesWithExpiredFiles(afterTableId, limit ≤ 1000)` | Page through tables needing cleanup (R3) |
| `ExpireSnapshotsResult.cleanupComplete` | False when a budget stopped cleanup early |

`ExpireSnapshotsResult(expired, scheduled)` still compiles and links, meaning a completed cleanup.

## Should consider

- **Procedure surface.** Trino's and Doris's expire procedures could expose a cleanup budget
  and report `cleanupComplete`, plus a separate "clean up expired objects" procedure,
  optionally per table. Today both call `expireSnapshots(set)`.
- **Incremental maintenance (R3).** For catalogs with many tables:
  `expireSnapshots(ids, 0)` → loop `listTablesWithExpiredFiles` → `cleanUpExpiredObjects(table, budget)`
  per table → occasionally an unscoped `cleanUpExpiredObjects` for catalog-wide
  view/macro/schema metadata, which table scope does not cover.
- **Indexes.** The cleanup and listing queries are designed around
  `ensureRecommendedIndexes()` (0.14.0). Run it once on PostgreSQL/MySQL catalogs.
- **Large catalogs.** `listTablesWithExpiredFiles` scans retired file rows above the cursor;
  page with the returned last id rather than restarting from null.

## Trial results

- **Trino** (snapshot `0.15.0-20261104.182540-2`): PASS on 483 and 484, 1,075 tests each,
  zero failures/skips, including expiry-failure recovery, table-scoped cleanup and
  duplicate/missing-file deletion. Trial commit `5fe49e3` (not pushed); normal pin still 0.14.0.
  Non-blocking requests: a typed error distinguishing a committed expiry from failed cleanup,
  and a strict (or explicitly bounded) `maxFiles` overshoot contract. Today a page of data files
  also takes the delete files that reference them, so a call can exceed `maxFiles`.
- **Doris** (same snapshot): PASS. Java 25 319, Java 17 276 and build logic 8 tests, zero failures;
  six focused trial tests per JVM cover paged expiry, committed-expiry recovery, retained history,
  table-scoped cleanup and duplicate/missing S3 deletion. Same two requests as Trino, plus:
  `ExpireSnapshotsResult.copy(int,int)` threw `NoSuchMethodError` for a client compiled against 0.14.

### Addressed after the trials (in 0.15.0)

- **Typed error:** if cleanup fails after the snapshot deletion committed, `expireSnapshots`
  throws `DucklakeExpiryCleanupIncompleteException` (`expiredSnapshotCount`,
  `scheduledFileCount`, cause). Do not retry the expiry; call `cleanUpExpiredObjects`.
- **Strict budget:** `maxCleanupFiles` / `maxFiles` is now a hard limit on files handled per
  call. A data file is removed together with the delete files that reference them only when all
  fit. If even one data file's delete files exceed the budget, those delete files go first (they
  are dead with it) and the data file follows on the next call.
- **Binary compatibility:** the 0.14 `copy(int,int)` and its Kotlin default-argument bridge link again.

## Known limits

- Dead view/macro/schema metadata GC still runs as one transaction with in-memory id lists
  (small tables in practice).
- Table-scoped cleanup never expires snapshots for one table only: snapshots are catalog-wide
  by DuckLake design.

## Pending rounds

- R4 PgBouncer compatibility, R2 table-level column stats (will change `getColumnStats`
  options; API shape reviewed before adoption), R5 commit statement count.

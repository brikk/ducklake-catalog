# Research: catalog optimizations at scale

Candidate optimizations that stay inside the DuckLake format but need design and
qualification before they are picked up. Not a work queue: nothing here is a
reproduced defect, and none of it is scheduled. Items move to
[BACKLOG.md](BACKLOG.md) once scoped.

Origin: PostHog's November 2026 write-up on leaving DuckLake at ~2 PB / ~1 T rows /
15.3 M snapshots ("Why we're re-rebuilding our data warehouse"). Of the catalog
problems it names, the two with cheap fixes are done — `dropTable` now deletes the
table's unversioned stats rows in the drop transaction, and
[recommended indexes](../docs/RECOMMENDED-INDEXES.md) exist with an
`ensureRecommendedIndexes()` entry point. Its remaining points are either engine
and protocol concerns outside this library (single-node sizing, S3 fetching, pgwire
client compatibility; our consumers are Trino and Doris) or the items below.

## What the format fixes and we cannot change

- One serial `snapshot_id` stream per catalog. Every commit races on
  `max(snapshot_id) + 1`; at very high concurrent commit rates retries are inherent.
  In-spec relief is shorter commit transactions (indexes), and more catalogs per
  tenant or domain. Catalog-level sharding is not a library feature.
- `ducklake_snapshot_changes` is retained after expiry (spec 1.1 text, upstream
  duckdb/ducklake#1472). Our conflict matrix reads only `(start, current]`, so this
  costs storage, not commit latency. No pruning.
- No primary or unique keys on the stats tables, `ducklake_table` or
  `ducklake_column`. Adding constraints could fail another client's otherwise valid
  write, so upserts stay probe-then-`UPDATE`; only non-unique indexes are used.

## R1. Bound the dead-object scans in `expireSnapshots` — done (0.15.0-SNAPSHOT)

Implemented as owner-chosen option A. `expireSnapshots` deletes the snapshot rows in one
short transaction. Cleanup then runs in keyset pages of 1,000 ids, one transaction each,
with paths copied by `INSERT … SELECT` (never fetched). The new
`expireSnapshots(ids, maxCleanupFiles)` and resumable `cleanUpExpiredObjects(maxFiles)` report
`ExpireSnapshotsResult.cleanupComplete`. This relies on deadness being monotonic for retired
rows. View/macro/schema metadata GC still runs as one transaction with in-memory id lists;
those tables are small in practice. Original analysis follows.


`findDeadTableIds`, `findDeadDataFiles`, `findDeadDeleteFiles` and the view/macro
sweep run a full-table correlated `NOT EXISTS` against `ducklake_snapshot` and build
the resulting id lists in memory (`JdbcDucklakeCatalog.kt`, `expireSnapshots` and
helpers). At tens of millions of files and snapshots this is the component that
PostHog describes breaking. [BACKLOG.md](BACKLOG.md) already records orphan
listings as unbounded.

Direction: a row can only become dead when an expiry removes the last surviving
snapshot in its `[begin_snapshot, end_snapshot)` range, so candidates are bounded by
`end_snapshot IS NOT NULL AND end_snapshot <= max(expired) + 1` plus the dead-table
ids. Page candidates by id with a traversal budget (the AGENT-GUIDE rule: never
fetch unbounded and limit in memory), schedule and delete per page inside the same
transaction, and keep the half-open survivor test as the correctness oracle.
Qualification needs the existing concurrency and interop expiry tests plus a
large-synthetic-catalog check that no live file is scheduled and no dead file leaks.

## R2. Serve current-snapshot column statistics from `ducklake_table_column_stats`

`getColumnStats(tableId, snapshotId)` re-aggregates `ducklake_file_column_stats` on
every call (O(files × columns) per planning), while `ducklake_table_column_stats`
holds the O(columns) answer that every writer, including this library, already
maintains. The 1.1 spec's "Snapshot Validity" note on that table permits a reader
to use it when the rows were read from a consistent view in which that snapshot
was the latest — exactly a `readSession` on the current snapshot.

Constraints: the table-level rows are gross bounds that may include deleted values,
carry 1.1 exactness flags this library does not read, and are deleted when a column's
bounds become unreliable (then only ANALYZE or a rewrite restores them). The current
API contract documents exact missing-row semantics
([`DucklakeCatalog.getColumnStats`](../ducklake-catalog/src/dev/brikk/ducklake/catalog/DucklakeCatalog.kt)).
A change needs either a new entry point or an explicit fallback (missing row, time
travel, or not in a read session → file aggregation), contract-test review with both
plugins, and a check of what Trino and Doris do with wider bounds.

## R3. Per-table expiry and cleanup scope — done (0.15.0-SNAPSHOT)

Snapshots stay catalog-wide; cleanup is table-scoped. `cleanUpExpiredObjects(tableId, maxFiles)`
schedules only that table's dead files (and its metadata if the table is fully expired).
`listTablesWithExpiredFiles(afterTableId, limit)` pages through tables needing cleanup.
Consumer guidance: [CONSUMER-NOTES-0.15.md](CONSUMER-NOTES-0.15.md). Original note follows.


`expireSnapshots` is a whole-catalog sweep by design (upstream expires at catalog
level too). With one catalog per tenant that is fine; with many tables per catalog a
table-scoped variant that only schedules a given table's dead files would let
maintenance run incrementally. Depends on R1's bounded candidate selection.

## R4. PgBouncer / proxy compatibility of the metadata pool

Shared PostgreSQL servers put a proxy in transaction-pooling mode between the
library and the database. Suspects that break there: `setTransactionIsolation`
issued session-wide by the JDBC driver in `readSession`, the `intervalstyle` session
setting in 1.1 read sessions, `FOR SHARE` reads outside an explicit transaction, and
HikariCP's own `connectionInitSql` on the Quack route. Investigation first: run the
Postgres suite through PgBouncer in transaction mode and list what fails. The pool
knobs added for this (`minIdleCatalogConnections`, idle timeout, max lifetime) cover
the connection-count side only.

## R5. Commit-path statement count

A plain insert commit issues roughly a dozen statements (lineage read, metadata
version check, stats probe, per-fragment inserts, stats update, snapshot and change
rows). Each is a round trip to a possibly remote metadata database. Batching the
per-fragment inserts into multi-row statements and folding the stats probe into the
update (`UPDATE … RETURNING` on PostgreSQL) would cut latency without changing what is
written. Measure first against an indexed catalog; the win may be small compared with
R1 and R2.

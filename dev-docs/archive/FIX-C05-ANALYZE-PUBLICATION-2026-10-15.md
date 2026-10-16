# C05 — coordinate ANALYZE publication with catalog writers

> Fix evidence retained for audit. Consumer behavior is summarized in
> [the snapshot upgrade notes](../../docs/SNAPSHOT-UPGRADE.md).

## Reproduction

Two deterministic PostgreSQL regressions reproduced wrong native query results
with the previous implementation. ANALYZE paused after reading the old file
counts, before taking statistics write locks. A competing JVM or native DuckDB
1.5.6 writer committed a real Parquet row containing `id=1000`. ANALYZE resumed
against its old snapshot and published count 2 and bounds 1–2 for three live rows.
A **fresh native DuckDB connection** then returned **no rows** for `WHERE id=1000`.
This was not just a stale estimate; the optimizer eliminated a committed value.

The regression uses latches, bounded workers and independent expected values,
not timing sleeps or hand-inserted losing metadata. Native and JVM writers both
failed before the correction.

## Correction and intentional compatibility change

ANALYZE now uses the same snapshot allocation, lineage checks, rollback/recompute
and bounded retry protocol as catalog data/DDL writes. If a competing commit wins,
the stale statistics transaction is rolled back in full and the next attempt
recomputes from the newer snapshot. If ANALYZE wins first, a native transaction
that prepared absolute statistics against the old head must retry and reload them.

**Successful ANALYZE now creates one metadata-only snapshot.** This replaces the
previous no-snapshot behavior. It preserves:

- Data file registrations and row identities;
- schema version and per-table schema history;
- `next_catalog_id`, `next_file_id` and the table's existing row-ID high-water mark;
- existing method/record signatures, wire DTOs and supported write formats.

The new snapshot has a standard `ducklake_snapshot_changes` row whose
`changes_made` is the empty string. No invented stats token, fake insert, ALTER or
compaction event is used. The empty row keeps the commit ledger complete for
other protocol implementations; native `snapshots()` returns an empty changes
map and native table-change queries return no data changes for this snapshot.
Global statistics are still mutable side tables, not versioned historical stats.

Consumers must stop assuming ANALYZE leaves the head unchanged. Snapshot listings
can contain these empty-change metadata commits. Ordinary conflict exhaustion
and the COMMIT-outcome contract apply; uncertain completion must not be blindly
retried. Existing snapshot-expiry/conflict-history retention rules are unchanged.

Backend-only locks or repeatable-read isolation alone are not used as a substitute:
an external writer can already have cached its absolute statistics before ANALYZE.
The standard durable snapshot claim forces that writer to rebase too. A native
transaction explicitly opened and staged before ANALYZE verifies this behavior.

## Connection ownership and bounded reads

Snapshot-collision handling now rolls back **before** collecting diagnostics on
the same owned connection. It no longer borrows a second pool connection while
holding an aborted transaction's connection. This fixes the remaining C14
diagnostic path needed for one-connection ANALYZE retries. A diagnostic failure is
suppressed onto the original conflict, not substituted for it.

Statistics recomputation uses one aggregate row for file counts/sizes, 256-row
keyset pages for inline versions (65,536-version traversal budget), and 1,024-row
file/column pages with one SQL-bounded lookahead row (4,194,304-row traversal
budget). Duplicate file/column keys are rejected, including at a page boundary.
No paths are transferred by these scans. Budget/error failures roll back the
unpublished statistics rather than returning a partial aggregate.

## Coverage

- Native and JVM PostgreSQL appends while ANALYZE is paused before publication:
  new bounds/counts survive and the filtered native query returns 1000.
- JVM append races on all five routes: PostgreSQL, MySQL, local DuckDB, native
  Quack and Java Quack, with one-connection pools and retained real Parquet files.
- A snapshot PK collision after statistics publication, followed by retry without
  a second pool borrow.
- Two simultaneous ANALYZEs, dropped-target retry rejection, schema/allocator/data
  invariants, an empty native changes map and no native CDC rows for the commit.
- An older open native transaction with cached drifted stats: ANALYZE repairs them,
  then native COMMIT rebases and retains the repaired count/bounds plus its new row.
- Multi-page statistics traversal with 2,050 additional synthetic file/stat rows;
  existing mixed inline/file, unknown/missing statistics and pinned-session tests.

## Verification and limits

Final forced verification passed:

```sh
mise exec -- ./gradlew check --rerun-tasks -Dducklake.corpus.dirs=all --no-configuration-cache
```

- **589 JUnit tests** (498 catalog, 51 SLT, 40 replay), zero failures/errors/skipped
  JUnit tests. This includes 13 new C05 regression cases across the five routes.
- **9,697 corpus records** passed, zero failed; existing 31 file and 12 record
  skips remain (484 discovered files, 453 run).
- Detekt and Java 17 checkAbi passed; frozen consumer/wire controls still pass.
- The original two filtered-read regressions failed before the correction and pass
  after it. A page-boundary duplicate rolls back both computed statistics and the
  prospective ANALYZE snapshot; successful multi-page reads retain complete counts.

The separate strict native lineage/BLOB/inline-schema qualification failures remain open; this is not
a native engine patch or DuckDB 2.0 / DuckLake 1.1 certification. No dependencies,
corpus/preview pins, public DTOs or sibling implementations are changed. 1.1 writes
remain disabled. Published 0.12.0 and earlier snapshots do not acquire this fix
until a subsequent publication.

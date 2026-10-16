# Snapshot expiration must preserve conflict history — fixed 2026-10-09

> Historical fix evidence. The retained-history contract remains in force; this
> report is not an open implementation task.

Tracked as **UP2-09** in the [2.0 readiness assessment](UPSTREAM-DUCKDB-2.0-2026-10-08.md).
This applies to our existing 1.5.5 lane, not only DuckDB 2.0.

Upstream reference: [duckdb/ducklake#1472](https://github.com/duckdb/ducklake/issues/1472),
fixed upstream by [d49146c6](https://github.com/duckdb/ducklake/commit/d49146c668b7b0da92070161e434b9af8f0f0171).

## Root cause and fix

`expireSnapshots` deleted matching `ducklake_snapshot_changes` rows along with
`ducklake_snapshot`. Those records are not merely a time-travel listing: native
open transactions and our JVM retry loop consult them to reject intervening
conflicting changes. Removing them allowed a stale writer to miss a compaction,
ALTER, or other conflict even though the current snapshot had advanced.

The fix removes only the conflict-record deletion. Snapshot deletion, dead-file
scheduling, dead-object cleanup and post-commit physical inline-table drops remain
unchanged. Conflict consumers already read snapshot changes by ID range without
joining the surviving snapshots, so they now see the retained records correctly.

We deliberately **do not prune conflict history by age or the oldest surviving
snapshot**. Neither bounds the lifetime of an open/retrying writer in another
engine. Safe future pruning would require a shared active-writer watermark or
an equivalent protocol; this patch does not invent one. History storage can grow.

## Behavior and rollout

- No method signatures or catalog schema change.
- `getSnapshot` and `listSnapshots` still omit expired snapshots.
- `listSnapshotChanges` now retains and exposes their change records. A consumer
  must not assume every returned change has a corresponding retained snapshot.
  Keeping a conflict record does not restore time-travel availability.
- Non-conflicting writers still retry and commit; retained history is not a
  blanket rejection of every transaction spanning expiration.
- Previously deleted history cannot be reconstructed by this fix. Drain old
  in-flight writers during rollout, and ensure other engines' expiration paths
  also retain history. An older native expiry implementation can still delete
  records independently of this JVM client.

## Regression evidence

The initial four-test run failed before the production fix:

- Two real DuckDB/PostgreSQL transactions read files before native adjacent-file
  compaction. JVM expiration then removed the intervening insertion/compaction
  snapshots. Both positional and whole-file stale DELETE COMMITs **succeeded
  instead of reporting a conflict**.
- A latch-parked JVM ALTER retried after a conflicting ALTER's snapshot was expired
  and committed instead of throwing its non-retryable logical conflict.
- The compatible-writer control still committed, but failed its assertion that
  the expired snapshot's conflict record survived.

After the fix the native transactions fail COMMIT with a conflict, leave live rows
unchanged and publish no new snapshot/delete file; the conflicting JVM ALTER
fails on attempt two and its column is rolled back. The compatible writer succeeds.

Seven new tests cover the above, repeated/empty expiry and latest-snapshot rejection,
plus direct retention on local DuckDB and MySQL. Existing Quack expiration and
PostgreSQL dropped-table GC tests now also assert retained history. Together these
verify retention on all four supported metadata backends without disabling GC.

## Verification

```
mise exec -- ./gradlew check --rerun-tasks \
  -Dducklake.corpus.dirs=all --no-configuration-cache
```

- **491 JUnit tests passed**: 401 catalog, 51 SLT, 39 replay; zero failures/errors/skips.
- Full oracle corpus: 480 files discovered, 449 run, **9,424 records passed**, zero
  failures. Existing 31 file skips and 12 record skips remain harness/environment gaps.
- Detekt and Java 17 `checkAbi` passed; 7,720 runtime classes scanned.
- No dependency upgrade, submodule change or sibling-repository modification.

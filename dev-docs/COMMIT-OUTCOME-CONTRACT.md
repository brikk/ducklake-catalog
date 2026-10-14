# Catalog COMMIT outcomes and producer cleanup

This contract accompanies the 0.13.0-SNAPSHOT follow-up. It does not add an
idempotency key, ownership lease, automatic reconciliation or filesystem cleanup.

## Caller decisions

| Result | Meaning | Retry / output handling |
|---|---|---|
| Normal return | The backend acknowledged the catalog commit | Keep its registered output. |
| `TransactionConflictException` | The attempt was rejected under the catalog conflict protocol | Existing retryability rules apply; replan stale payloads when required. |
| `DucklakeCommitOutcomeUnknownException` | COMMIT completion could not be established; it may have applied | **Do not blindly retry or delete output.** Retain/quarantine this attempt's objects and reconcile on a fresh connection. |
| `DucklakePostCommitException` | Backend COMMIT was acknowledged, but connection finalization failed | **Do not retry or delete its output.** Report completion/finalization failure separately and use a fresh connection. |
| Other/pre-COMMIT exception | No new blanket commit or ownership guarantee | Establish rollback/publication/ownership before any cleanup; a generic exception is not permission to unlink an imported/shared object. |

Unknown/post-commit exceptions extend `DucklakeException`, **not**
`TransactionConflictException`. The catalog's optimistic retry loop does not catch
them as conflicts. Consumers must retain this classification when translating
errors into engine/coordinator/worker error types; old catch-all cleanup remains
unsafe even though the superclass is compatible.

`attemptedSnapshotId` is nullable (snapshotless maintenance has none). It is
**diagnostic, not an idempotency or ownership token**: a different writer can claim
the same candidate ID. Neither that ID nor the latest head alone proves that this
payload committed. Reconciliation must inspect the actual registration, table/
field identity, paths and operation intent using a fresh connection. Fail closed
when proof is insufficient. No automatic reconciliation is implemented here.

## Phase-sensitive classification

The wrapper sits at every catalog backend COMMIT call: snapshot-producing writes,
ANALYZE, expiration and guarded cleanup acknowledgements. Ordinary statement and
preflight failures retain their existing handling.

- Explicit serialization/deadlock/constraint rejections retain their original SQL
  exception for existing conflict classification. PostgreSQL states `40001`,
  `40P01`, `23505` and MySQL's explicit deadlock rejection are recognized.
- Transport states, timeouts and `40003` (completion unknown) are not rollback
  proof, regardless of conflict words or nested causes.
- Maintained DuckDB/native Quack sometimes lack SQLState. Only their explicit
  transaction/duplicate-key SQL error envelope is recognized as a rejection;
  arbitrary "conflict" text is not sufficient. This must be requalified on a
  driver/protocol upgrade, not extended by broad substring guessing.
- Native Quack first commits remotely, then ends its empty local JDBC transaction.
  Failure of that second phase is post-commit, even if its local error says 40001.
- Snapshot-write connection-close SQL failures after an acknowledged commit are
  post-commit too. A later rollback call does not undo a commit already applied.
- Cleanup/rollback failures must not replace the original commit failure.

This is not a declaration that all backend DDL is transactional: MySQL implicit
DDL commits and catalog initialization have their own boundaries. Do not interpret
a generic pre-COMMIT error as proof that arbitrary DDL/ownership effects vanished.

## Downstream handoff — no sibling implementation changes here

Before deploying consumers against this behavior:

1. Preserve unknown versus acknowledged-post-commit status through exception mapping.
2. Ensure abort/finally paths for INSERT, DELETE, MERGE, add-files, rewrite and flush
   do not unlink output or resubmit the same write for either status.
3. Give uncertain attempts a recovery/quarantine path; do not just mark them ordinary
   failed attempts whose paths can immediately be swept as orphans.
4. Restrict definite-failure cleanup to this attempt's privately created outputs,
   never arbitrary imported/shared paths. Keep age/reference/lease protections.
5. Handle uncertain maintenance acknowledgements too: the queue/snapshot mutation
   might already have applied. Never use failure alone as proof it did not.

Tests inject lost replies **after real backend commits** on PostgreSQL, MySQL,
local DuckDB, native Quack and Java Quack. They verify one publication, no automatic
retry, a retained actual Parquet output, snapshotless expiry and confirmed-commit
finalization failure. Pre-COMMIT injection verifies rollback without publication.
These are deterministic boundary tests, not a claim of complete network-partition
or remote storage recovery qualification. Trino/Doris cleanup implementations
remain their agents' responsibility and were not edited.

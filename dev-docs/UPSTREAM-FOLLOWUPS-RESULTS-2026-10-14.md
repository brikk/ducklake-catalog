# Upstream follow-up execution — 2026-10-14

Production pins and 1.1 write guards remain unchanged. Each verified step is
committed independently; native qualification failures are not waived by JVM tests.

## 1. Old inline schemas, expiration and flushes

Four regressions reproduced failures in the original JVM implementation using
real native DuckDB/JDBC 1.5.6 PostgreSQL catalogs, not edited registry rows:
lazy inlining after ALTER, lower inline versions inside multi-table commits,
expiry of boundary evidence, and silently empty reads after external expiry.

Resolution now follows the table's preceding schema change. Old inline versions
below their committing schema are distinguished using the immediately preceding
snapshot. An exact global-version snapshot can prove an older boundary when
available. Missing evidence is not an excuse to guess a column tree.

Before our expiry removes that evidence, a bounded, keyset-paged preflight records
resolvable nonempty inline aliases in the existing `ducklake_schema_versions`
ledger, inside the owned expiry transaction. It uses 256-row pages, a 65,536-entry
traversal budget and identifier/version projections only; no file paths or row
payloads are transferred. No alias writes happen until preflight completes.
There is no new format, automatic migration or snapshot. Repeated expiry does not
duplicate aliases. If an external cleaner already erased an ambiguous boundary,
nonempty reads/change feeds and our expiry fail explicitly, preserving the data
instead of returning empty results or NULL substitutions.

Additional coverage verifies rename/field identity, two physical inline versions,
pinned one-connection reads, JVM flush to real Parquet and native read-back with
the original row ID. Alias preservation/repeated expiry runs on all five routes:
PostgreSQL, MySQL, local DuckDB, native Quack and Java Quack.

Verification: `mise exec -- ./gradlew check -Dducklake.corpus.dirs=all
--no-configuration-cache` passed **543 JUnit tests** (452 catalog, 51 SLT, 40 replay),
zero failures/errors/skips, detekt and Java 17 checkAbi. The selected full corpus
retains its verified **9,697 records**, zero failures, with existing harness skips.

**Native defect remains:** the strict disposable
`dev-docs/probes/ducklake_old_inline_schema.py` exits 1 on maintained Python 1.5.6,
core `069cc9f9b5`, DuckLake `ac7595b0`. Physical inline `(1,11)` becomes native
`(1,NULL)` after unrelated DDL, and native flush persists that NULL. This is
#1590's native read/flush failure, not repaired by this JVM change. Do not use
native output as its golden or recommend automatic migration as a workaround.

The public schema-resolution signature is unchanged; its documented boundary is
now table-scoped instead of an unrelated global-version snapshot. Other public
records, wire formats and supported write versions are unchanged.

Commit: `b89d870`.

## 2. COMMIT outcome classification and cleanup contract

Lost-reply injection after real backend commits reproduced ten failing cases
across the five catalog routes. The old code either wrapped the failure generically
or retried a committed write when transport error text contained "conflict".

All catalog COMMIT boundaries now classify completion before normal conflict
handling. `DucklakeCommitOutcomeUnknownException` is not retryable and tells the
producer to retain output/reconcile; its nullable attempted snapshot ID is only
diagnostic. Explicit backend rollback/rejection signals keep existing retry rules.
Transport states, timeouts and completion-unknown signals cannot be overridden by
message text. Confirmed commits with connection-finalization failures use a
separate non-retryable `DucklakePostCommitException`, including native Quack's
remote-commit / empty-local-transaction boundary.

Tests cover real Parquet output and one publication after a lost reply, pre-COMMIT
rollback, snapshotless expiry, acknowledged commit followed by finalization failure,
native Quack local 40001 after remote success, and policy/normal conflict retries.
All five routes are exercised. These are deterministic boundary injections, not
full network-partition qualification.

Full `check -Dducklake.corpus.dirs=all --no-configuration-cache` passed **574 JUnit
tests** (483 catalog, 51 SLT, 40 replay), zero failures/errors/skips, detekt and Java
17 checkAbi; selected full corpus remains at **9,697 records**, zero failures.
Existing record constructors/components and wire goldens still pass. The new
exceptions are additive public API; no DTO or method signature changed.

**Downstream adoption is still required:** [the cleanup/error contract](COMMIT-OUTCOME-CONTRACT.md)
specifies exception mapping, quarantine/reconciliation, no blind republishing and
no catch-all unlink. This repository doesn't perform producer filesystem cleanup
or implement idempotency. Trino/Doris agents must adopt the contract before
deployment; their repositories were not changed. A generic pre-COMMIT error is
not an ownership guarantee, especially for shared/imported files or implicit DDL.

Commit: `c586309`.

## 3. Lineage, statistics and BLOB native regression gates

Added `dev-docs/probes/ducklake_lineage_stats_blob.py` with six isolated scenarios:
sorted merges and updated-row merges (including history/change images/checkpoint),
deleted-inline false-exact statistics, a live-inline statistics control, escaped
plain/nested BLOB bytes with NULLs, and invalid UTF-8 BLOBs. Expected IDs, rows,
aggregates and bytes are literal independent expectations. Raw Parquet bytes are
checked after maintenance, not just the native logical scan.

Every SQL error is a failed check, not a skip. The strict probe **exits 1** on all
three sampled targets:

| Target | Core / DuckLake | Format | Observed failures |
|---|---|---|---|
| Maintained Python 1.5.6 | `069cc9f9b5` / `ac7595b0` | 1.0 | Sorted/updated merge IDs/history/change images, false MIN after flush, BLOB reinterpretation/persistence, invalid-UTF read/merge |
| Locked preview | `96063b9e39` / `e8924cfc9c` | Explicit 1.0 | Same; invalid-UTF checkpoint also fails |
| Locked preview | Same | Fresh 1.1-dev1 | Same as explicit 1.0 |

Concrete observations: retained live rows are 51–69, but after flushing a deleted
inline value the aggregate reports **MIN=4, MAX=69, COUNT=19** rather than MIN=51.
The live-inline control passes on all targets. Bytes `5c783431` (literal `\\x41`)
become `41` (`A`) in scalar/STRUCT/LIST columns with `binary_as_string=true`, and
maintenance persists those changed bytes into Parquet. NULL and plain-text
controls remain intact. Invalid UTF-8 errors are retained as failed checks, even
when a failed maintenance operation leaves the original bytes unchanged.

Six harness unit tests pass on maintained and preview packages: exact-match
control, changed ID, changed escape bytes, query errors, maintenance errors and
NULL/empty/zero-byte distinction. The preview workflow adds these **harness**
checks; it does not pretend the failing native engine gates pass. YAML and local
documentation links were verified. No production implementation or pin change
was made in this step. These failures remain release/RC qualification blockers,
not fixed native behavior, and are now included in the acceptance runbook/README.

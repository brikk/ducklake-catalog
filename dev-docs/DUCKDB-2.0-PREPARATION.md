# Safe DuckDB 2.0 preparation

Execution order requested on 2026-10-09. Keep the released dependency lane, do not
publish unsupported-format writes, and commit each verified step separately.

| Step | Scope | Status |
|---|---|---|
| 1 | Explicit geometry lambda syntax, with golden/mirror regression coverage | Verified, `1927995` |
| 2 | Independent pinned preview CI; preserve the released JVM lane | Verified locally, `a15dbf2`; GitHub run awaits push |
| 3 | Fresh version guards across mutations and concurrent migration | Verified, `b7e1269` |
| 4 | Version-aware inline metadata-column names | Verified, `494491f` |
| 5 | Matched Quack peers and transaction-recovery qualification | Safe preparation verified; full 2.0 JVM qualification remains gated |

See [the compatibility assessment](UPSTREAM-DUCKDB-2.0-2026-10-08.md) for the full
risks and upstream provenance. No step alone certifies DuckDB 2.0 or DuckLake 1.1.

## Step 1 verification

The new strict-syntax regression failed before the fix with the deprecated-arrow
binder error. LIST, fixed ARRAY, MAP keys/values and nested containers now normalize
both WKB and WKT with legacy arrow syntax disabled. All existing golden/mirror and
negative geometry controls still pass. Full `check -Dducklake.corpus.dirs=all` passed
492 JUnit tests and 9,424 corpus records, plus detekt and Java 17 checkAbi.

## Step 2 verification and limits

The native preview workflow pins the Python/core/extension builds and corpus SHA;
it tests both fresh 1.1-dev1 and explicit/retained 1.0 layouts on PostgreSQL and
local DuckDB. Both backend jobs were reproduced locally with all 12 canaries and
provenance checks passing. The maintained JVM CI/dependency/corpus pins are unchanged.

This workflow is **not a full JVM 2.0 qualification lane**: no released 2.0 JDBC
artifact was available in the checked Central metadata. The GitHub execution itself
has not been run because these commits have not been pushed. Update the lock file
deliberately; nightly runs must not silently install a different preview.

## Step 3 scope

Mutations read the version on their owned connection on every transaction attempt;
there is no instance-level cache. File registration additionally checks encryption
again on that connection. DDL, stats, expiry and cleanup acknowledgements reject
unsupported versions too. PG/MySQL use shared locking reads for metadata guard rows;
local DuckDB/Quack still require externally coordinated migrations (no FOR SHARE).

Selected cleanup acknowledgements now run their bounded chunks in one guarded
transaction, rather than autocommitting each chunk. This strengthens atomicity;
batch/path limits and exact tuple matching remain unchanged. No 1.1 writes are enabled.

Three migration/unsupported-mutation regressions failed before the fix and now
pass. Full checks passed 495 JUnit tests (404 catalog, 51 SLT, 40 replay), 9,424
corpus records, detekt and Java 17 checkAbi. Four-backend cleanup/transaction tests
also passed. This prevents unsupported writes, not arbitrary online DDL migration.

## Step 4 scope

A shared descriptor selects exact known legacy versus 1.1-dev1 metadata names.
Inline data visibility, ordering, row IDs, change-feed projections and maintenance
helpers use it with their owned/pinned context. Inlined file-delete tables keep
their distinct, unchanged names. DDL uses the same descriptor, but step 3 still
refuses all 1.1 writes. Unknown layouts fail explicitly instead of guessing.

The read regression uses real native-created PostgreSQL inline data, renames its
physical metadata columns to the upstream dev layout, and keeps the JVM instance
alive. A single-connection read session returns identical values/IDs/counts before
and after; an old-name user-like column is not mistaken for metadata. This is
layout-level coverage, not full 1.1 schema/type/nested-schema qualification.

Full checks passed 497 JUnit tests (406 catalog, 51 SLT, 40 replay), the full
corpus's 9,424 records, detekt and Java 17 checkAbi.

## Step 5 scope and qualification limits

The maintained Quack fixture now uses DuckDB CLI 1.5.5 to match JDBC 1.5.5.0.
The native preview lane runs separate client/server processes from the same locked
2.0 package and checks both peers' core/Quack hashes. It verifies rollback of an
uncommitted write, error-invalidated explicit transactions, rollback recovery,
subsequent committed writes and detach, with bounded process startup/teardown.

A new maintained-lane regression reproduced an already-poisoned PostgreSQL read
session after expected missing inline tables. Read paths now use non-failing,
namespace-scoped existence probes first; unexpected errors in a pinned session
escape rather than being swallowed. Actual connection errors are still errors.

These are safe preparation gates, **not full JVM 2.0/Quack-v3 qualification**.
No 2.0 JDBC artifact was introduced, and heartbeat leases, network partitions,
mixed-version rejection, cancellation under load, storage rollback, and full 1.1
stats/types/nested-schema support remain release gates. Preview GitHub execution
awaits pushing; the native probes are verified locally.

Final full forced checks, followed by another full check after the one-connection
write-preflight assertion, passed **499 JUnit tests** (408 catalog, 51 SLT, 40 replay),
9,424 corpus records, detekt and Java 17 checkAbi. The maintained peer-version test
confirms both native peers report v1.5.5. Both preview layout backends and matched
native preview Quack provenance/recovery probes passed locally.

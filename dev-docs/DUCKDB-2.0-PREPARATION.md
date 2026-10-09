# Safe DuckDB 2.0 preparation

Execution order requested on 2026-10-09. Keep the released dependency lane, do not
publish unsupported-format writes, and commit each verified step separately.

| Step | Scope | Status |
|---|---|---|
| 1 | Explicit geometry lambda syntax, with golden/mirror regression coverage | Verified, `1927995` |
| 2 | Independent pinned preview CI; preserve the released JVM lane | Verified locally; workflow prepared |
| 3 | Fresh version guards across mutations and concurrent migration | Pending |
| 4 | Version-aware inline metadata-column names | Pending |
| 5 | Matched Quack peers and transaction-recovery qualification | Pending |

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

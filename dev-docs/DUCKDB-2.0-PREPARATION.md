# Safe DuckDB 2.0 preparation

Execution order requested on 2026-10-09. Keep the released dependency lane, do not
publish unsupported-format writes, and commit each verified step separately.

| Step | Scope | Status |
|---|---|---|
| 1 | Explicit geometry lambda syntax, with golden/mirror regression coverage | Verified; committed separately |
| 2 | Independent pinned preview CI; preserve the released JVM lane | Pending |
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

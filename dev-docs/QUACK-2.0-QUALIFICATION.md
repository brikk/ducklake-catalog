# Direct Java Quack on staged DuckDB 2.0 — October 20

**Historical staged baseline.** The maintained lane has since moved to official 2.0;
see [shipping qualification](UPGRADE-DUCKDB-2.0.0.md). To reproduce this exact staged
target, add `-Pducklake.quack.target=alpha49096` to the command below.

This records the transport baseline in `ff2dc27`, before opt-in final-1.1 reads.
The subsequent [read tier](../docs/READING-1.1.md) and
[read verification](FINAL-1.1-READ-RESULTS.md) extend that baseline without enabling writes.

**Direct Java transport works on the tested v3 peer. This is not full DuckLake 1.1
support or final DuckDB 2.0 JDBC certification.** No runtime dependencies, supported
write formats, corpus pins or existing native-preview locks were changed.

## Exact target, not branch tip

| Component | Executed target |
|---|---|
| CLI | `v2.0.0-alpha49096`, core `2ec7e8401c` |
| CLI SHA-256 | `9e433f0b330b043db53db5f85620afba766c3d9630b2d80e71aa0d127fc9aed1` |
| Signed core Quack extension | `e7a9be3f8c` |
| Signed core DuckLake extension | `417a9117f5` |
| Java driver | Maven Central `dev.brikk.duckdb:quack-jdbc:0.8.0` |
| Driver SHA-256 | `320ccd96e978e1b30ff7ba77791dd0062bc6e72bd6d160e389b9e2c52269ac9d` |
| Negotiated Quack protocol | **3**, asserted on the actual driver session |
| Catalogs | Separate native-created **1.0** and **1.1** catalogs |

The DuckDB release branch's newer Quack `f96420ed` is **not** the extension shipped
for this staged CLI. It is not qualified by these results. Signature verification
stays enabled; no unsigned extensions, local source substitutes or unsafe crypto
options are used. Extensions install into fixture-owned temporary directories.

## Reproduce

```sh
mise exec -- ./gradlew :ducklake-catalog:quackPreviewTest \
  -Pducklake.preview.cli=/absolute/path/to/alpha49096/duckdb \
  --no-configuration-cache --no-daemon
```

The task is deliberately opt-in and independent of normal `check`. Missing CLI
configuration fails instead of silently skipping. CLI/core/extension identities,
driver version/JAR digest and negotiated v3 are asserted; a different staged build
requires deliberate fixture requalification. It has its own runtime classpath:
**DuckDB JDBC is absent**, with a class-loading negative assertion to prove it.
The JVM uses only the published Java driver for metadata. Matching native CLI
processes bootstrap catalogs, write real Parquet and independently read data/CDC.

Results: `ducklake-catalog/build/reports/tests/quackPreviewTest/` and
`ducklake-catalog/build/reports/quack-preview/` (provenance and server logs).
Child commands have timeouts; readiness/teardown are bounded, and owned processes
and disposable catalogs/extensions are removed even on setup failure.

## Covered behavior

**14 tests**, no skipped tests:

- Native-created scalar and JDBC STRUCT/LIST/MAP inline values, NULLs and absolute IDs.
- Real Parquet registration, positional delete and embedded-ID rewrite; fresh native
  scans, time travel and delete CDC agree with independent expected rows/IDs.
- DDL rollback before publication and subsequent pool reuse.
- Concurrent snapshot/allocator collision and retry with a one-connection pool.
- ANALYZE versus a competing append, with native filtered-query validation afterward.
- Lost COMMIT reply and acknowledged-post-commit finalization injection after real
  backend commits: no automatic retry/republication and retained physical output.
- Pinned metadata sessions across native commits, timestamp resolution and inline
  CDC at visible snapshot boundaries, then visibility after session release.
- SQL-bounded deletion queue selection and exact acknowledgement, expiry with
  retained conflict history, and raw failed-transaction rollback/reuse.
- Native oracle JSON parsing retains the final query result, not an earlier setup
  success result; NULL, empty string and literal `NULL` remain distinct.
- Reachability of real final-1.1 metadata with the existing unsupported-read/write
  boundaries preserved rather than relabelling it as supported.

COMMIT injections are controlled boundary tests, not proof of arbitrary network
partition, cancellation, heartbeat/lease or server-crash recovery.

## 1.1 boundary at the transport baseline

At `ff2dc27`, basic 1.1 version/table/column metadata was reachable over the driver.
The library recognized the previously prepared `1.1-dev1` inline layout but not the
final format, so **final-1.1 inline reads failed explicitly**. CREATE,
ANALYZE and expiration against final 1.1 are rejected with the unsupported-version
exception before publication; native seed rows and head remain unchanged.

These checks validate safe rejection, not usable 1.1 catalog writes. Do not add
`1.1` to an allowlist merely because the transport works. Actual schema/type/
statistics APIs, migration/backend/wire qualification and connector adoption still
follow the [acceptance runbook](DUCKLAKE-1.1-ACCEPTANCE.md).

The maintained embedded JDBC/native-Quack routes still use DuckDB JDBC 1.5.6.0.
A shipping DuckDB 2.0 JDBC artifact is required to qualify those routes separately.
The opt-in suite ran on the build JDK; classfile checks are not a claim of a full
Java 17 runtime suite. No sibling repository implementation was changed.

## Verification

```sh
mise exec -- ./gradlew check :ducklake-catalog:quackPreviewTest --rerun-tasks \
  -Dducklake.corpus.dirs=all \
  -Pducklake.preview.cli=/absolute/path/to/alpha49096/duckdb \
  --no-configuration-cache --no-daemon
```

Passed **597 maintained JUnit tests** (506 catalog, 51 SLT, 40 replay) and the
**14 opt-in direct-v3 tests**, zero failures/errors/skipped JUnit tests. Full corpus
passed **9,697 records**, zero failed; existing 31 file/12 record skips remain.
Detekt includes the new source set; Java 17 checkAbi passed. The missing-CLI
negative invocation fails at the explicit configuration guard instead of skipping.
Neither the maintained server fixture nor production/preview/corpus pins changed.

No catalog runtime fix was needed for these tested operations. The fixture uses
the preview's `extension_directories` setting, avoiding the deprecated singular
setting's warning contaminating JSON output. Its oracle parser checks the final
JSON result after setup, and tests use visible snapshot IDs for pinned inline reads.
No expected row/ID was replaced with native output to obtain a passing result.

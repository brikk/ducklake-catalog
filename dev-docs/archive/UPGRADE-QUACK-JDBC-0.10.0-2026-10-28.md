# Quack JDBC 0.10.0 / embedded DuckDB quick scan — October 28, 2026

Owner request: upgrade the Java Quack driver, ensure embedded DuckDB is 2.0.0 and
do a quick scan before continuing with H05/H09/H08. Base: `dc8c091`.

**Complete: requested update and focused quick scan pass.** This is a driver-only update, not a full
engine/corpus requalification, publication or connector artifact adoption. The
earlier full harness run used Quack 0.8.0; its evidence is not relabelled.

## Versions and artifact scan

- Java Quack: **`dev.brikk.duckdb:quack-jdbc:0.10.0`**, replacing 0.8.0 in the shared
  version catalog. Runtime version assertions and the direct-Java JAR checksum pin
  are updated, not removed. Historical JSON wire samples and old reports stay intact.
- Published JAR SHA-256: `8088131ef67723a921b5fe50b22f7ba147485830b81cb78d3354ce6ae73f8342`,
  independently downloaded and checked against Maven Central's `.jar.sha256`.
  All **102 classes** target at most Java17 (major61). No native libraries or
  `org.duckdb` classes are bundled. The POM has no runtime dependencies; its 1.5.6
  DuckDB property is used only by an optional upstream test profile.
- Embedded JDBC remains **`org.duckdb:duckdb_jdbc:2.0.0.0`**. Its independently
  checked SHA-256 remains `5170daa90a43296705127ef3c80834ba1cd275ec9d765a9f7eee346ab0087186`.
  Actual Java17.0.2 `PRAGMA version` reports **`v2.0.0`, core `d54cbbe3df`**.
  The same smoke loads Quack 0.10 (major0/minor10) through JDBC service discovery.
- The maintained server Dockerfile/image and explicit qualification CLI are already
  **DuckDB2.0.0**. Signed DuckLake `417a9117f5`, native Quack `f96420ed46` and corpus
  pins are unchanged. Direct Java Quack is pure Java; it does not embed another DuckDB.
- The separate `legacyInlineProducer` **1.5.6.0** dependency intentionally remains
  isolated as a historical test-data producer, never a maintained runtime dependency.
- Gradle resolution confirms Quack0.10.0 on the no-DuckDB-JDBC direct runtime and
  replay test runtime, and DuckDB JDBC2.0.0.0 on replay runtime. No unexpected embedded
  downgrade is introduced by the Quack POM.

The [tagged driver changelog](https://github.com/brikk/duckdb-quack-jdbc/blob/v0.10.0/CHANGELOG.md)
records released2.0/v3 coverage in0.9 and `must_invalidate` session handling in0.10:
stop heartbeats, reject later requests locally, and do not reconnect/replay SQL.
Its upstream suite results are not claimed as rerun here. Catalog COMMIT outcome
classification, write guards and automatic-migration policy are unchanged; this
scan is not universal transport-failure or pool-recovery certification.

## Focused verification

- **37 focused catalog tests pass**: nine per local/native-Quack/Java-Quack route,
  six direct-driver catalog tests and four unchanged metadata/wire tests.
- All **86 direct-Java tests pass** against the explicit official2.0 CLI, with no
  embedded DuckDB JDBC on that test JVM's runtime classpath. All 17 current fixture
  provenance files attest driver0.10.0, the new JAR SHA, protocol3 and core `d54cbbe3df`.
- Zero JUnit failures/errors/skips. Catalog lint and Java17 ABI checks pass
  (7,843 classes / 17 artifacts); replay compilation is unaffected/up-to-date.
- Independently scanned all102 driver classes at Java17-or-lower bytecode, and
  ran the JDBC service-discovery / embedded-core smoke on actual Java17.0.2.
- Focused Gradle run: **2m34s**. The six changed pin/assertion file hashes remained
  unchanged through the run; production implementation, server, corpus and native
  extension pins are unchanged. Version assertions and checksums were updated to
  the published artifact, not weakened or replaced with wildcard expectations.

No full `check` or corpus replay is claimed for this dependency change, as requested
for this quick scan. Earlier full qualification used0.8.0 and remains historical.
No H05/H09/H08 repair has started, and no separate native gate has been rerun or waived.

```sh
GRADLE_USER_HOME=/tmp/opencode/catalog-r07-gradle-isolated mise exec -- ./gradlew \
  :ducklake-catalog:test --tests '*TestJdbcDucklakeCatalogDirectQuack*' \
  --tests '*TestFinal11DuckDbBackends*' --tests '*TestReadMetadataWireFormat*' \
  :ducklake-catalog:quackPreviewTest :ducklake-catalog:checkAbi :ducklake-catalog:detekt \
  :ducklake-test-corpus-replay:compileKotlin \
  -Pducklake.preview.cli=/tmp/opencode/catalog-final11-r01-cli/duckdb \
  --no-daemon --no-configuration-cache
```

Raw evidence: `/tmp/opencode/catalog-quack010-dependencies.log`,
`catalog-quack010-focused.log` and `catalog-quack010-scan/` (artifact scan, Java17
runtime source/output, scoped hashes and provenance), plus
`catalog-quack010-focused-xml/summary.json`. No push or broader adoption is implied.

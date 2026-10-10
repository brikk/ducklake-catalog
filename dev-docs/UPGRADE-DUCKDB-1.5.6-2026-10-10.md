# DuckDB 1.5.6 and Java Quack JDBC 0.8.0

## Artifacts and scope

- DuckDB JDBC: `org.duckdb:duckdb_jdbc:1.5.6.0`.
- Test-server CLI: released `v1.5.6`, matched to the JDBC client.
- Corpus: `ac7595b0a1305bea3d4cfaca763b0ce964c763a2`, the DuckLake extension
  [pinned by DuckDB v1.5.6](https://github.com/duckdb/duckdb/blob/v1.5.6/.github/config/extensions/ducklake.cmake).
- The signed release Quack extension pin is
  `7e80f7ffcc98d0b3e81d0e1df8cc1c2da240a64b`, still protocol v1—not v3 mainline.
- Java driver: **`dev.brikk.duckdb:quack-jdbc:0.8.0`**, resolved from Maven Central.
  Its downloaded release JAR SHA-256 is
  `320ccd96e978e1b30ff7ba77791dd0062bc6e72bd6d160e389b9e2c52269ac9d`.

While the release was publishing, the full suite passed with
`0.8.0-SNAPSHOT` (`0.8.0-20261009.151226-1`). The release watcher then downloaded
the final Central artifact; verification was repeated with **0.8.0**. No snapshot
driver dependency or snapshot repository remains in the committed configuration.
The pure-Java driver is test-runtime-only here, consistent with the other optional
JDBC drivers: applications using it must add it to their runtime classpath.

## Compatibility fixes exposed by actual-driver testing

The existing synthetic `jdbc:duckdb:quack://` route remains unchanged. New direct
driver coverage uses `jdbc:quack://` against the same initialized metadata server.

Two catalog-side failures were reproduced and fixed:

1. jOOQ did not recognize `jdbc:quack:` and chose DEFAULT, producing an invalid
   `INSERT INTO ducklake_schema sch ...`. The catalog now explicitly infers DuckDB
   SQL for that URL without replacing the Java driver or wrapping its statements.
2. Native inline STRUCT values returned as standard `java.sql.Struct` were treated
   as text. The decoder now maps ordered attributes to the source-schema field
   names, recursively decodes children and NULLs, and rejects mismatched shapes.
   Existing DuckDB-specific named STRUCT decoding remains intact.

## New direct-driver coverage

- Native-created catalog and inline scalar/nested STRUCT/LIST/MAP reads.
- Real Parquet registration, positional deletes and a lineage-preserving rewrite,
  read back by native DuckDB with original row IDs retained.
- DDL, rollback, pool reuse and concurrent catalog-ID collision/retry.
- Driver version and released server version checks.
- All 18 bounded cleanup contract tests: exact raw tuple acknowledgement,
  collation independence, timestamp cutoff, SQL page/path bounds, hierarchy,
  malformed metadata, chunk limits and pinned read sessions.
- Generic JDBC STRUCT field-order/NULL and malformed-shape unit controls.

The preview workflow's corpus lock is updated to the maintained release corpus;
its native 2.0 package/core/extension pins are not changed. This is not final 2.0
or full JVM Quack-v3 certification. Nested-schema support and 1.1 writes stay gated.

## Verification

Final command:

```
mise exec -- ./gradlew check --rerun-tasks \
  -Dducklake.corpus.dirs=all --refresh-dependencies --no-configuration-cache
```

- **525 JUnit tests passed**: 434 catalog, 51 SLT, 40 replay; zero failures/errors/skips.
- Full release corpus: 484 files discovered, 453 run, **9,697 records passed**, zero
  failures. Existing 31 file skips and 12 record skips remain harness/environment gaps.
- Detekt and Java 17 `checkAbi` passed (7,720 main-runtime classes). The final Java
  driver JAR was independently scanned: 102 classes, maximum class major 61 (Java 17).
- The resolved Gradle release JAR matches the Central download SHA-256 above;
  JDBC metadata reports driver `0.8.0`, and native/direct server checks report `v1.5.6`.
- No public catalog method/record signatures changed. No sibling repositories were edited.

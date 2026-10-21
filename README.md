# ducklake-catalog

The shared **DuckLake catalog layer** for JVM engines — a JDBC/jOOQ client for the
`ducklake_*` metadata schema that DuckDB's `ducklake` extension writes to a SQL catalog
backend (Postgres, MySQL, DuckDB). It is the common dependency consumed by the
[Trino](https://github.com/brikk/trino-ducklake) and
[Doris](https://github.com/brikk/doris-ducklake) DuckLake connectors.

Published to **Maven Central** under the `dev.brikk.ducklake` group.

## Modules

| Artifact | Coordinates | Purpose |
| --- | --- | --- |
| Catalog | `dev.brikk.ducklake:ducklake-catalog` | Kotlin/jOOQ client for the DuckLake catalog metadata schema (main library). |
| Catalog test fixtures | `dev.brikk.ducklake:ducklake-catalog` (`test-fixtures` variant) | `TestingDucklake*CatalogServer` + testing helpers for downstream test suites. |
| Corpus replay | `dev.brikk.ducklake:ducklake-test-corpus-replay` | Engine-agnostic replay harness (DuckDB oracle, SLT parser/driver, `ReplayReadEngine` seam) for the upstream DuckLake sqllogictest corpus. Test-only; ships **no** corpus SQL. |
| SLT format | `dev.brikk.ducklake:slt-format` | Standalone, dependency-free (stdlib-only) parser, model, and expander for the DuckDB-dialect sqllogictest (`.test`) format. `SltParser` (skip-don't-throw), `SltModel`, and the pure `SltExpander` that flattens loops/foreach/conditionals/templates into concrete SQL. Consumed by corpus replay; usable on its own. |

`jooq-custom-naming` is a codegen-time-only helper and is **not** published.

## Using it as a dependency

Latest release: **0.12.0** (`main` is `0.13.0-SNAPSHOT`)

See [user documentation](docs/README.md) for compatibility, producer contracts and
[release notes](docs/releases/RELEASE-0.12.0.md). Consumers upgrading from before
0.10.0 must adopt its [nullable row-ID contract](docs/releases/RELEASE-0.10.0.md).

### Gradle (Kotlin DSL)

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("dev.brikk.ducklake:ducklake-catalog:0.12.0")

    // Optional: shared Testcontainers-based catalog fixtures for your tests
    testImplementation(testFixtures("dev.brikk.ducklake:ducklake-catalog:0.12.0"))

    // Optional: the DuckLake corpus replay harness (test-only)
    testImplementation("dev.brikk.ducklake:ducklake-test-corpus-replay:0.12.0")

    // Optional: the standalone SLT (.test) format parser/model/expander (dependency-free)
    implementation("dev.brikk.ducklake:slt-format:0.12.0")
}
```

> The `test-fixtures` variant is resolved via Gradle Module Metadata, so consuming it with
> `testFixtures(...)` requires Gradle (Maven consumers use the classifier directly, below).

### Gradle (Groovy DSL)

```groovy
dependencies {
    implementation 'dev.brikk.ducklake:ducklake-catalog:0.12.0'
    testImplementation testFixtures('dev.brikk.ducklake:ducklake-catalog:0.12.0')
    testImplementation 'dev.brikk.ducklake:ducklake-test-corpus-replay:0.12.0'
    implementation 'dev.brikk.ducklake:slt-format:0.12.0'
}
```

### Maven

```xml
<dependency>
  <groupId>dev.brikk.ducklake</groupId>
  <artifactId>ducklake-catalog</artifactId>
  <version>0.12.0</version>
</dependency>

<!-- Test fixtures (classifier) -->
<dependency>
  <groupId>dev.brikk.ducklake</groupId>
  <artifactId>ducklake-catalog</artifactId>
  <version>0.12.0</version>
  <classifier>test-fixtures</classifier>
  <scope>test</scope>
</dependency>

<!-- Standalone SLT (.test) format library -->
<dependency>
  <groupId>dev.brikk.ducklake</groupId>
  <artifactId>slt-format</artifactId>
  <version>0.12.0</version>
</dependency>
```

### Snapshots

Development snapshots are published from `main` to the Central Portal snapshots repo. Add:

```kotlin
repositories {
    mavenCentral()
    maven {
        url = uri("https://central.sonatype.com/repository/maven-snapshots/")
        mavenContent { snapshotsOnly() }
        content { includeGroup("dev.brikk.ducklake") }
    }
}
// then depend on e.g. dev.brikk.ducklake:ducklake-catalog:0.13.0-SNAPSHOT
```

## Bounded Cleanup API

Version `0.9.0` adds bounded cleanup APIs:

- `listScheduledFileBatch(cutoff, limit)`: at most 1024 selected raw queue tuples, including
  their timestamps for exact acknowledgement. Identical tuples describe equivalent requests.
- `listRetainedFileReferencePage(kind, afterFileId, limit)`: SQL-limited, ascending keyset pages
  of data or delete references, with page-local latest table/schema hierarchy. Includes retired
  and dropped owners; excludes the deletion queue.
- `getCleanupDataPath()`: bounded catalog-root projection, including duplicate detection.
- `removeSelectedScheduledFiles(entries)`: exact raw tuple equality, independent of supported
  path collations, in chunks of 64 predicates inside one guarded catalog transaction. At most 1024 input entries.

Path fields are limited to 16,384 characters, with SQL overflow sentinels so oversized metadata
is not first materialized in full. Consumers should plan within `readSession`, cap total inspected
references, and perform mutations afterward. These methods do not acquire ownership locks or
coordinate simultaneous import/deletion. Old APIs remain available for existing consumers;
new bulk maintenance must not use their unbounded fetches as a paging fallback.

Update consumers to matching published artifacts before deployment.

## DuckDB catalog transports

The maintained development runtime/test lane uses DuckDB JDBC **2.0.0.0** and a matching
DuckDB **2.0.0** Quack server. For an initialized DuckLake 1.0 metadata catalog:

| Route | `DucklakeCatalogConfig.catalogDatabaseUrl` | Required runtime driver |
|---|---|---|
| Local DuckDB file | `jdbc:duckdb:/path/catalog.db` | `org.duckdb:duckdb_jdbc:2.0.0.0` |
| Native Quack client | `jdbc:duckdb:quack://host:9494?metadata_catalog=lake_meta` | DuckDB JDBC 2.0.0.0, native Quack/DuckLake extensions |
| Direct Java Quack client | `jdbc:quack://host:9494` (optionally `/existing-server-catalog`) | `dev.brikk.duckdb:quack-jdbc:0.8.0` |

For both remote routes, pass the token in `catalogDatabasePassword`, not in shared
URLs. The direct Java route uses ordinary remote JDBC transactions and does not
need embedded DuckDB in the client process. Match native clients and servers to 2.0.0.
Applications supply their JDBC drivers. Local/direct JDBC routes expect initialized
metadata; the native Quack route uses DuckLake `ATTACH`, which can bootstrap missing
metadata through the native extension. No 1.1 migration is enabled by this library.
Quack serves metadata here; engines still need access to the actual Parquet data location.
DuckDB 2.0 defaults new lakes to 1.1. For read/write access, initialize them with
`DUCKLAKE_VERSION '1.0', AUTOMATIC_MIGRATION FALSE`; native Quack bootstrap does this
explicitly. Direct Java Quack 0.8.0 uses protocol v3 on the official 2.0 server.
See [compatibility](docs/COMPATIBILITY.md) and [shipping qualification](dev-docs/UPGRADE-DUCKDB-2.0.0.md).

## Compatibility status

- **Maintained development lane:** DuckLake **1.0**, DuckDB/JDBC **2.0.0 / 2.0.0.0**,
  with optional direct Java Quack JDBC **0.8.0**. The catalog library targets Java 17;
  building it requires JDK 25.
- **DuckLake 1.1 / DuckDB 2.0:** a [limited opt-in read tier](docs/READING-1.1.md)
  is available through direct Java Quack for root-schema catalogs. It is not full
  1.1 schema/type/statistics support. The limited reader is tested on official 2.0
  artifacts; all 1.1 writes
  remain disabled.
- Version 0.12.0 adds compatibility/regression preparation without changing the
  0.11.0 production support contract or dependency pins.
- Passing JVM tests or pinned native-preview CI does not qualify every native
  correctness case. Source merges are not proof a fix is in a shipping binary.
- **Rollback requires a tested backup:** the preview write/checkpoint rehearsal made
  local metadata unreadable by 1.5.6 even while the DuckLake format stayed 1.0.
  See [compatibility and rollback limits](docs/COMPATIBILITY.md).

Before adopting development snapshots, read the
[0.13.0-SNAPSHOT upgrade guidance](docs/SNAPSHOT-UPGRADE.md), including uncertain
COMMIT handling and ANALYZE's intentional metadata-only snapshots.

## Development

[Developer documentation](dev-docs/README.md) contains build/test instructions,
release procedures, active work and the historical archive. It is separate from
the public [user guides](docs/README.md).

## License

Apache License 2.0 — see [LICENSE](LICENSE).

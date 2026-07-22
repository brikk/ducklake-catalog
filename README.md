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

Latest release: **0.2.0**

### Gradle (Kotlin DSL)

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("dev.brikk.ducklake:ducklake-catalog:0.2.0")

    // Optional: shared Testcontainers-based catalog fixtures for your tests
    testImplementation(testFixtures("dev.brikk.ducklake:ducklake-catalog:0.2.0"))

    // Optional: the DuckLake corpus replay harness (test-only)
    testImplementation("dev.brikk.ducklake:ducklake-test-corpus-replay:0.2.0")

    // Optional: the standalone SLT (.test) format parser/model/expander (dependency-free)
    implementation("dev.brikk.ducklake:slt-format:0.2.0")
}
```

> The `test-fixtures` variant is resolved via Gradle Module Metadata, so consuming it with
> `testFixtures(...)` requires Gradle (Maven consumers use the classifier directly, below).

### Gradle (Groovy DSL)

```groovy
dependencies {
    implementation 'dev.brikk.ducklake:ducklake-catalog:0.2.0'
    testImplementation testFixtures('dev.brikk.ducklake:ducklake-catalog:0.2.0')
    testImplementation 'dev.brikk.ducklake:ducklake-test-corpus-replay:0.2.0'
    implementation 'dev.brikk.ducklake:slt-format:0.2.0'
}
```

### Maven

```xml
<dependency>
  <groupId>dev.brikk.ducklake</groupId>
  <artifactId>ducklake-catalog</artifactId>
  <version>0.2.0</version>
</dependency>

<!-- Test fixtures (classifier) -->
<dependency>
  <groupId>dev.brikk.ducklake</groupId>
  <artifactId>ducklake-catalog</artifactId>
  <version>0.2.0</version>
  <classifier>test-fixtures</classifier>
  <scope>test</scope>
</dependency>

<!-- Standalone SLT (.test) format library -->
<dependency>
  <groupId>dev.brikk.ducklake</groupId>
  <artifactId>slt-format</artifactId>
  <version>0.2.0</version>
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
// then depend on e.g. dev.brikk.ducklake:ducklake-catalog:0.3.0-SNAPSHOT
```

## Building

Single-project-free multi-module Gradle build (JDK 25 toolchain via `mise`):

```shell
./gradlew build            # compile + test (Docker/Podman required for Testcontainers)
./gradlew publishToMavenLocal
```

The catalog module's jOOQ bindings under `ducklake-catalog/generated/` are checked into git;
only regeneration needs Docker. See [`ducklake-catalog/README.md`](ducklake-catalog/README.md).

## Releasing

- **Snapshot** — every push to `main` (version `*-SNAPSHOT`) publishes via
  `.github/workflows/publish-snapshot.yml`.
- **Release** — push a `v<version>` tag (version must be non-SNAPSHOT). The
  `.github/workflows/publish-release.yml` workflow uploads + validates the Central Portal
  deployment bundle in **manual** mode; finish it by clicking Publish at
  <https://central.sonatype.com/publishing/deployments>. Then bump `main` to the next
  `-SNAPSHOT`.

Both workflows read the Central Portal user token + PGP signing key from org secrets
(`KOTLIN_TOOLCHAIN_*`), mapped to vanniktech's `ORG_GRADLE_PROJECT_*` properties.

## License

Apache License 2.0 — see [LICENSE](LICENSE).

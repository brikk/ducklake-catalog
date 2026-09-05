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

Latest release: **0.9.0** (`main` is `0.10.0-SNAPSHOT`)

### Gradle (Kotlin DSL)

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("dev.brikk.ducklake:ducklake-catalog:0.9.0")

    // Optional: shared Testcontainers-based catalog fixtures for your tests
    testImplementation(testFixtures("dev.brikk.ducklake:ducklake-catalog:0.9.0"))

    // Optional: the DuckLake corpus replay harness (test-only)
    testImplementation("dev.brikk.ducklake:ducklake-test-corpus-replay:0.9.0")

    // Optional: the standalone SLT (.test) format parser/model/expander (dependency-free)
    implementation("dev.brikk.ducklake:slt-format:0.9.0")
}
```

> The `test-fixtures` variant is resolved via Gradle Module Metadata, so consuming it with
> `testFixtures(...)` requires Gradle (Maven consumers use the classifier directly, below).

### Gradle (Groovy DSL)

```groovy
dependencies {
    implementation 'dev.brikk.ducklake:ducklake-catalog:0.9.0'
    testImplementation testFixtures('dev.brikk.ducklake:ducklake-catalog:0.9.0')
    testImplementation 'dev.brikk.ducklake:ducklake-test-corpus-replay:0.9.0'
    implementation 'dev.brikk.ducklake:slt-format:0.9.0'
}
```

### Maven

```xml
<dependency>
  <groupId>dev.brikk.ducklake</groupId>
  <artifactId>ducklake-catalog</artifactId>
  <version>0.9.0</version>
</dependency>

<!-- Test fixtures (classifier) -->
<dependency>
  <groupId>dev.brikk.ducklake</groupId>
  <artifactId>ducklake-catalog</artifactId>
  <version>0.9.0</version>
  <classifier>test-fixtures</classifier>
  <scope>test</scope>
</dependency>

<!-- Standalone SLT (.test) format library -->
<dependency>
  <groupId>dev.brikk.ducklake</groupId>
  <artifactId>slt-format</artifactId>
  <version>0.9.0</version>
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
// then depend on e.g. dev.brikk.ducklake:ducklake-catalog:0.10.0-SNAPSHOT
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
  path collations, in chunks of 64 predicates. At most 1024 input entries; not atomic across chunks.

Path fields are limited to 16,384 characters, with SQL overflow sentinels so oversized metadata
is not first materialized in full. Consumers should plan within `readSession`, cap total inspected
references, and perform mutations afterward. These methods do not acquire ownership locks or
coordinate simultaneous import/deletion. Old APIs remain available for existing consumers;
new bulk maintenance must not use their unbounded fetches as a paging fallback.

Update consumers to matching published artifacts before deployment.

## Building

Multi-module Gradle build. The toolchain is pinned in [`mise.toml`](mise.toml) / [`.sdkmanrc`](.sdkmanrc)
(JDK 25). Note that `build-logic` itself is compiled for JVM 25, so the JVM that *launches* Gradle
must already be 25 — the Foojay toolchain resolver only covers the compile/test toolchain. Use
`mise` (or an activated `mise` shell) so the right JDK is on `PATH`:

```shell
mise install                          # once: fetch the pinned JDK
mise exec -- ./gradlew build          # compile + test (Docker/Podman required for Testcontainers)
mise exec -- ./gradlew publishToMavenLocal
```

Running `./gradlew` from a JDK 21 (or older) shell fails at configuration time with
"Dependency requires at least JVM runtime version 25".

The catalog module's jOOQ bindings under `ducklake-catalog/generated/` are checked into git;
only regeneration needs Docker. See [`ducklake-catalog/README.md`](ducklake-catalog/README.md).

## CI

`.github/workflows/ci.yml` runs `./gradlew check` (compile, detekt, `checkAbi`, all tests including
the Testcontainers PostgreSQL/MySQL and DuckDB-oracle interop tests) on every pull request and push
to `main`, and replays the **full** upstream DuckLake sqllogictest corpus nightly (the PR job runs
the starter set). Trigger the workflow manually with `full_corpus` to replay everything on demand.

## Releasing

- **Snapshot** — every push to `main` (version `*-SNAPSHOT`) publishes via
  `.github/workflows/publish-snapshot.yml`.
- **Release** — set the module versions to a non-SNAPSHOT value, commit (`Release x.y.z`), and
  push a `v<version>` tag. `.github/workflows/publish-release.yml` uploads the Central Portal
  deployment bundle as **AUTOMATIC**: the portal validates and releases it to Maven Central with
  no manual "Publish" click (and the workflow does not wait for it). Then bump `main` to the
  next `-SNAPSHOT`.
- **Stuck deployment** — `.github/workflows/publish-pending-deployment.yml` (manual dispatch)
  drives the Central Portal Publisher API directly: `status`, `publish`, or `drop` a
  deployment by ID, for the rare case a bundle is left waiting on the portal.

Both workflows read the Central Portal user token + PGP signing key from org secrets
(`KOTLIN_TOOLCHAIN_*`), mapped to vanniktech's `ORG_GRADLE_PROJECT_*` properties.

## License

Apache License 2.0 — see [LICENSE](LICENSE).

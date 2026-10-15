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

The `0.10.0` upgrade requires nullable row-ID handling in consumers; see the
[release and migration notes](dev-docs/RELEASE-0.10.0.md).

For 0.11.0 transport setup and intentional maintenance/version-guard behavior
changes, see the [0.11.0 release notes](dev-docs/RELEASE-0.11.0.md).

Version 0.12.0 adds compatibility/regression preparation without enabling 1.1
support or changing production dependencies; see the [0.12.0 release notes](dev-docs/RELEASE-0.12.0.md).

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

The maintained runtime/test lane uses DuckDB JDBC **1.5.6.0** and a matching
DuckDB **1.5.6** Quack server. For an initialized DuckLake 1.0 metadata catalog:

| Route | `DucklakeCatalogConfig.catalogDatabaseUrl` | Required runtime driver |
|---|---|---|
| Local DuckDB file | `jdbc:duckdb:/path/catalog.db` | `org.duckdb:duckdb_jdbc:1.5.6.0` |
| Native Quack client | `jdbc:duckdb:quack://host:9494?metadata_catalog=lake_meta` | DuckDB JDBC 1.5.6.0, native Quack/DuckLake extensions |
| Direct Java Quack client | `jdbc:quack://host:9494` (optionally `/existing-server-catalog`) | `dev.brikk.duckdb:quack-jdbc:0.8.0` |

For both remote routes, pass the token in `catalogDatabasePassword`, not in shared
URLs. The direct Java route uses ordinary remote JDBC transactions and does not
need embedded DuckDB in the client process; the native route remains unchanged.
Applications supply their JDBC drivers. Local/direct JDBC routes expect initialized
metadata; the native Quack route uses DuckLake `ATTACH`, which can bootstrap missing
metadata through the native extension. No 1.1 migration is enabled by this library.
Quack serves metadata here; engines still need access to the actual Parquet data location.
Direct Java transport is verified against the released 1.5.6/v1 server; this does
not certify final DuckDB 2.0/v3 support. See the [upgrade verification report](dev-docs/UPGRADE-DUCKDB-1.5.6-2026-10-10.md).

## Compatibility status

- **Maintained, qualified lane:** DuckLake **1.0**, DuckDB/JDBC **1.5.6 / 1.5.6.0**,
  with optional direct Java Quack JDBC **0.8.0**. The catalog library targets Java 17;
  building it requires JDK 25.
- **DuckLake 1.1 / DuckDB 2.0:** preparation only, **not supported write formats or
  final-artifact certification**. All 1.1 writes remain disabled. Known dev inline
  layout reads do not constitute full 1.1 schema/type/statistics support.
- Version 0.12.0 adds flush/change-feed and expiration regressions,
  provenance-aware layout fixtures, frozen consumer ABI/wire controls and migration
  rehearsals. It preserves the 0.11.0 support contract and production pins.
  See the [prep results and remaining gates](dev-docs/DUCKLAKE-1.1-PREP-RESULTS.md) and
  [capability matrix](dev-docs/DUCKLAKE-1.1-CAPABILITIES.md).
- Strict native correctness gates still fail on the sampled maintained/locked-preview
  engines, including change feeds/delete-and-flush, old inline schemas, merge lineage,
  false-exact MIN/MAX and BLOB preservation. Passing JVM tests is not native qualification.
- **Rollback requires a tested backup:** the preview write/checkpoint rehearsal made
  local metadata unreadable by 1.5.6 even while the DuckLake format stayed 1.0.
  See the [migration/rollback acceptance runbook](dev-docs/DUCKLAKE-1.1-ACCEPTANCE.md).

Actual 1.1 implementation remains deferred until release-candidate artifacts/layouts
are rechecked, using the [consumer contract](dev-docs/DUCKLAKE-1.1-CONSUMER-CONTRACT.md).

For unreleased inline-schema/expiry fixes and PostgreSQL planning changes, see the
[upstream follow-up log](dev-docs/UPSTREAM-FOLLOWUPS-RESULTS-2026-10-14.md).
Producers upgrading must adopt the [COMMIT outcome and cleanup contract](dev-docs/COMMIT-OUTCOME-CONTRACT.md);
an uncertain commit is not permission to retry or delete output files.
Unreleased [C05 ANALYZE coordination](dev-docs/FIX-C05-ANALYZE-PUBLICATION-2026-10-15.md)
intentionally makes successful ANALYZE publish a metadata-only snapshot with an
empty change ledger; consumers must not assume it leaves the snapshot head unchanged.

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

The separate `.github/workflows/duckdb2-preview.yml` lane checks pinned **native**
layout/migration canaries, matched Quack peers and versioned local fixtures. It is
not full JVM DuckDB 2.0 or DuckLake 1.1 qualification. The
[October 8 assessment](dev-docs/UPSTREAM-DUCKDB-2.0-2026-10-08.md) is historical;
the [prep results](dev-docs/DUCKLAKE-1.1-PREP-RESULTS.md) record current findings and limits.

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

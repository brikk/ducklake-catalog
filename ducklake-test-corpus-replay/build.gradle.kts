import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("buildlogic.kotlin.library")
    // Re-exposes slt-format types (SltFile/SltRecord) on this module's public API (e.g.
    // ReplayDriver.replay(SltFile), FileResult.outcomes), so the dependency must be `api` —
    // which requires the java-library plugin.
    `java-library`
    alias(libs.plugins.detekt)
    alias(libs.plugins.vanniktech.publish)
}

version = "0.5.0-SNAPSHOT"

// Published to Maven Central as dev.brikk.ducklake:ducklake-test-corpus-replay. This ships the
// engine-agnostic replay FRAMEWORK only (DuckDB oracle, SLT parser/driver, ReplayReadEngine
// seam) — NOT the upstream corpus SQL data. Consumers (Trino/Doris plugin repos) vendor the
// pristine `duckdb/ducklake` submodule themselves and point `ducklake.corpus.root` at it.
mavenPublishing {
    publishToMavenCentral()
    if (providers.gradleProperty("signingInMemoryKey").isPresent ||
        System.getenv("ORG_GRADLE_PROJECT_signingInMemoryKey") != null
    ) {
        signAllPublications()
    }
    coordinates("dev.brikk.ducklake", "ducklake-test-corpus-replay", version.toString())
    pom {
        name.set("DuckLake Test Corpus Replay")
        description.set(
            "Engine-agnostic replay harness for the upstream DuckLake sqllogictest corpus: a " +
                "DuckDB oracle, SLT parser/driver, and a ReplayReadEngine seam that the Trino and " +
                "Doris DuckLake connectors implement to mirror lake reads. Test-only.",
        )
        url.set("https://github.com/brikk/ducklake-catalog")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer { name.set("Jayson Minard") }
            developer { name.set("Sortdev SRL") }
        }
        scm {
            url.set("https://github.com/brikk/ducklake-catalog")
            connection.set("scm:git:https://github.com/brikk/ducklake-catalog.git")
            developerConnection.set("scm:git:ssh://git@github.com/brikk/ducklake-catalog.git")
        }
    }
}

// Emit JVM 17 bytecode (built on the tree-wide JDK 25 toolchain). Nothing here
// needs a Java 18+ API — only duckdb-jdbc + kotlin stdlib — and targeting 17
// lets the JVM-17 consumers (the doris-ducklake plugin's tests, which must
// match the JDK-17 Doris FE runtime) depend on this module without a
// target-JVM-version conflict. Trino-ducklake (JVM 25) consumes 17 bytecode
// fine. This keeps doris-ducklake a single consistent 17 toolchain and avoids
// the JDK-25 parquet-format shaded-thrift ABI break in its tests.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    source.setFrom("src", "test")
}

// Replays the upstream DuckLake sqllogictest corpus (git submodule at ./ducklake,
// pinned to the release branch matching our DuckDB version) through an embedded
// DuckDB oracle, and optionally mirrors lake reads through a ReplayReadEngine
// (Trino / Doris adapters live in those modules, not here — this module stays
// engine-agnostic).
dependencies {
    // platform (not enforcedPlatform): PUBLISHED module — see note in :ducklake-catalog.
    implementation(platform(libs.kotlin.bom))
    // api: the SLT model/parser types appear on this module's public API (SltFile, SltRecord),
    // so consumers resolve them transitively.
    api(project(":slt-format"))
    implementation(libs.duckdb.jdbc)
}

tasks.withType<Test> {
    // Identity-control runs INSTALL/LOAD ducklake (network on first run, cached
    // in ~/.duckdb afterwards) and executes several thousand corpus records.
    maxHeapSize = "2g"
    // Corpus location for tests; overridable for CI layouts.
    systemProperty("ducklake.corpus.root", layout.projectDirectory.dir("ducklake/test/sql").asFile.absolutePath)
    // Directory selection: default starter set; "-Dducklake.corpus.dirs=all" for the full corpus.
    System.getProperty("ducklake.corpus.dirs")?.let { systemProperty("ducklake.corpus.dirs", it) }
}

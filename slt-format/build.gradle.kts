import com.vanniktech.maven.publish.DeploymentValidation
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("buildlogic.kotlin.library")
    alias(libs.plugins.detekt)
    alias(libs.plugins.vanniktech.publish)
}

version = "0.7.1"

// Published to Maven Central as dev.brikk.ducklake:slt-format. A standalone, dependency-free
// (kotlin-stdlib only) format layer for the DuckDB-dialect sqllogictest (.test) files:
//  - SltModel  — the sealed record types (SltFile / SltStatement / SltQuery / SltLoop / … /
//                SltUnsupported) + sort modes,
//  - SltParser — pure, no-IO, skip-don't-throw (unknown constructs surface as SltUnsupported
//                rather than exceptions),
//  - SltExpander — pure loop/foreach/conditional/template expansion to flat, fully-substituted
//                ConcreteRecords with file/line provenance.
// The replay RUNNER (DuckDB oracle, driver, golden comparator, ReplayReadEngine seam) lives in
// :ducklake-test-corpus-replay, which depends on this module.
mavenPublishing {
    // Auto-publish + no-wait (see ducklake-catalog/build.gradle.kts for rationale).
    publishToMavenCentral(true, DeploymentValidation.NONE)
    if (providers.gradleProperty("signingInMemoryKey").isPresent ||
        System.getenv("ORG_GRADLE_PROJECT_signingInMemoryKey") != null
    ) {
        signAllPublications()
    }
    coordinates("dev.brikk.ducklake", "slt-format", version.toString())
    pom {
        name.set("SLT Format")
        description.set(
            "Standalone, dependency-free JVM library for the DuckDB-dialect sqllogictest (.test) " +
                "format: the SltModel record types, a skip-don't-throw SltParser, and a pure " +
                "SltExpander that flattens loops/foreach/conditionals/templates into concrete SQL.",
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

// Emit JVM 17 bytecode (built on the tree-wide JDK 25 toolchain). The library is stdlib-only and
// uses no Java 18+ API; targeting 17 satisfies the "JVM 21-or-lower" ask from downstream
// consumers and lets :ducklake-test-corpus-replay (also JVM 17) depend on it without a
// target-version conflict.
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

dependencies {
    // platform (not enforcedPlatform): PUBLISHED module — pins the kotlin-stdlib version in the
    // POM without forcing it on consumers. No runtime dependencies beyond the stdlib.
    implementation(platform(libs.kotlin.bom))
}

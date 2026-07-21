pluginManagement {
    includeBuild("build-logic")
}

plugins {
    // Resolves and downloads the JDK 25 toolchain on demand from the Foojay Disco API,
    // so builds don't depend on a matching local JDK install.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "ducklake-catalog"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include(":slt-format")
include(":ducklake-catalog")
include(":jooq-custom-naming")
include(":ducklake-test-corpus-replay")

plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    implementation(files(libs.javaClass.superclass.protectionDomain.codeSource.location))
    implementation(libs.plugins.kotlin.jvm.toDep())

    // DucklakePostgresService stands up a Postgres container + bootstraps the DuckLake
    // metadata schema via DuckDB for jOOQ codegen.
    implementation(libs.testcontainers.postgresql)
    implementation(libs.duckdb.jdbc)
    implementation(libs.postgres.jdbc)
}

fun Provider<PluginDependency>.toDep() = map {
    "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version}"
}

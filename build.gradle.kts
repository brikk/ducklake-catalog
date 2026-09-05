plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    // Declared here (apply false) so vanniktech's shared MavenCentralBuildService is loaded
    // once in the root classloader scope. Without this, applying the plugin to two sibling
    // modules (ducklake-catalog + ducklake-test-corpus-replay) loads the build service under
    // conflicting scopes and `prepareMavenCentralPublishing` fails to create. Only affects the
    // Central publish path (mavenLocal doesn't use the service), so it passed local smoke.
    alias(libs.plugins.vanniktech.publish) apply false
}

group = "dev.brikk.ducklake"
version = "0.9.0-rv04-SNAPSHOT"

// Prints the resolved project version (used by the release workflow's SNAPSHOT guard).
tasks.register("printVersion") {
    val v = version.toString()
    doLast { println(v) }
}

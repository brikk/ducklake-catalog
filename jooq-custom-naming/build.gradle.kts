plugins {
    id("buildlogic.kotlin.library")
}

version = "0.17.0-SNAPSHOT"

dependencies {
    implementation(libs.jooq.codegen)
    implementation(libs.jooq.meta)
}

plugins {
    id("buildlogic.kotlin.library")
}

version = "0.4.0-SNAPSHOT"

dependencies {
    implementation(libs.jooq.codegen)
    implementation(libs.jooq.meta)
}

plugins {
    id("buildlogic.kotlin.library")
}

version = "0.0.1-SNAPSHOT"

dependencies {
    implementation(libs.jooq.codegen)
    implementation(libs.jooq.meta)
}

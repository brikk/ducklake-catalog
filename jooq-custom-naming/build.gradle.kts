plugins {
    id("buildlogic.kotlin.library")
}

version = "0.9.0-rv04-SNAPSHOT"

dependencies {
    implementation(libs.jooq.codegen)
    implementation(libs.jooq.meta)
}

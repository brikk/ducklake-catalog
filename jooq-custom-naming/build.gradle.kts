plugins {
    id("buildlogic.kotlin.library")
}

version = "0.15.0"

dependencies {
    implementation(libs.jooq.codegen)
    implementation(libs.jooq.meta)
}

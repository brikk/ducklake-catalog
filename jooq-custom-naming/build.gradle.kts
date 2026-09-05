plugins {
    id("buildlogic.kotlin.library")
}

version = "0.8.0"

dependencies {
    implementation(libs.jooq.codegen)
    implementation(libs.jooq.meta)
}

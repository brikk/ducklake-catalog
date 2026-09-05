plugins {
    id("buildlogic.kotlin.library")
}

version = "0.7.1"

dependencies {
    implementation(libs.jooq.codegen)
    implementation(libs.jooq.meta)
}

plugins {
    `kotlin-dsl`
}

// Every plugin the projects apply, so their versions live in the catalog.
dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.kotlin.serialization.plugin)
    implementation(libs.detekt.gradle.plugin)
    implementation(libs.spotless.gradle.plugin)
}

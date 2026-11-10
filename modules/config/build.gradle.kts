plugins {
    id("kotlin-conventions")
}

// Validated environment configuration and the JSON logging every entry point
// installs.
dependencies {
    api(libs.arrow.core)
    implementation(libs.slf4j.api)
    implementation(libs.slf4j.jul)
    runtimeOnly(libs.logstash.encoder)
    implementation(libs.logback.classic)
}

plugins {
    id("kotlin-conventions")
}

// Ports and the failure every port reports. Depends on `domain` only: no
// PostgreSQL, HTTP or runtime code, so a port cannot mention a driver's types.
dependencies {
    api(projects.domain)
    implementation(libs.slf4j.api)
}

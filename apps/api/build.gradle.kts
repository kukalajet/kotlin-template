plugins {
    id("kotlin-conventions")
    id("kotlin-serialization")
}

// Routes, request validation, the error envelope, the access log and the
// OpenAPI document. Depends on application ports, never on `database`:
// api-server chooses the adapters.
dependencies {
    api(projects.application)
    api(libs.ktor.server.core)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.routing.openapi)
    implementation(libs.ktor.serialization.json)
    implementation(libs.serialization.json)
    implementation(libs.coroutines.slf4j)
    implementation(libs.slf4j.api)

    testImplementation(libs.ktor.server.test.host)
}

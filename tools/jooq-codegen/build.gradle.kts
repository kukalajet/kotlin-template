plugins {
    id("kotlin-conventions")
}

// Generates database's jOOQ code from the migrations applied to a real
// PostgreSQL in a container. Run through `:database:generateSchema` and
// `:database:verifySchema`; nothing depends on it at runtime.
dependencies {
    implementation(libs.jooq.codegen)
    implementation(libs.flyway.core)
    implementation(libs.testcontainers.postgresql)
    runtimeOnly(libs.flyway.postgresql)
    runtimeOnly(libs.postgresql)
    runtimeOnly(libs.slf4j.nop)
}

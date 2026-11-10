plugins {
    id("kotlin-conventions")
    application
}

// Composition root: reads the configuration, opens the database, puts the
// adapters behind the ports and serves the API.
dependencies {
    implementation(projects.api)
    implementation(projects.database)
    implementation(projects.config)
    implementation(libs.ktor.server.netty)
    implementation(libs.slf4j.api)
}

application {
    // Netty loads its native transport; without this the JVM prints a
    // plain-text warning among the JSON lines.
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
    mainClass = "kotlintemplate.apiserver.MainKt"
}

tasks.register<JavaExec>("healthcheck") {
    group = "application"
    description = "Exits 0 when the local API answers GET /health/live with 200."
    mainClass = "kotlintemplate.apiserver.HealthcheckKt"
    classpath = sourceSets.main.get().runtimeClasspath
}

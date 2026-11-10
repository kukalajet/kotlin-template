plugins {
    id("kotlin-conventions")
    application
}

// Composition root: `worker` polls every job; `worker once` runs one pass.
dependencies {
    implementation(projects.worker)
    implementation(projects.database)
    implementation(projects.config)
    implementation(libs.slf4j.api)
}

application {
    mainClass = "kotlintemplate.workerserver.MainKt"
    applicationName = "worker"
}

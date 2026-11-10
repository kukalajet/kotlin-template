plugins {
    id("kotlin-conventions")
}

// Recurring background work: a table of jobs, and the runner that polls them
// or runs one pass of each. Jobs reach the database only through ports.
dependencies {
    api(projects.application)
    api(libs.coroutines.core)
    implementation(libs.coroutines.slf4j)
    implementation(libs.slf4j.api)

    testImplementation(libs.coroutines.test)
}

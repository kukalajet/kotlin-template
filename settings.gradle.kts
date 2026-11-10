pluginManagement {
    includeBuild("build-logic")
}

plugins {
    // Downloads the JDK that kotlin-conventions asks for when none is installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "kotlin-template"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

// Project name to folder: libraries under modules/, applications under apps/,
// build tools under tools/.
val projectFolders =
    mapOf(
        "domain" to "modules/domain",
        "application" to "modules/application",
        "config" to "modules/config",
        "database" to "modules/database",
        "api" to "apps/api",
        "api-server" to "apps/api-server",
        "worker" to "apps/worker",
        "worker-server" to "apps/worker-server",
        "jooq-codegen" to "tools/jooq-codegen",
    )

projectFolders.forEach { (name, folder) ->
    include(name)
    project(":$name").projectDir = file(folder)
}

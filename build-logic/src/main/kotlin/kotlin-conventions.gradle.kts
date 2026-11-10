// Applied by every project: Kotlin on the catalog's JDK, warnings as errors,
// JUnit, detekt, ktlint and the dependency-direction check.

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("dev.detekt")
    id("com.diffplug.spotless")
}

val catalog = versionCatalogs.named("libs")

fun catalogLibrary(alias: String) = catalog.findLibrary(alias).get()

fun catalogVersion(alias: String) = catalog.findVersion(alias).get().requiredVersion

kotlin {
    jvmToolchain(catalogVersion("jdk").toInt())

    compilerOptions {
        allWarningsAsErrors = true
        // Reports an ignored return value of any function in the project, so
        // an Either that carries an error cannot be dropped by accident.
        freeCompilerArgs.add("-Xreturn-value-checker=full")
    }
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation(platform(catalogLibrary("junit-bom")))
    testImplementation(catalogLibrary("junit-jupiter"))
    testRuntimeOnly(catalogLibrary("junit-launcher"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

tasks.named<Test>("test") {
    useJUnitPlatform {
        // PostgreSQL tests start a container; `:database:postgresTest` runs them.
        excludeTags("postgres")
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(isolated.rootProject.projectDirectory.file("config/detekt.yml"))
}

spotless {
    kotlin {
        // src/generated holds jOOQ's output, which is checked by comparison instead.
        target("src/main/**/*.kt", "src/test/**/*.kt")
        ktlint(catalogVersion("ktlint"))
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint(catalogVersion("ktlint"))
    }
}

// The dependency direction in ARCHITECTURE.md. Each entry names a group (or a
// project path) and the only projects that may declare it directly; Gradle's
// `implementation` scope keeps it off everyone else's compile classpath.
val checkDependencyDirection =
    tasks.register("checkDependencyDirection") {
    group = "verification"
    description = "Fails when this project declares a dependency another layer owns."

    val rules =
        mapOf(
            "org.jooq" to setOf("database", "jooq-codegen"),
            "org.postgresql" to setOf("database", "jooq-codegen"),
            "com.zaxxer" to setOf("database"),
            "org.flywaydb" to setOf("database", "jooq-codegen"),
            "org.testcontainers" to setOf("database", "jooq-codegen"),
            ":jooq-codegen" to setOf("database"),
            ":database" to setOf("api-server", "worker-server"),
            "io.ktor" to setOf("api", "api-server"),
            "org.jetbrains.kotlinx:kotlinx-serialization" to setOf("api"),
            "ch.qos.logback" to setOf("config"),
            "net.logstash.logback" to setOf("config"),
            "org.slf4j:jul-to-slf4j" to setOf("config"),
        )
    val projectName = project.name
    val declared =
        provider {
            configurations
                .flatMap { configuration -> configuration.dependencies }
                .map { dependency ->
                    when (dependency) {
                        is ProjectDependency -> dependency.path
                        else -> "${dependency.group}:${dependency.name}"
                    }
                }.distinct()
        }

    inputs.property("declared", declared)

    doLast {
        val violations =
            declared.get().flatMap { dependency ->
                rules
                    .filter { (prefix, allowed) -> dependency.startsWith(prefix) && projectName !in allowed }
                    .map { (_, allowed) -> "$dependency may only be declared by ${allowed.joinToString()}" }
            }

        if (violations.isNotEmpty()) {
            throw GradleException("Project $projectName breaks the dependency direction:\n" + violations.joinToString("\n"))
        }
    }
}

tasks.withType<dev.detekt.gradle.Detekt>().configureEach {
    // jOOQ's output: its naming and layout are the generator's.
    exclude { element -> element.file.invariantSeparatorsPath.contains("/src/generated/") }
}

// detektMain and detektTest resolve types, which ForbiddenMethodCall and
// ElseCaseInsteadOfExhaustiveWhen need; plain `detekt` would repeat them without.
tasks.named("detekt") {
    enabled = false
}

tasks.named("check") {
    dependsOn(checkDependencyDirection, "detektMain", "detektTest")
}

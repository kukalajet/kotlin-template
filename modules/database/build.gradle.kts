plugins {
    id("kotlin-conventions")
}

// The jooq-codegen tool, run in its own JVM by generateSchema and verifySchema.
val jooqCodegen = configurations.create("jooqCodegen")

// The outbound adapter: jOOQ, HikariCP, pgjdbc and Flyway. Only api-server and
// worker-server may depend on it.
dependencies {
    api(projects.application)
    api(projects.config)
    implementation(libs.coroutines.core)
    implementation(libs.slf4j.api)
    implementation(libs.jooq)
    implementation(libs.hikari)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgresql)
    implementation(libs.postgresql)

    jooqCodegen(projects.jooqCodegen)

    testImplementation(libs.coroutines.test)
    testImplementation(libs.testcontainers.postgresql)
}

val migrations = layout.projectDirectory.dir("src/main/resources/db/migration")
val generatedSchema = layout.projectDirectory.dir("src/generated/kotlin")
val schemaPackage = "kotlintemplate.database.schema"

sourceSets.main {
    kotlin.srcDir(generatedSchema)
}

// jOOQ's typed tables and records, generated from the migrations applied to a
// real PostgreSQL in a container (needs Docker) and committed under
// src/generated. Run it after adding a migration.
tasks.register<JavaExec>("generateSchema") {
    group = "build"
    description = "Regenerates src/generated from the migrations, in a PostgreSQL container."
    classpath = jooqCodegen
    mainClass = "kotlintemplate.jooqcodegen.MainKt"
    args(migrations.asFile.path, generatedSchema.asFile.path, schemaPackage)
}

// Fails when src/generated does not match the migrations, like a migration
// without its regenerated code. scripts/check.sh runs it.
val verifyOutput = layout.buildDirectory.dir("verify-schema")

tasks.register<JavaExec>("verifySchema") {
    group = "verification"
    description = "Fails when src/generated is out of date with the migrations."
    classpath = jooqCodegen
    mainClass = "kotlintemplate.jooqcodegen.MainKt"
    args(migrations.asFile.path, verifyOutput.get().asFile.path, schemaPackage)
    inputs.dir(migrations)
    inputs.dir(generatedSchema)

    val expected = generatedSchema.asFile
    val actual = verifyOutput.get().asFile

    doLast {
        fun contents(root: File) =
            root
                .walkTopDown()
                .filter(File::isFile)
                .associate { file -> file.relativeTo(root).path to file.readText() }

        if (contents(expected) != contents(actual)) {
            throw GradleException(
                "src/generated is out of date with the migrations: run ./gradlew :database:generateSchema",
            )
        }
    }
}

// Adapters against a real PostgreSQL in a container: `./gradlew :database:postgresTest`.
val postgresTest =
    tasks.register<Test>("postgresTest") {
        group = "verification"
        description = "Runs the adapters against PostgreSQL in a Testcontainers container."
        testClassesDirs =
            sourceSets.test
                .get()
                .output.classesDirs
        classpath = sourceSets.test.get().runtimeClasspath
        useJUnitPlatform {
            includeTags("postgres")
        }
    }

tasks.register<JavaExec>("migrate") {
    group = "application"
    description = "Applies pending migrations to DATABASE_URL."
    mainClass = "kotlintemplate.database.MigrateKt"
    classpath = sourceSets.main.get().runtimeClasspath
}

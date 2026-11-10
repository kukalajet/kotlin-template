package kotlintemplate.jooqcodegen

import org.flywaydb.core.Flyway
import org.jooq.codegen.GenerationTool
import org.jooq.meta.jaxb.Configuration
import org.jooq.meta.jaxb.Database
import org.jooq.meta.jaxb.Generate
import org.jooq.meta.jaxb.Generator
import org.jooq.meta.jaxb.Jdbc
import org.jooq.meta.jaxb.Target
import org.testcontainers.postgresql.PostgreSQLContainer

/** The image Compose runs, so the generated types match production's. */
private const val POSTGRES_IMAGE = "postgres:18-alpine"

private const val ARGUMENT_COUNT = 3

/**
 * Applies the migrations in `args[0]` to a fresh PostgreSQL, then writes jOOQ's
 * Kotlin code for its `public` schema to `args[1]`, in package `args[2]`,
 * replacing what is there.
 */
fun main(args: Array<String>) {
    require(args.size == ARGUMENT_COUNT) { "Usage: jooq-codegen <migrations dir> <output dir> <package>" }

    val (migrations, output, packageName) = args

    PostgreSQLContainer(POSTGRES_IMAGE).use { postgres ->
        postgres.start()

        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("filesystem:$migrations")
            .load()
            .migrate()

        GenerationTool.generate(
            Configuration()
                .withJdbc(
                    Jdbc()
                        .withDriver("org.postgresql.Driver")
                        .withUrl(postgres.jdbcUrl)
                        .withUser(postgres.username)
                        .withPassword(postgres.password),
                ).withGenerator(
                    Generator()
                        .withName("org.jooq.codegen.KotlinGenerator")
                        .withDatabase(
                            Database()
                                .withName("org.jooq.meta.postgres.PostgresDatabase")
                                .withInputSchema("public")
                                .withOutputSchemaToDefault(true)
                                .withExcludes("flyway_schema_history"),
                        ).withGenerate(Generate().withGeneratedAnnotation(false))
                        .withTarget(Target().withPackageName(packageName).withDirectory(output).withClean(true)),
                ),
        )
    }
}

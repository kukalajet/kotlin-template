package kotlintemplate.database

import arrow.core.Either
import arrow.core.flatMap
import arrow.core.raise.catch
import arrow.core.raise.either
import kotlintemplate.config.AppConfig
import kotlintemplate.config.DatabaseSettings
import kotlintemplate.config.InvalidLogLevel
import kotlintemplate.config.initLogging
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import org.flywaydb.core.api.exception.FlywayValidateException
import org.slf4j.LoggerFactory
import java.sql.SQLException
import kotlin.system.exitProcess

/**
 * Applies pending migrations from db/migration on a connection of its own.
 * Building an index on a large table can take minutes, so statements have no
 * time limit; 0 also overrides a default set on the role or the database.
 */
fun migrate(settings: DatabaseSettings): Either<MigrationError, Unit> =
    either {
        val target = JdbcTarget.parse(settings.url.expose()).mapLeft { MigrationError.InvalidUrl }.bind()
        val url =
            target.url(
                mapOf(
                    "ApplicationName" to "kotlin-template-migrate",
                    "options" to sessionOptions("statement_timeout" to 0, "lock_timeout" to MIGRATION_LOCK_TIMEOUT_MS),
                    "connectTimeout" to (ACQUIRE_TIMEOUT_MS / MILLIS_PER_SECOND).toString(),
                ),
            )
        val flyway =
            Flyway
                .configure()
                .dataSource(url, target.user, target.password)
                .locations("classpath:db/migration")
                .load()

        catch({ flyway.migrate() }) { failure: FlywayException -> raise(migrationError(failure)) }
    }

private fun migrationError(failure: FlywayException): MigrationError {
    if (failure is FlywayValidateException) {
        return MigrationError.Migrations(failure.message.orEmpty())
    }

    val sqlException = generateSequence<Throwable>(failure) { it.cause }.filterIsInstance<SQLException>().firstOrNull()

    return when (sqlException) {
        null -> MigrationError.Other(failure::class.simpleName.orEmpty())
        else -> MigrationError.Database(diagnosticsOf(sqlException))
    }
}

/** Applies pending migrations, then exits: 0 on success, 1 on failure. */
fun main() {
    val logger = LoggerFactory.getLogger("kotlintemplate.database.Migrate")

    initLogging().onLeft {
        logger.atError().addKeyValue("error", InvalidLogLevel.MESSAGE).log("Invalid logging configuration")
        exitProcess(1)
    }

    val result =
        AppConfig
            .fromEnv()
            .mapLeft { error -> error.message }
            .flatMap { config -> migrate(config.database).mapLeft { error -> error.message } }

    result.fold(
        ifLeft = { error ->
            logger.atError().addKeyValue("error", error).log("Database migration failed")
            exitProcess(1)
        },
        ifRight = {
            logger.info("Database migrated")
            exitProcess(0)
        },
    )
}

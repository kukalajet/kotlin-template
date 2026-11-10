package kotlintemplate.database

import arrow.core.Either
import kotlintemplate.application.DatabaseHealth
import kotlintemplate.application.DatabaseHealthError
import kotlintemplate.application.HealthOperation
import kotlin.time.Duration.Companion.seconds

/**
 * Readiness must answer before the probe gives up, so the check has its own
 * short limit rather than the statement-based call limit.
 */
private val CHECK_LIMIT = 5.seconds

class PgDatabaseHealth(
    private val database: Database,
) : DatabaseHealth {
    override suspend fun check(): Either<DatabaseHealthError, Unit> =
        bounded(CHECK_LIMIT, database.dsl.selectOne()) { it.execute() }
            .map { }
            .mapLeft { error -> error.toFailure(HealthOperation.Check) }
}

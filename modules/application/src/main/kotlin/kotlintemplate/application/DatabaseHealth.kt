package kotlintemplate.application

import arrow.core.Either

enum class HealthOperation(
    override val operationName: String,
) : Operation {
    Check("check"),
    ;

    override val error: String get() = "DatabaseHealthError"
}

typealias DatabaseHealthError = PersistenceFailure<HealthOperation>

/** Readiness: does the database answer. */
interface DatabaseHealth {
    suspend fun check(): Either<DatabaseHealthError, Unit>
}

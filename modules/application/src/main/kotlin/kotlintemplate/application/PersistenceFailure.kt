package kotlintemplate.application

import org.slf4j.Logger

/**
 * The operations of one port, such as [UserOperation]. [error] names the
 * port's failure in logs, like a tagged error's tag.
 */
interface Operation {
    val error: String
    val operationName: String
}

/**
 * What an adapter may say about a failed call: a reason such as
 * `DatabaseError` or `TimeoutError`, and a code such as a SQLSTATE. Adapters
 * derive these without copying messages, SQL, parameters or stored values.
 */
data class Diagnostics(
    val reason: String,
    val code: String? = null,
)

/**
 * A port's failed call. It is a value, not an exception, and holds no driver
 * exception: those can carry SQL, parameters and stored values, so only the
 * safe diagnostics survive, and nothing that logs this failure can leak them.
 */
data class PersistenceFailure<out Op : Operation>(
    val operation: Op,
    val diagnostics: Diagnostics,
) {
    /** The failure without its operation's type, for code that handles every port's failures alike. */
    fun report(): PersistenceReport = PersistenceReport(operation.error, operation.operationName, diagnostics)
}

/** Any port's failure, as entry points and the HTTP layer log it. */
data class PersistenceReport(
    val error: String,
    val operation: String,
    val diagnostics: Diagnostics,
) {
    /** One Error line with the safe fields only. */
    fun log(
        logger: Logger,
        message: String,
    ) {
        logger
            .atError()
            .addKeyValue("error", error)
            .addKeyValue("operation", operation)
            .addKeyValue("reason", diagnostics.reason)
            .addKeyValue("code", diagnostics.code)
            .log(message)
    }
}

package kotlintemplate.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import kotlintemplate.application.DatabaseHealthError
import kotlintemplate.application.PersistenceFailure
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("kotlintemplate.api")

/**
 * Every error the API answers with: its status, stable code and message,
 * written once. Each entry must supply all three, so a new error cannot be
 * answered without them.
 */
enum class ApiError(
    val status: HttpStatusCode,
    val code: String,
    val message: String,
) {
    InvalidRequest(HttpStatusCode.BadRequest, "INVALID_REQUEST", "The request payload is invalid"),
    PayloadTooLarge(HttpStatusCode.PayloadTooLarge, "PAYLOAD_TOO_LARGE", "The request payload is too large"),
    RouteNotFound(HttpStatusCode.NotFound, "NOT_FOUND", "Route not found"),
    MethodNotAllowed(HttpStatusCode.MethodNotAllowed, "METHOD_NOT_ALLOWED", "Method not allowed"),
    UserNotFound(HttpStatusCode.NotFound, "USER_NOT_FOUND", "User not found"),
    UserAlreadyExists(HttpStatusCode.Conflict, "USER_ALREADY_EXISTS", "A user with this email already exists"),
    Database(HttpStatusCode.InternalServerError, "DATABASE_ERROR", "The database operation could not be completed"),
    DatabaseUnavailable(HttpStatusCode.ServiceUnavailable, "DATABASE_UNAVAILABLE", "The database is unavailable"),
    Internal(HttpStatusCode.InternalServerError, "INTERNAL_ERROR", "The request could not be completed"),
    ;

    companion object {
        /**
         * A failed operation is a server error: 500, logged with its safe
         * diagnostics only. The response says nothing about the failure.
         */
        fun database(failure: PersistenceFailure<*>): ApiError {
            failure.report().log(logger, "Database operation failed")

            return Database
        }

        /** A failed readiness check means the database cannot serve traffic: 503. */
        fun databaseUnavailable(failure: DatabaseHealthError): ApiError {
            failure.report().log(logger, "Database health check failed")

            return DatabaseUnavailable
        }
    }
}

/** Every error response's body. */
@Serializable
data class ErrorBody(
    val error: ErrorDetail,
)

@Serializable
data class ErrorDetail(
    /** Stable and machine-readable, such as USER_NOT_FOUND. */
    val code: String,
    val message: String,
)

suspend fun ApplicationCall.respondError(error: ApiError) {
    respond(error.status, ErrorBody(ErrorDetail(error.code, error.message)))
}

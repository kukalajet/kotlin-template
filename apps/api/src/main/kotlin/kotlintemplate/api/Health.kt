@file:OptIn(ExperimentalKtorApi::class)

package kotlintemplate.api

import io.ktor.http.HttpStatusCode
import io.ktor.openapi.jsonSchema
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import io.ktor.utils.io.ExperimentalKtorApi
import kotlintemplate.application.DatabaseHealth
import kotlinx.serialization.Serializable

@Serializable
data class LivenessBody(
    val status: String,
)

@Serializable
data class ReadinessBody(
    val status: String,
    val database: String,
)

fun Route.healthRoutes(health: DatabaseHealth) {
    // Process liveness; never touches the database, so an outage does not restart the API.
    get("/health/live") {
        call.respond(LivenessBody("ok"))
    }.describe {
        summary = "Process liveness"
        tag("health")
        responses {
            HttpStatusCode.OK { schema = jsonSchema<LivenessBody>() }
        }
    }

    for (path in listOf("/health/ready", "/health")) {
        get(path) {
            call.respondReadiness(health)
        }.describe {
            summary = if (path == "/health") "Alias for readiness" else "Database readiness: select 1, 5 second limit"
            tag("health")
            responses {
                HttpStatusCode.OK { schema = jsonSchema<ReadinessBody>() }
                HttpStatusCode.ServiceUnavailable {
                    description = "DATABASE_UNAVAILABLE"
                    schema = jsonSchema<ErrorBody>()
                }
            }
        }
    }
}

private suspend fun ApplicationCall.respondReadiness(health: DatabaseHealth) {
    health.check().fold(
        ifLeft = { failure -> respondError(ApiError.databaseUnavailable(failure)) },
        ifRight = { respond(ReadinessBody(status = "ok", database = "ok")) },
    )
}

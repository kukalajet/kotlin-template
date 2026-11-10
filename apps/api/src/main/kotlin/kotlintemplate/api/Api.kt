package kotlintemplate.api

import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.routing.Route
import io.ktor.server.routing.routing
import kotlintemplate.application.DatabaseHealth
import kotlintemplate.application.UserRepository
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("kotlintemplate.api")

/**
 * The ports the routes use. Each route group takes only its own as a
 * parameter, so a test can serve one group with just its fakes.
 */
data class AppState(
    val users: UserRepository,
    val health: DatabaseHealth,
)

/** Every route, behind the access log and the error envelope. */
fun Application.api(state: AppState) {
    installApiPlugins()
    routing {
        apiRoutes(state)
    }
}

fun Route.apiRoutes(state: AppState) {
    healthRoutes(state.health)
    userRoutes(state.users)
    openApiRoute()
}

/**
 * The access log, JSON responses, and the error envelope for unknown routes,
 * unsupported methods and handlers that throw: 500 INTERNAL_ERROR, logged by
 * exception class only, since its message can carry request data.
 */
fun Application.installApiPlugins() {
    installAccessLog()
    install(ContentNegotiation) {
        json()
    }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            logger.atError().addKeyValue("exception", cause::class.qualifiedName).log("Request handler failed")
            call.respondError(ApiError.Internal)
        }
        // Only Ktor's own empty answers: a route's error envelope is left alone.
        status(HttpStatusCode.NotFound, HttpStatusCode.MethodNotAllowed) { status ->
            if (content is OutgoingContent.NoContent) {
                call.respondError(
                    if (status ==
                        HttpStatusCode.NotFound
                    ) {
                        ApiError.RouteNotFound
                    } else {
                        ApiError.MethodNotAllowed
                    },
                )
            }
        }
    }
}

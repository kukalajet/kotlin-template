package kotlintemplate.api

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.server.routing.Route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlintemplate.application.DatabaseHealth
import kotlintemplate.application.DatabaseHealthError
import kotlintemplate.application.Diagnostics
import kotlintemplate.application.FindUserError
import kotlintemplate.application.HealthOperation
import kotlintemplate.application.InsertUserError
import kotlintemplate.application.UserOperation
import kotlintemplate.application.UserRepository
import kotlintemplate.application.UserRepositoryError
import kotlintemplate.domain.CreateUser
import kotlintemplate.domain.User
import kotlintemplate.domain.UserAlreadyExists
import kotlintemplate.domain.UserId
import kotlintemplate.domain.UserNotFound
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.util.UUID

/** An in-memory UserRepository; `failing` makes every call a persistence failure. */
class FakeUsers(
    private val failing: Boolean = false,
) : UserRepository {
    val users = mutableListOf<User>()
    var lastLimit: Int? = null

    private fun failure(operation: UserOperation) = UserRepositoryError(operation, Diagnostics("TimeoutError"))

    override suspend fun insert(input: CreateUser): Either<InsertUserError, User> {
        if (failing) return InsertUserError.Repository(failure(UserOperation.Insert)).left()
        if (users.any { it.email == input.email }) {
            return InsertUserError
                .AlreadyExists(
                    UserAlreadyExists(input.email),
                ).left()
        }

        val user =
            User(
                UserId(UUID.randomUUID()),
                input.email,
                input.displayName,
                Instant.parse("2026-11-10T10:14:23.984814Z"),
            )

        users.add(0, user)

        return user.right()
    }

    override suspend fun findById(id: UserId): Either<FindUserError, User> {
        if (failing) return FindUserError.Repository(failure(UserOperation.FindById)).left()

        return users.find { it.id == id }?.right() ?: FindUserError.NotFound(UserNotFound(id)).left()
    }

    override suspend fun list(limit: Int): Either<UserRepositoryError, List<User>> {
        lastLimit = limit

        return if (failing) failure(UserOperation.List).left() else users.take(limit).right()
    }
}

class FakeHealth(
    private val healthy: Boolean,
) : DatabaseHealth {
    override suspend fun check(): Either<DatabaseHealthError, Unit> =
        if (healthy) {
            Unit.right()
        } else {
            DatabaseHealthError(
                HealthOperation.Check,
                Diagnostics("PoolTimedOut", "08001"),
            ).left()
        }
}

/** Serves [routes] behind the API's plugins, as the real application does. */
fun serve(
    routes: Route.() -> Unit,
    test: suspend ApplicationTestBuilder.() -> Unit,
) = testApplication {
    application {
        installApiPlugins()
        routing(routes)
    }
    test()
}

suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

suspend fun HttpResponse.errorCode(): String? =
    json()["error"]
        ?.jsonObject
        ?.get("code")
        ?.jsonPrimitive
        ?.content

@file:OptIn(ExperimentalKtorApi::class)

package kotlintemplate.api

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.withError
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.JsonSchema
import io.ktor.openapi.JsonType
import io.ktor.openapi.jsonSchema
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.post
import io.ktor.utils.io.ExperimentalKtorApi
import kotlintemplate.application.FindUserError
import kotlintemplate.application.InsertUserError
import kotlintemplate.application.UserRepository
import kotlintemplate.domain.CreateUser
import kotlintemplate.domain.DisplayName
import kotlintemplate.domain.Email
import kotlintemplate.domain.User
import kotlintemplate.domain.UserId
import kotlinx.serialization.Serializable
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * A valid body is under 2 KiB: a 320-character email and a 100-character
 * display name at up to 4 UTF-8 bytes per character, plus the JSON around
 * them.
 */
private const val CREATE_USER_BODY_MAX_BYTES = 4L * 1024

/**
 * The list has no cursor, so `limit` caps what one request can read. A
 * project that needs to page past the cap adds its own pagination.
 */
private const val LIST_LIMIT_MAX = 100
private const val LIST_LIMIT_DEFAULT = 50

/** RFC 3339 with milliseconds, in UTC. */
private val createdAtFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

@Serializable
data class UserBody(
    val id: String,
    val email: String,
    val displayName: String,
    val createdAt: String,
) {
    companion object {
        fun of(user: User) =
            UserBody(
                id = user.id.toString(),
                email = user.email.value,
                displayName = user.displayName.value,
                createdAt = createdAtFormat.format(user.createdAt.truncatedTo(ChronoUnit.MILLIS)),
            )
    }
}

@Serializable
data class UserListBody(
    val users: List<UserBody>,
)

@Serializable
data class CreateUserBody(
    val email: String,
    val displayName: String,
) {
    fun toDomain(): Either<ApiError, CreateUser> =
        either {
            CreateUser(
                email = withError({ ApiError.InvalidRequest }) { Email.parse(email).bind() },
                displayName = withError({ ApiError.InvalidRequest }) { DisplayName.parse(displayName).bind() },
            )
        }
}

/** Every case gets an answer; a new case fails the build until it does. */
internal fun FindUserError.toApiError(): ApiError =
    when (this) {
        is FindUserError.NotFound -> ApiError.UserNotFound
        is FindUserError.Repository -> ApiError.database(failure)
    }

internal fun InsertUserError.toApiError(): ApiError =
    when (this) {
        is InsertUserError.AlreadyExists -> ApiError.UserAlreadyExists
        is InsertUserError.Repository -> ApiError.database(failure)
    }

fun Route.userRoutes(users: UserRepository) {
    listUsers(users)
    getUser(users)
    createUser(users)
}

private fun Route.listUsers(users: UserRepository) {
    get("/users") {
        call.respondResult(HttpStatusCode.OK) {
            val limit = call.intQueryParameter("limit", 1..LIST_LIMIT_MAX, LIST_LIMIT_DEFAULT).bind()
            val listed = users.list(limit).mapLeft(ApiError::database).bind()

            UserListBody(listed.map(UserBody::of))
        }
    }.describe {
        summary = "Lists users, newest first"
        tag("users")
        parameters {
            query("limit") {
                description = "An integer from 1 to 100; defaults to 50."
                schema = JsonSchema(type = JsonType.INTEGER, minimum = 1.0, maximum = LIST_LIMIT_MAX.toDouble())
            }
        }
        responses {
            HttpStatusCode.OK { schema = jsonSchema<UserListBody>() }
            errorResponse(ApiError.InvalidRequest, ApiError.Database)
        }
    }
}

private fun Route.getUser(users: UserRepository) {
    get("/users/{id}") {
        call.respondResult(HttpStatusCode.OK) {
            val id = call.uuidParameter("id").bind()
            val user = users.findById(UserId(id)).mapLeft { it.toApiError() }.bind()

            UserBody.of(user)
        }
    }.describe {
        summary = "Gets one user"
        tag("users")
        parameters {
            path("id") {
                description = "The user's id"
                schema = JsonSchema(type = JsonType.STRING, format = "uuid")
            }
        }
        responses {
            HttpStatusCode.OK { schema = jsonSchema<UserBody>() }
            errorResponse(ApiError.InvalidRequest, ApiError.UserNotFound, ApiError.Database)
        }
    }
}

private fun Route.createUser(users: UserRepository) {
    post("/users") {
        call.respondResult(HttpStatusCode.Created) {
            val body = call.receiveJson<CreateUserBody>(CREATE_USER_BODY_MAX_BYTES).bind()
            val user = users.insert(body.toDomain().bind()).mapLeft { it.toApiError() }.bind()

            UserBody.of(user)
        }
    }.describe {
        summary = "Creates a user"
        tag("users")
        requestBody {
            required = true
            schema = jsonSchema<CreateUserBody>()
        }
        responses {
            HttpStatusCode.Created { schema = jsonSchema<UserBody>() }
            errorResponse(
                ApiError.InvalidRequest,
                ApiError.UserAlreadyExists,
                ApiError.PayloadTooLarge,
                ApiError.Database,
            )
        }
    }
}

/** Answers with [status] and the block's value, or with the error it raised. */
internal suspend inline fun <reified T : Any> ApplicationCall.respondResult(
    status: HttpStatusCode,
    block: arrow.core.raise.Raise<ApiError>.() -> T,
) {
    either(block).fold(
        ifLeft = { error -> respondError(error) },
        ifRight = { value -> respond(status, value) },
    )
}

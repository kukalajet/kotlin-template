package kotlintemplate.api

import arrow.core.Either
import arrow.core.raise.catch
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentLength
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveChannel
import io.ktor.utils.io.readBuffer
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import java.nio.charset.CharacterCodingException
import java.util.UUID

/** Unknown fields are rejected: kotlinx.serialization's default. */
internal val requestJson = Json

private val canonicalUuid = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

/**
 * A JSON body of at most [maxBytes]: 413 above it, 400 for a missing or
 * different content type, malformed JSON, a missing field or an unknown one.
 * The body is read up to one byte past the limit, never further.
 */
internal suspend inline fun <reified T> ApplicationCall.receiveJson(maxBytes: Long): Either<ApiError, T> =
    either {
        ensure(request.contentType().match(ContentType.Application.Json)) { ApiError.InvalidRequest }
        ensure((request.contentLength() ?: 0) <= maxBytes) { ApiError.PayloadTooLarge }

        val bytes = receiveChannel().readBuffer(maxBytes + 1).readByteArray()

        ensure(bytes.size <= maxBytes) { ApiError.PayloadTooLarge }

        val text =
            catch({ bytes.decodeToString(throwOnInvalidSequence = true) }) { _: CharacterCodingException ->
                raise(ApiError.InvalidRequest)
            }

        // SerializationException, for malformed JSON and missing or unknown
        // fields, is an IllegalArgumentException.
        catch({ requestJson.decodeFromString<T>(text) }) { _: IllegalArgumentException ->
            raise(ApiError.InvalidRequest)
        }
    }

/** A path parameter holding a UUID in its canonical 36-character form. */
internal fun ApplicationCall.uuidParameter(name: String): Either<ApiError, UUID> =
    either {
        val raw = ensureNotNull(parameters[name]) { ApiError.InvalidRequest }

        ensure(canonicalUuid.matches(raw)) { ApiError.InvalidRequest }

        UUID.fromString(raw)
    }

/** An optional integer query parameter within [range], given at most once. */
internal fun ApplicationCall.intQueryParameter(
    name: String,
    range: IntRange,
    default: Int,
): Either<ApiError, Int> =
    either {
        val values = request.queryParameters.getAll(name) ?: return@either default
        val value = values.singleOrNull()?.toIntOrNull()

        ensure(value != null && value in range) { ApiError.InvalidRequest }

        value
    }

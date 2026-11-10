package kotlintemplate.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.util.UUID
import kotlin.time.TimeSource

private val logger = LoggerFactory.getLogger("kotlintemplate.api.AccessLog")

/** The Compose healthcheck polls these every few seconds; only a probe that fails is worth a line. */
private val healthProbePaths = setOf("/health", "/health/live", "/health/ready")

private const val CF_RAY_MAX_CHARS = 64

/**
 * Runs each request with a fresh `trace_id` in the MDC, so every line its
 * handlers log carries it, then logs one line after the response: Error for
 * a 5xx, Info otherwise. The path is logged without its query string, which
 * can carry tokens or personal data, and no other headers are logged.
 *
 * Install it before StatusPages, so it sees the status StatusPages answers.
 */
internal fun Application.installAccessLog() {
    intercept(ApplicationCallPipeline.Monitoring) {
        val traceId = UUID.randomUUID().toString().replace("-", "")
        val startedAt = TimeSource.Monotonic.markNow()

        withContext(MDCContext(MDC.getCopyOfContextMap().orEmpty() + ("trace_id" to traceId))) {
            proceed()

            val status = call.response.status() ?: HttpStatusCode.NotFound
            val path = call.request.path()

            if (!isPassingHealthProbe(path, status)) {
                val isServerError = status.value >= HttpStatusCode.InternalServerError.value
                val event =
                    (if (isServerError) logger.atError() else logger.atInfo())
                        .addKeyValue("method", call.request.httpMethod.value)
                        .addKeyValue("path", path)
                        .addKeyValue("status", status.value)
                        .addKeyValue("duration_ms", startedAt.elapsedNow().inWholeMilliseconds)
                val cfRay = cfRayOf(call.request.headers["cf-ray"])

                (cfRay?.let { event.addKeyValue("cf_ray", it) } ?: event).log("Sent HTTP response")
            }
        }
    }
}

internal fun isPassingHealthProbe(
    path: String,
    status: HttpStatusCode,
): Boolean = path in healthProbePaths && status.value < HttpStatusCode.BadRequest.value

/**
 * Cloudflare's request id, such as 8f1d2c3b4a5e6f70-FRA. Any client can send
 * the header, so only a short, plain value is logged: letters, digits and
 * hyphens.
 */
internal fun cfRayOf(value: String?): String? =
    value?.takeIf { ray ->
        ray.isNotEmpty() && ray.length <= CF_RAY_MAX_CHARS && ray.all { it.isAsciiLetterOrDigit() || it == '-' }
    }

private fun Char.isAsciiLetterOrDigit(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

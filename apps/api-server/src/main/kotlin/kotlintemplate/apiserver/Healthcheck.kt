package kotlintemplate.apiserver

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

private val TIMEOUT = 2.seconds.toJavaDuration()
private const val OK = 200

/**
 * The container's healthcheck: exits 0 when the API on this host answers
 * `GET /health/live` with 200. The runtime image has no shell or curl.
 */
fun main() {
    // The healthcheck runs alone, without DATABASE_URL, so it reads only API_PORT.
    @Suppress("ForbiddenMethodCall")
    val port = System.getenv("API_PORT")?.takeIf { it.isNotEmpty() } ?: "8000"
    val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/health/live")).timeout(TIMEOUT).build()
    val status =
        try {
            HttpClient
                .newBuilder()
                .connectTimeout(TIMEOUT)
                .build()
                .use { client -> client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() }
        } catch (
            @Suppress("SwallowedException") failure: IOException,
        ) {
            null
        }

    exitProcess(if (status == OK) 0 else 1)
}

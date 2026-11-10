package kotlintemplate.api

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.get
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApiTest {
    private val healthy = AppState(FakeUsers(), FakeHealth(healthy = true))

    @Test
    fun `unknown routes and methods answer with the error envelope`() =
        serve({ userRoutes(FakeUsers()) }) {
            val missing = client.get("/nope")
            val wrongMethod = client.delete("/users")

            assertEquals(HttpStatusCode.NotFound, missing.status)
            assertEquals("NOT_FOUND", missing.errorCode())
            assertEquals(HttpStatusCode.MethodNotAllowed, wrongMethod.status)
            assertEquals("METHOD_NOT_ALLOWED", wrongMethod.errorCode())
        }

    @Test
    fun `a handler that throws answers 500 without the cause`() =
        serve({ get("/boom") { error("secret detail") } }) {
            val response = client.get("/boom")

            assertEquals(HttpStatusCode.InternalServerError, response.status)
            assertEquals("INTERNAL_ERROR", response.errorCode())
            assertTrue("secret" !in response.bodyAsText())
        }

    @Test
    fun `liveness never asks the database`() =
        serve({ healthRoutes(FakeHealth(healthy = false)) }) {
            assertEquals(HttpStatusCode.OK, client.get("/health/live").status)
        }

    @Test
    fun `readiness and its alias follow the database`() {
        serve({ healthRoutes(FakeHealth(healthy = true)) }) {
            assertEquals(HttpStatusCode.OK, client.get("/health/ready").status)
            assertEquals(
                "ok",
                client
                    .get("/health")
                    .json()["database"]
                    ?.jsonPrimitive
                    ?.content,
            )
        }
        serve({ healthRoutes(FakeHealth(healthy = false)) }) {
            val response = client.get("/health/ready")

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertEquals("DATABASE_UNAVAILABLE", response.errorCode())
            assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/health").status)
        }
    }

    @Test
    fun `the OpenAPI document describes every route but itself`() =
        serve({ apiRoutes(healthy) }) {
            val document = Json.parseToJsonElement(client.get("/openapi.json").bodyAsText()).jsonObject
            val paths = document["paths"]?.jsonObject?.keys.orEmpty()

            assertTrue(
                document["openapi"]
                    ?.jsonPrimitive
                    ?.content
                    .orEmpty()
                    .startsWith("3.1"),
            )
            assertEquals(setOf("/health", "/health/live", "/health/ready", "/users", "/users/{id}"), paths)
        }

    @Test
    fun `only a plain cf-ray is logged`() {
        assertEquals("8f1d2c3b4a5e6f70-FRA", cfRayOf("8f1d2c3b4a5e6f70-FRA"))

        for (value in listOf("", "abc def", "abc\"}", "a".repeat(65), null)) {
            assertNull(cfRayOf(value), value)
        }
    }

    @Test
    fun `only passing health probes are skipped`() {
        assertTrue(isPassingHealthProbe("/health/live", HttpStatusCode.OK))
        assertTrue(!isPassingHealthProbe("/health/ready", HttpStatusCode.ServiceUnavailable))
        assertTrue(!isPassingHealthProbe("/users", HttpStatusCode.OK))
    }
}

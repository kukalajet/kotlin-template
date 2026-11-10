package kotlintemplate.api

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class UsersTest {
    private suspend fun ApplicationTestBuilder.create(
        body: String,
        type: ContentType = ContentType.Application.Json,
    ): HttpResponse =
        client.post("/users") {
            contentType(type)
            setBody(body)
        }

    @Test
    fun `creating a user answers 201 with the user`() =
        serve({ userRoutes(FakeUsers()) }) {
            val response = create("""{"email":" ada@example.com ","displayName":"Ada"}""")
            val body = response.json()

            assertEquals(HttpStatusCode.Created, response.status)
            assertEquals("ada@example.com", body["email"]?.jsonPrimitive?.content)
            assertEquals("Ada", body["displayName"]?.jsonPrimitive?.content)
            assertEquals("2026-11-10T10:14:23.984Z", body["createdAt"]?.jsonPrimitive?.content)
        }

    @Test
    fun `invalid bodies are 400`() =
        serve({ userRoutes(FakeUsers()) }) {
            val invalid =
                listOf(
                    """{"email":"ada","displayName":"Ada"}""",
                    """{"email":"ada@example.com","displayName":"  "}""",
                    """{"email":"ada@example.com"}""",
                    """{"email":"ada@example.com","displayName":"Ada","admin":true}""",
                    """{"email":"ada@example.com","displayName":null}""",
                    """{"email":""",
                    "",
                )

            for (body in invalid) {
                assertEquals("INVALID_REQUEST", create(body).errorCode(), body)
            }

            val notJson = create("""{"email":"ada@example.com","displayName":"Ada"}""", ContentType.Text.Plain)

            assertEquals(HttpStatusCode.BadRequest, notJson.status)
        }

    @Test
    fun `a body over 4 KiB is 413`() =
        serve({ userRoutes(FakeUsers()) }) {
            val response = create("""{"email":"ada@example.com","displayName":"${"a".repeat(5000)}"}""")

            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
            assertEquals("PAYLOAD_TOO_LARGE", response.errorCode())
        }

    @Test
    fun `a taken email is 409`() =
        serve({ userRoutes(FakeUsers()) }) {
            val body = """{"email":"ada@example.com","displayName":"Ada"}"""

            assertEquals(HttpStatusCode.Created, create(body).status)
            assertEquals("USER_ALREADY_EXISTS", create(body).errorCode())
        }

    @Test
    fun `users are found by id`() =
        serve({ userRoutes(FakeUsers()) }) {
            val id = create("""{"email":"ada@example.com","displayName":"Ada"}""").json()["id"]?.jsonPrimitive?.content

            assertEquals(HttpStatusCode.OK, client.get("/users/$id").status)
            assertEquals("USER_NOT_FOUND", client.get("/users/0b6d8a4e-1f9c-4c1e-9a43-8a1f4a3e2b10").errorCode())
            assertEquals("INVALID_REQUEST", client.get("/users/not-a-uuid").errorCode())
            assertEquals("INVALID_REQUEST", client.get("/users/1-1-1-1-1").errorCode())
        }

    @Test
    fun `list takes a limit from 1 to 100, 50 by default`() {
        val users = FakeUsers()

        serve({ userRoutes(users) }) {
            val listed = client.get("/users")

            assertEquals(HttpStatusCode.OK, listed.status)
            assertEquals(0, listed.json()["users"]?.jsonArray?.size)
            assertEquals(50, users.lastLimit)
            assertEquals(HttpStatusCode.OK, client.get("/users?limit=100").status)

            for (limit in listOf("0", "101", "abc", "", "1&limit=2")) {
                assertEquals("INVALID_REQUEST", client.get("/users?limit=$limit").errorCode(), limit)
            }
        }
    }

    @Test
    fun `a persistence failure is 500 without the cause`() =
        serve({ userRoutes(FakeUsers(failing = true)) }) {
            val response = client.get("/users")

            assertEquals(HttpStatusCode.InternalServerError, response.status)
            assertEquals("DATABASE_ERROR", response.errorCode())
            assertEquals("DATABASE_ERROR", create("""{"email":"ada@example.com","displayName":"Ada"}""").errorCode())
        }
}

package kotlintemplate.config

import arrow.core.left
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds

class AppConfigTest {
    private val url = "DATABASE_URL" to "postgresql://localhost/app"

    private fun configFrom(vararg pairs: Pair<String, String>) = AppConfig.fromLookup(mapOf(*pairs)::get)

    @Test
    fun `defaults apply to unset and empty values`() {
        val config = configFrom(url, "API_PORT" to "").getOrNull() ?: fail("config is invalid")

        assertEquals(10_000, config.database.statementTimeoutMs)
        assertEquals("127.0.0.1", config.apiHost)
        assertEquals(8000, config.apiPort)
        assertEquals(5000.milliseconds, config.workerPollInterval)
        assertEquals(60_000.milliseconds, config.workerPassTimeout)
    }

    @Test
    fun `database url is required and redacted`() {
        assertEquals(ConfigError.Missing("DATABASE_URL").left(), configFrom())
        assertEquals(
            "<redacted>",
            configFrom(url)
                .getOrNull()
                ?.database
                ?.url
                .toString(),
        )
    }

    @Test
    fun `invalid values name the variable only`() {
        val invalid =
            listOf(
                "DATABASE_STATEMENT_TIMEOUT_MS" to "0",
                "DATABASE_STATEMENT_TIMEOUT_MS" to "2147483648",
                "API_PORT" to "0",
                "API_PORT" to "65536",
                "WORKER_POLL_INTERVAL_MS" to "-1",
                "WORKER_PASS_TIMEOUT_MS" to "soon",
            )

        for ((name, value) in invalid) {
            val error = configFrom(url, name to value).leftOrNull()

            assertEquals(name, (error as? ConfigError.Invalid)?.name, "$name=$value")
            assertFalse(error?.message.orEmpty().contains(value), "$name=$value")
        }
    }
}

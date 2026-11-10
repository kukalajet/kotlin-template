package kotlintemplate.config

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.core.raise.ensure
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * PostgreSQL stores statement_timeout as a signed 32-bit number of
 * milliseconds and refuses a larger value when a connection starts, which
 * would fail every query rather than this check at startup.
 */
private const val POSTGRES_MAX_TIMEOUT_MS = Int.MAX_VALUE.toLong()
private const val MAX_PORT = 65_535

private const val DEFAULT_STATEMENT_TIMEOUT_MS = 10_000L
private const val DEFAULT_API_PORT = 8000L
private const val DEFAULT_WORKER_POLL_INTERVAL_MS = 5000L
private const val DEFAULT_WORKER_PASS_TIMEOUT_MS = 60_000L

data class DatabaseSettings(
    val url: Redacted<String>,
    val statementTimeoutMs: Long,
)

/**
 * The settings the API and the worker read. Each variable is validated once,
 * at startup; an empty value counts as unset.
 */
data class AppConfig(
    val database: DatabaseSettings,
    val apiHost: String,
    val apiPort: Int,
    /** The pause after each pass of a worker job. */
    val workerPollInterval: Duration,
    /** A pass of a worker job that runs longer fails the worker. */
    val workerPassTimeout: Duration,
) {
    companion object {
        fun fromEnv(): Either<ConfigError, AppConfig> = fromLookup(System::getenv)

        /** Reads the settings through [lookup], so tests and tools can pass their own variables. */
        fun fromLookup(lookup: (String) -> String?): Either<ConfigError, AppConfig> =
            either {
                val read = { name: String -> lookup(name)?.takeIf { it.isNotEmpty() } }

                AppConfig(
                    database =
                        DatabaseSettings(
                            url = Redacted(read("DATABASE_URL") ?: raise(ConfigError.Missing("DATABASE_URL"))),
                            statementTimeoutMs =
                                long(
                                    read,
                                    "DATABASE_STATEMENT_TIMEOUT_MS",
                                    "an integer from 1 to 2147483647",
                                    DEFAULT_STATEMENT_TIMEOUT_MS,
                                ) {
                                    it in 1..POSTGRES_MAX_TIMEOUT_MS
                                },
                        ),
                    apiHost = read("API_HOST") ?: "127.0.0.1",
                    apiPort =
                        long(
                            read,
                            "API_PORT",
                            "a port from 1 to 65535",
                            DEFAULT_API_PORT,
                        ) { it in 1..MAX_PORT }.toInt(),
                    workerPollInterval =
                        positiveMillis(
                            read,
                            "WORKER_POLL_INTERVAL_MS",
                            DEFAULT_WORKER_POLL_INTERVAL_MS,
                        ),
                    workerPassTimeout =
                        positiveMillis(
                            read,
                            "WORKER_PASS_TIMEOUT_MS",
                            DEFAULT_WORKER_PASS_TIMEOUT_MS,
                        ),
                )
            }

        private fun Raise<ConfigError>.long(
            read: (String) -> String?,
            name: String,
            expected: String,
            default: Long,
            isValid: (Long) -> Boolean,
        ): Long {
            val raw = read(name) ?: return default
            val value = raw.toLongOrNull()

            ensure(value != null && isValid(value)) { ConfigError.Invalid(name, expected) }

            return value
        }

        private fun Raise<ConfigError>.positiveMillis(
            read: (String) -> String?,
            name: String,
            default: Long,
        ): Duration = long(read, name, "a positive integer", default) { it > 0 }.milliseconds
    }
}

/** Names the variable and what it should hold, never its value, which can be a secret. */
sealed interface ConfigError {
    val message: String

    data class Missing(
        val name: String,
    ) : ConfigError {
        override val message: String get() = "$name is required"
    }

    data class Invalid(
        val name: String,
        val expected: String,
    ) : ConfigError {
        override val message: String get() = "$name must be $expected"
    }
}

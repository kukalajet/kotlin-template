package kotlintemplate.database

import arrow.core.Either
import arrow.core.raise.catch
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlintemplate.config.DatabaseSettings
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.conf.Settings
import org.jooq.impl.DSL
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.text.Charsets.UTF_8
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private const val APPLICATION_NAME = "kotlin-template"
private const val DEFAULT_PORT = 5432
private const val MAX_POOL_SIZE = 10
internal const val MILLIS_PER_SECOND = 1000L

/**
 * With every pooled connection busy, a query waits this long for one and then
 * fails like any other database operation, instead of queueing without bound
 * behind slow statements. Opening a new connection counts as part of it.
 */
internal const val ACQUIRE_TIMEOUT_MS = 5_000L

/**
 * A migration waiting for a lock, behind a long transaction say, holds up
 * every later query on its table, so it gives up after this wait.
 */
internal const val MIGRATION_LOCK_TIMEOUT_MS = 5_000L

/**
 * Longer than the migration's lock wait, so a query queued behind a migration
 * that gives up still succeeds. Shorter than the default statement timeout,
 * so a query stuck behind a lock fails with SQLSTATE 55P03 (lock timeout)
 * rather than 57014 (statement timeout), which reads as slow SQL.
 */
private const val LOCK_TIMEOUT_MS = 8_000L

/**
 * PostgreSQL ends a session that stays idle inside an open transaction this
 * long, releasing locks that would block migrations and vacuum.
 */
private const val IDLE_IN_TRANSACTION_SESSION_TIMEOUT_MS = 30_000L

/**
 * PostgreSQL enforces the limits above, so none of them fires when the server
 * stops answering. Each call is therefore also bounded by the longest the
 * server's limits allow: the wait for a connection, then the statement, plus
 * a margin for the round trip, so the server's own error arrives first
 * whenever it can.
 */
private const val CALL_MARGIN_MS = 2_000L

fun databaseCallTimeout(statementTimeoutMs: Long): Duration =
    (ACQUIRE_TIMEOUT_MS + statementTimeoutMs + CALL_MARGIN_MS).milliseconds

/** The URL is not kept: it holds the password. */
data object InvalidDatabaseUrl {
    const val MESSAGE = "DATABASE_URL is not a valid PostgreSQL connection URL"
}

/** A libpq-style DATABASE_URL, split into what pgjdbc takes. */
internal data class JdbcTarget(
    private val hostPortDatabase: String,
    private val query: List<String>,
    val user: String?,
    val password: String?,
) {
    /** The JDBC URL with [properties] added to the URL's own parameters. */
    fun url(properties: Map<String, String>): String {
        val added = properties.map { (name, value) -> "$name=${URLEncoder.encode(value, UTF_8)}" }
        val parameters = (query + added).joinToString("&")

        return "jdbc:postgresql://$hostPortDatabase" + if (parameters.isEmpty()) "" else "?$parameters"
    }

    companion object {
        fun parse(databaseUrl: String): Either<InvalidDatabaseUrl, JdbcTarget> =
            either {
                val uri = catch({ URI(databaseUrl) }) { raise(InvalidDatabaseUrl) }

                ensure(uri.scheme == "postgresql" || uri.scheme == "postgres") { InvalidDatabaseUrl }

                val host = ensureNotNull(uri.host) { InvalidDatabaseUrl }
                val database = uri.rawPath.orEmpty().removePrefix("/")

                ensure(database.isNotEmpty()) { InvalidDatabaseUrl }

                val port = if (uri.port == -1) DEFAULT_PORT else uri.port
                val userInfo = uri.rawUserInfo?.split(":", limit = 2).orEmpty()

                JdbcTarget(
                    hostPortDatabase = "$host:$port/$database",
                    query = uri.rawQuery?.split("&").orEmpty(),
                    user = userInfo.getOrNull(0)?.let(::percentDecode),
                    password = userInfo.getOrNull(1)?.let(::percentDecode),
                )
            }

        /** URLDecoder alone would turn a literal "+" into a space. */
        private fun percentDecode(value: String): String = URLDecoder.decode(value.replace("+", "%2B"), UTF_8)
    }
}

/** pgjdbc's socketTimeout is in whole seconds; round up. */
private fun socketTimeoutSeconds(statementTimeoutMs: Long): String =
    ((statementTimeoutMs + CALL_MARGIN_MS + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND).toString()

/** pgjdbc's `options`: settings PostgreSQL applies to the session as it starts. */
internal fun sessionOptions(vararg settings: Pair<String, Long>): String =
    settings.joinToString(" ") { (name, value) -> "-c $name=$value" }

/**
 * The API's and worker's connection pool and jOOQ context. The pool opens
 * connections on demand, so the API starts, and liveness answers, while the
 * database is unavailable. Close it after the server or worker stops.
 */
class Database private constructor(
    private val dataSource: HikariDataSource,
    internal val dsl: DSLContext,
    internal val callLimit: Duration,
) : AutoCloseable {
    override fun close() {
        dataSource.close()
    }

    companion object {
        fun open(settings: DatabaseSettings): Either<InvalidDatabaseUrl, Database> =
            either {
                val target = JdbcTarget.parse(settings.url.expose()).bind()
                val callLimit = databaseCallTimeout(settings.statementTimeoutMs)
                val pool =
                    HikariConfig().apply {
                        poolName = APPLICATION_NAME
                        jdbcUrl =
                            target.url(
                                mapOf(
                                    "ApplicationName" to APPLICATION_NAME,
                                    "options" to
                                        sessionOptions(
                                            "statement_timeout" to settings.statementTimeoutMs,
                                            "lock_timeout" to LOCK_TIMEOUT_MS,
                                            "idle_in_transaction_session_timeout" to
                                                IDLE_IN_TRANSACTION_SESSION_TIMEOUT_MS,
                                        ),
                                    // A read that waits past the statement timeout and the
                                    // margin means the server stopped answering: pgjdbc drops
                                    // the connection, freeing a call that already gave up.
                                    "socketTimeout" to socketTimeoutSeconds(settings.statementTimeoutMs),
                                ),
                            )
                        username = target.user
                        password = target.password
                        maximumPoolSize = MAX_POOL_SIZE
                        minimumIdle = 0
                        connectionTimeout = ACQUIRE_TIMEOUT_MS
                        // Start without a connection: the database may be down.
                        initializationFailTimeout = -1
                    }

                val dataSource = HikariDataSource(pool)

                Database(dataSource, DSL.using(dataSource, SQLDialect.POSTGRES, jooqSettings), callLimit)
            }

        init {
            // jOOQ prints a banner and tips on first use otherwise.
            System.setProperty("org.jooq.no-logo", "true")
            System.setProperty("org.jooq.no-tips", "true")
        }
    }
}

/** jOOQ settings for every adapter: SQL text never reaches the logs. */
internal val jooqSettings: Settings = Settings().withExecuteLogging(false)

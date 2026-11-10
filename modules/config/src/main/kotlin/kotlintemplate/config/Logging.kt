package kotlintemplate.config

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.bridge.SLF4JBridgeHandler

private val levels =
    mapOf(
        "trace" to Level.TRACE,
        "debug" to Level.DEBUG,
        "info" to Level.INFO,
        "warn" to Level.WARN,
        "error" to Level.ERROR,
        "off" to Level.OFF,
    )

/**
 * Libraries whose debug lines would leak: HikariCP's name the JDBC URL and
 * user, jOOQ's carry SQL text. They log at Info at most, whatever LOG_LEVEL.
 */
private val quietLoggers = listOf("com.zaxxer.hikari", "org.jooq")

data object InvalidLogLevel {
    const val MESSAGE = "LOG_LEVEL must be trace, debug, info, warn, error or off"
}

/**
 * Installs every entry point's logging: one JSON object per line on standard
 * output (logback.xml), at `LOG_LEVEL` (`info` when unset or empty) and above,
 * with the MDC's fields, such as a request's `trace_id`. Libraries that log
 * through java.util.logging, such as pgjdbc, are routed to the same output.
 *
 * An invalid `LOG_LEVEL` still installs logging, at `info`, so the entry point
 * can report the error as JSON before it exits.
 */
fun initLogging(): Either<InvalidLogLevel, Unit> {
    SLF4JBridgeHandler.removeHandlersForRootLogger()
    SLF4JBridgeHandler.install()

    val level = parseLogLevel(System.getenv("LOG_LEVEL"))
    // The cast holds: logback-classic is the only SLF4J binding on the classpath.
    val context = LoggerFactory.getILoggerFactory() as LoggerContext

    val rootLevel = level.getOrNull() ?: Level.INFO

    context.getLogger(Logger.ROOT_LOGGER_NAME).level = rootLevel
    quietLoggers.forEach { name ->
        context.getLogger(name).level = if (rootLevel.isGreaterOrEqual(Level.INFO)) null else Level.INFO
    }

    return level.map { }
}

internal fun parseLogLevel(raw: String?): Either<InvalidLogLevel, Level> {
    if (raw.isNullOrEmpty()) {
        return Level.INFO.right()
    }

    return levels[raw.lowercase()]?.right() ?: InvalidLogLevel.left()
}

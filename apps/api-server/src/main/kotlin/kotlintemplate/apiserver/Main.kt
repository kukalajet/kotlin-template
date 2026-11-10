package kotlintemplate.apiserver

import arrow.core.getOrElse
import io.ktor.server.application.ServerReady
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlintemplate.api.AppState
import kotlintemplate.api.api
import kotlintemplate.config.AppConfig
import kotlintemplate.config.InvalidLogLevel
import kotlintemplate.config.initLogging
import kotlintemplate.database.Database
import kotlintemplate.database.InvalidDatabaseUrl
import kotlintemplate.database.PgDatabaseHealth
import kotlintemplate.database.PgUserRepository
import org.slf4j.LoggerFactory
import java.io.IOException
import kotlin.system.exitProcess

private val logger = LoggerFactory.getLogger("kotlintemplate.apiserver.Main")

/** In-flight requests get this long to finish after SIGTERM, within the stop timeout. */
private const val SHUTDOWN_GRACE_MS = 1_000L
private const val SHUTDOWN_TIMEOUT_MS = 10_000L

private fun fail(error: String): Nothing {
    logger.atError().addKeyValue("error", error).log("API failed")
    exitProcess(1)
}

/**
 * Serves the API until SIGINT or SIGTERM, then stops accepting requests,
 * lets in-flight ones finish and closes the pool; the JVM exits 130 or 143.
 * Exits 1 when it fails to start.
 */
fun main() {
    initLogging().onLeft {
        logger.atError().addKeyValue("error", InvalidLogLevel.MESSAGE).log("Invalid logging configuration")
        exitProcess(1)
    }

    val config = AppConfig.fromEnv().getOrElse { error -> fail(error.message) }
    val database = Database.open(config.database).getOrElse { fail(InvalidDatabaseUrl.MESSAGE) }

    // The adapters behind the ports the routes use, on one pool. Add a
    // feature's repository here when its routes need it.
    val state = AppState(users = PgUserRepository(database), health = PgDatabaseHealth(database))
    val server = embeddedServer(Netty, host = config.apiHost, port = config.apiPort) { api(state) }

    server.monitor.subscribe(ServerReady) {
        logger.atInfo().addKeyValue("address", "${config.apiHost}:${config.apiPort}").log("API listening")
    }

    Runtime.getRuntime().addShutdownHook(
        Thread {
            logger.info("API shutting down")
            server.stop(SHUTDOWN_GRACE_MS, SHUTDOWN_TIMEOUT_MS)
            database.close()
        },
    )

    try {
        server.start(wait = true)
    } catch (failure: IOException) {
        fail("Binding ${config.apiHost}:${config.apiPort} failed: ${failure::class.simpleName}")
    }
}

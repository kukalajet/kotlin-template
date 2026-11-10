package kotlintemplate.workerserver

import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.left
import kotlintemplate.config.AppConfig
import kotlintemplate.config.InvalidLogLevel
import kotlintemplate.config.initLogging
import kotlintemplate.database.Database
import kotlintemplate.database.InvalidDatabaseUrl
import kotlintemplate.database.PgUserRepository
import kotlintemplate.worker.JobError
import kotlintemplate.worker.WorkerDeps
import kotlintemplate.worker.pollJobs
import kotlintemplate.worker.runJobsOnce
import kotlintemplate.worker.workerJobs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

private val logger = LoggerFactory.getLogger("kotlintemplate.workerserver.Main")

/** How long SIGTERM waits for the running pass to stop and the pool to close. */
private const val SHUTDOWN_TIMEOUT_SECONDS = 10L

private enum class Mode { Poll, Once }

private fun fail(error: String): Nothing {
    logger.atError().addKeyValue("error", error).log("Worker failed")
    exitProcess(1)
}

/**
 * `worker` polls every job until one fails; `worker once` runs one pass of
 * every job, then exits. Exit codes: 0 when the work completes, 1 when it
 * fails; SIGINT or SIGTERM cancels the running pass, and the JVM exits 130 or
 * 143.
 */
fun main(args: Array<String>) {
    initLogging().onLeft {
        logger.atError().addKeyValue("error", InvalidLogLevel.MESSAGE).log("Invalid logging configuration")
        exitProcess(1)
    }

    val mode =
        when (args.toList()) {
            emptyList<String>() -> Mode.Poll
            listOf("once") -> Mode.Once
            else -> fail("Usage: worker [once]")
        }
    val config = AppConfig.fromEnv().getOrElse { error -> fail(error.message) }
    val database = Database.open(config.database).getOrElse { fail(InvalidDatabaseUrl.MESSAGE) }

    // The adapters behind the worker's ports. Add the adapters a new job's ports need here.
    val deps = WorkerDeps(users = PgUserRepository(database))
    val stopped = CountDownLatch(1)

    val outcome =
        runBlocking {
            val work =
                async {
                    when (mode) {
                        Mode.Once -> {
                            runJobsOnce(workerJobs, deps, config.workerPassTimeout)
                        }

                        Mode.Poll -> {
                            pollJobs(
                                workerJobs,
                                deps,
                                config.workerPollInterval,
                                config.workerPassTimeout,
                            ).left()
                        }
                    }
                }

            // Interruption is a normal shutdown, not a failure, so it logs nothing.
            Runtime.getRuntime().addShutdownHook(
                Thread {
                    work.cancel()
                    stopped.await(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                },
            )

            try {
                work.await()
            } catch (
                @Suppress("SwallowedException") interrupted: CancellationException,
            ) {
                null
            }
        }

    database.close()
    stopped.countDown()
    exit(outcome)
}

/** After a signal the JVM is already exiting, with the signal's code; calling exit then would block. */
private fun exit(outcome: Either<JobError, Unit>?) {
    if (outcome == null) {
        return
    }

    outcome.onLeft { failure -> failure.log(logger) }
    exitProcess(if (outcome.isRight()) 0 else 1)
}

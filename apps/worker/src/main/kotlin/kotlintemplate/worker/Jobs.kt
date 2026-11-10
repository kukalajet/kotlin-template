package kotlintemplate.worker

import arrow.core.Either
import arrow.core.raise.either
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("kotlintemplate.worker.Jobs")

private const val SAMPLE_SIZE = 5

/**
 * Every job the worker runs. Add a job here, and both polling and the
 * one-shot pass run it; WorkerDeps must hold the ports it needs.
 */
val workerJobs: List<Job> =
    listOf(
        Job("sample-users", ::sampleUsersPass),
    )

/**
 * A read-only example job: loads a few users and logs how many it found, at
 * Debug, because a line every few seconds would bury the default Info log.
 */
internal suspend fun sampleUsersPass(deps: WorkerDeps): Either<JobError, Unit> =
    either {
        val users =
            deps.users
                .list(SAMPLE_SIZE)
                .mapLeft { it.toJobError() }
                .bind()

        logger.atDebug().addKeyValue("user_count", users.size).log("Worker user sample loaded")
    }

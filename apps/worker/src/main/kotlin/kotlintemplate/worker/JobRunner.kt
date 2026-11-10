package kotlintemplate.worker

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.raise.ensure
import kotlintemplate.application.PersistenceFailure
import kotlintemplate.application.PersistenceReport
import kotlintemplate.application.UserRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.Logger
import org.slf4j.MDC
import java.util.UUID
import kotlin.time.Duration

/** Everything a job may use. Add the ports a new job needs here, and their adapters in worker-server. */
class WorkerDeps(
    val users: UserRepository,
)

/** A piece of recurring work the worker does, one pass at a time. */
class Job(
    /** Names the job in its pass timeout's error and in its pass's log lines. */
    val name: String,
    val pass: suspend (WorkerDeps) -> Either<JobError, Unit>,
)

sealed interface JobError {
    val message: String

    /** Reduced to its safe report as soon as a job sees it. */
    data class Persistence(
        val report: PersistenceReport,
    ) : JobError {
        override val message: String get() = "${report.error} failed during ${report.operation}"
    }

    data class TimedOut(
        val job: String,
        val timeout: Duration,
    ) : JobError {
        override val message: String
            get() = "Worker job $job ran longer than its ${timeout.inWholeMilliseconds} ms pass timeout"
    }

    /**
     * With no jobs, polling would end at once, the worker would exit 0, and
     * its supervisor would start it again, forever.
     */
    data object NoJobs : JobError {
        override val message: String get() = "The worker has no jobs"
    }

    /** One Error line, `Worker failed`, with a persistence failure's safe fields. */
    fun log(logger: Logger) {
        when (this) {
            is Persistence -> report.log(logger, "Worker failed")
            is TimedOut, NoJobs -> logger.atError().addKeyValue("error", message).log("Worker failed")
        }
    }
}

fun PersistenceFailure<*>.toJobError(): JobError = JobError.Persistence(report())

/**
 * A pass that never finishes would stall its loop without failing the
 * worker, so nothing would restart it. Past [timeout] the pass is cancelled,
 * which cancels its running statement, and fails with an error naming the
 * job. Each pass logs with a fresh `trace_id` and its job's name.
 */
private suspend fun runPass(
    job: Job,
    deps: WorkerDeps,
    timeout: Duration,
): Either<JobError, Unit> {
    val context = MDC.getCopyOfContextMap().orEmpty() + mapOf("trace_id" to newTraceId(), "job" to job.name)

    return withContext(MDCContext(context)) {
        withTimeoutOrNull(timeout) { job.pass(deps) } ?: JobError.TimedOut(job.name, timeout).left()
    }
}

private fun newTraceId(): String = UUID.randomUUID().toString().replace("-", "")

/** One pass of every job, in order; the first failure ends it, so later jobs do not run. */
suspend fun runJobsOnce(
    jobs: List<Job>,
    deps: WorkerDeps,
    passTimeout: Duration,
): Either<JobError, Unit> =
    either {
        ensure(jobs.isNotEmpty()) { JobError.NoJobs }

        for (job in jobs) {
            runPass(job, deps, passTimeout).bind()
        }
    }

/**
 * Each job polls on its own loop, so a slow job never delays another. A loop
 * waits [interval] after a pass finishes, so passes of one job never overlap.
 * Runs until the first failure, which cancels every other loop and is
 * returned; the process supervisor starts the worker again.
 */
suspend fun pollJobs(
    jobs: List<Job>,
    deps: WorkerDeps,
    interval: Duration,
    passTimeout: Duration,
): JobError {
    if (jobs.isEmpty()) {
        return JobError.NoJobs
    }

    return coroutineScope {
        val firstFailure = CompletableDeferred<JobError>()
        val loops = jobs.map { job -> launch { firstFailure.complete(pollJob(job, deps, interval, passTimeout)) } }
        val failure = firstFailure.await()

        loops.forEach { it.cancel() }

        failure
    }
}

/** Only ever returns a failure. */
private suspend fun pollJob(
    job: Job,
    deps: WorkerDeps,
    interval: Duration,
    passTimeout: Duration,
): JobError {
    while (true) {
        runPass(job, deps, passTimeout).onLeft { failure -> return failure }
        delay(interval)
    }
}

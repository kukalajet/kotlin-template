package kotlintemplate.worker

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import kotlintemplate.application.Diagnostics
import kotlintemplate.application.FindUserError
import kotlintemplate.application.InsertUserError
import kotlintemplate.application.UserOperation
import kotlintemplate.application.UserRepository
import kotlintemplate.application.UserRepositoryError
import kotlintemplate.domain.CreateUser
import kotlintemplate.domain.User
import kotlintemplate.domain.UserId
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

class JobRunnerTest {
    /** Only list is used by jobs; it fails when [failing]. */
    private class FakeUsers(
        private val failing: Boolean,
    ) : UserRepository {
        var listCalls = 0

        override suspend fun insert(input: CreateUser): Either<InsertUserError, User> = error("unused")

        override suspend fun findById(id: UserId): Either<FindUserError, User> = error("unused")

        override suspend fun list(limit: Int): Either<UserRepositoryError, List<User>> {
            listCalls += 1

            return if (failing) {
                UserRepositoryError(
                    UserOperation.List,
                    Diagnostics("TimeoutError"),
                ).left()
            } else {
                emptyList<User>().right()
            }
        }
    }

    private val passTimeout = 5.seconds

    @Test
    fun `one pass runs every job in order`() =
        runTest {
            val ran = mutableListOf<String>()
            val jobs =
                listOf("a", "b").map { name ->
                    Job(name) {
                        ran.add(name)
                        Unit.right()
                    }
                }

            assertEquals(Unit.right(), runJobsOnce(jobs, WorkerDeps(FakeUsers(failing = false)), passTimeout))
            assertEquals(listOf("a", "b"), ran)
        }

    @Test
    fun `the first failure stops later jobs`() =
        runTest {
            val ran = mutableListOf<String>()
            val jobs =
                listOf(
                    Job("sample-users", ::sampleUsersPass),
                    Job("later") {
                        ran.add("later")
                        Unit.right()
                    },
                )
            val result = runJobsOnce(jobs, WorkerDeps(FakeUsers(failing = true)), passTimeout)

            assertEquals("UserRepositoryError failed during list", result.leftOrNull()?.message)
            assertEquals(emptyList(), ran)
        }

    @Test
    fun `a pass past its timeout fails naming the job`() =
        runTest {
            val stuck = Job("stuck") { awaitCancellation() }

            assertEquals(
                JobError.TimedOut("stuck", passTimeout).left(),
                runJobsOnce(listOf(stuck), WorkerDeps(FakeUsers(failing = false)), passTimeout),
            )
        }

    @Test
    fun `no jobs is a failure`() =
        runTest {
            val deps = WorkerDeps(FakeUsers(failing = false))

            assertEquals(JobError.NoJobs.left(), runJobsOnce(emptyList(), deps, passTimeout))
            assertEquals(JobError.NoJobs, pollJobs(emptyList(), deps, 1.seconds, passTimeout))
        }

    @Test
    fun `polling repeats passes until one fails and stops every loop`() =
        runTest {
            var passes = 0
            val flaky = Job("flaky") { if (++passes < 3) Unit.right() else JobError.NoJobs.left() }
            val forever = Job("forever") { Unit.right() }

            val failure = pollJobs(listOf(forever, flaky), WorkerDeps(FakeUsers(failing = false)), 1.hours, passTimeout)

            assertEquals(JobError.NoJobs, failure)
            assertEquals(3, passes)
        }
}

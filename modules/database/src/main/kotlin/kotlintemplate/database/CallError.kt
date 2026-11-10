package kotlintemplate.database

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import kotlintemplate.application.Diagnostics
import kotlintemplate.application.Operation
import kotlintemplate.application.PersistenceFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeout
import org.jooq.Query
import org.jooq.exception.DataAccessException
import org.postgresql.util.PSQLException
import java.sql.SQLException
import java.sql.SQLTransientConnectionException
import java.util.concurrent.CompletableFuture
import kotlin.time.Duration

/**
 * Why a bounded database call failed. The driver's exception is reduced to
 * these fields as soon as it is caught. Adapters turn it into their port's
 * failure with [toFailure], unless it means a domain error, such as a unique
 * violation on a known constraint.
 */
internal sealed interface CallError {
    data class Failed(
        val diagnostics: Diagnostics,
        /** The constraint a unique violation names, if this is one. */
        val uniqueViolation: String?,
    ) : CallError

    data object TimedOut : CallError

    fun <Op : Operation> toFailure(operation: Op): PersistenceFailure<Op> =
        PersistenceFailure(
            operation,
            when (this) {
                is Failed -> diagnostics
                TimedOut -> Diagnostics("TimeoutError")
            },
        )
}

/**
 * Runs [query] with [run] on a thread for blocking calls, and gives up after
 * [limit] (see [databaseCallTimeout]). Giving up, or the caller's
 * cancellation, cancels the statement: pgjdbc sends PostgreSQL a cancel
 * request, so the server stops the query instead of running it to its own
 * statement timeout. The caller does not wait for the blocked thread.
 */
internal suspend fun <Q : Query, T> bounded(
    limit: Duration,
    query: Q,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    run: (Q) -> T,
): Either<CallError, T> {
    val running = CompletableFuture.supplyAsync({ run(query) }, dispatcher.asExecutor())

    return try {
        withTimeout(limit) { running.await() }.right()
    } catch (
        @Suppress("SwallowedException") timeout: TimeoutCancellationException,
    ) {
        query.cancel()

        CallError.TimedOut.left()
    } catch (cancelled: CancellationException) {
        query.cancel()

        throw cancelled
    } catch (failure: DataAccessException) {
        val sqlException = failure.getCause(SQLException::class.java)

        CallError.Failed(diagnosticsOf(sqlException), uniqueViolationOf(sqlException)).left()
    }
}

/**
 * Safe diagnostics from a driver exception. Messages are never copied: they
 * can contain SQL, parameters or stored values. The code is a SQLSTATE, such
 * as 57014.
 */
internal fun diagnosticsOf(exception: SQLException?): Diagnostics =
    when (exception) {
        null -> Diagnostics("UnknownError")

        // HikariCP's wait for a connection ran out; the SQLSTATE is the last
        // connection attempt's, such as 08001 when the server refuses it.
        is SQLTransientConnectionException -> Diagnostics("PoolTimedOut", exception.sqlState)

        else -> Diagnostics("DatabaseError", exception.sqlState)
    }

private const val UNIQUE_VIOLATION = "23505"

private fun uniqueViolationOf(exception: SQLException?): String? =
    (exception as? PSQLException)
        ?.takeIf { it.sqlState == UNIQUE_VIOLATION }
        ?.serverErrorMessage
        ?.constraint

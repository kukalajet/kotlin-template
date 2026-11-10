package kotlintemplate.database

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensureNotNull
import kotlintemplate.application.Diagnostics
import kotlintemplate.application.FindUserError
import kotlintemplate.application.InsertUserError
import kotlintemplate.application.UserOperation
import kotlintemplate.application.UserRepository
import kotlintemplate.application.UserRepositoryError
import kotlintemplate.database.schema.tables.records.UsersRecord
import kotlintemplate.database.schema.tables.references.USERS
import kotlintemplate.domain.CreateUser
import kotlintemplate.domain.DisplayName
import kotlintemplate.domain.Email
import kotlintemplate.domain.User
import kotlintemplate.domain.UserAlreadyExists
import kotlintemplate.domain.UserId
import kotlintemplate.domain.UserNotFound

/** The only unique violation that means the address is taken; any other stays a persistence failure. */
private const val USERS_EMAIL_KEY = "users_email_key"

class PgUserRepository(
    private val database: Database,
) : UserRepository {
    private val dsl get() = database.dsl

    override suspend fun insert(input: CreateUser): Either<InsertUserError, User> =
        either {
            val operation = UserOperation.Insert
            val query =
                dsl
                    .insertInto(USERS)
                    .set(USERS.EMAIL, input.email.value)
                    .set(USERS.DISPLAY_NAME, input.displayName.value)
                    .returning()
            val record =
                bounded(database.callLimit, query) { it.fetchSingle() }
                    .mapLeft { error ->
                        if (error is CallError.Failed && error.uniqueViolation == USERS_EMAIL_KEY) {
                            InsertUserError.AlreadyExists(UserAlreadyExists(input.email))
                        } else {
                            InsertUserError.Repository(error.toFailure(operation))
                        }
                    }.bind()

            record.toUser(operation).mapLeft(InsertUserError::Repository).bind()
        }

    override suspend fun findById(id: UserId): Either<FindUserError, User> =
        either {
            val operation = UserOperation.FindById
            val query = dsl.selectFrom(USERS).where(USERS.ID.eq(id.value))
            val record =
                bounded(database.callLimit, query) { it.fetchOne() }
                    .mapLeft { error -> FindUserError.Repository(error.toFailure(operation)) }
                    .bind()

            ensureNotNull(record) { FindUserError.NotFound(UserNotFound(id)) }
            record.toUser(operation).mapLeft(FindUserError::Repository).bind()
        }

    /**
     * Users inserted in one transaction share created_at (now() is the
     * transaction's start time). The id breaks those ties, so the order, and
     * which users fall under the limit, stays the same between requests.
     */
    override suspend fun list(limit: Int): Either<UserRepositoryError, List<User>> =
        either {
            val operation = UserOperation.List
            val query =
                dsl
                    .selectFrom(USERS)
                    .orderBy(USERS.CREATED_AT.desc(), USERS.ID.desc())
                    .limit(limit)
            val records =
                bounded(database.callLimit, query) { it.fetch() }
                    .mapLeft { error -> error.toFailure(operation) }
                    .bind()

            records.map { record -> record.toUser(operation).bind() }
        }
}

/**
 * Stored data that no longer passes the domain's checks is a persistence
 * failure naming the column, never a domain error.
 */
private fun UsersRecord.toUser(operation: UserOperation): Either<UserRepositoryError, User> =
    either {
        fun invalid(column: String) = UserRepositoryError(operation, Diagnostics("InvalidRow", column))

        User(
            id = UserId(ensureNotNull(id) { invalid("id") }),
            email = ensureNotNull(email?.let { Email.parse(it).getOrNull() }) { invalid("email") },
            displayName =
                ensureNotNull(
                    displayName?.let { DisplayName.parse(it).getOrNull() },
                ) { invalid("display_name") },
            createdAt = ensureNotNull(createdAt) { invalid("created_at") }.toInstant(),
        )
    }

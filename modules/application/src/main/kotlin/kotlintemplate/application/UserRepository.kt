package kotlintemplate.application

import arrow.core.Either
import kotlintemplate.domain.CreateUser
import kotlintemplate.domain.User
import kotlintemplate.domain.UserAlreadyExists
import kotlintemplate.domain.UserId
import kotlintemplate.domain.UserNotFound

enum class UserOperation(
    override val operationName: String,
) : Operation {
    Insert("insert"),
    FindById("findById"),
    List("list"),
    ;

    override val error: String get() = "UserRepositoryError"
}

typealias UserRepositoryError = PersistenceFailure<UserOperation>

/** Every way [UserRepository.insert] fails. A `when` over it must name each case. */
sealed interface InsertUserError {
    data class AlreadyExists(
        val error: UserAlreadyExists,
    ) : InsertUserError

    data class Repository(
        val failure: UserRepositoryError,
    ) : InsertUserError
}

/** Every way [UserRepository.findById] fails. */
sealed interface FindUserError {
    data class NotFound(
        val error: UserNotFound,
    ) : FindUserError

    data class Repository(
        val failure: UserRepositoryError,
    ) : FindUserError
}

interface UserRepository {
    suspend fun insert(input: CreateUser): Either<InsertUserError, User>

    suspend fun findById(id: UserId): Either<FindUserError, User>

    /** At most [limit] users, newest first. */
    suspend fun list(limit: Int): Either<UserRepositoryError, List<User>>
}

package kotlintemplate.database

import kotlintemplate.application.Diagnostics

/**
 * Why `migrate` failed, without the driver's messages: a failed migration can
 * be one that moves stored data, whose values the messages would repeat.
 */
sealed interface MigrationError {
    val message: String

    data object InvalidUrl : MigrationError {
        override val message: String get() = InvalidDatabaseUrl.MESSAGE
    }

    data class Database(
        val diagnostics: Diagnostics,
    ) : MigrationError {
        override val message: String get() = diagnostics.reason + diagnostics.code?.let { " ($it)" }.orEmpty()
    }

    /** Flyway's own checks, such as a changed or missing migration. Their messages name migrations only. */
    data class Migrations(
        override val message: String,
    ) : MigrationError

    /** Any other Flyway failure, named by its class: its message could quote the database. */
    data class Other(
        override val message: String,
    ) : MigrationError
}

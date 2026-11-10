package kotlintemplate.database

import arrow.core.left
import kotlintemplate.application.Diagnostics
import kotlintemplate.application.FindUserError
import kotlintemplate.application.InsertUserError
import kotlintemplate.application.UserOperation
import kotlintemplate.application.UserRepositoryError
import kotlintemplate.config.DatabaseSettings
import kotlintemplate.config.Redacted
import kotlintemplate.domain.CreateUser
import kotlintemplate.domain.DisplayName
import kotlintemplate.domain.Email
import kotlintemplate.domain.UserAlreadyExists
import kotlintemplate.domain.UserId
import kotlintemplate.domain.UserNotFound
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** The adapters against PostgreSQL in a container: `./gradlew :database:postgresTest`. */
@Tag("postgres")
class PostgresTest {
    companion object {
        private val postgres = PostgreSQLContainer("postgres:18-alpine")
        private lateinit var settings: DatabaseSettings
        private lateinit var database: Database

        @JvmStatic
        @BeforeAll
        fun start() {
            postgres.start()
            settings = settingsFor(postgres.host, postgres.firstMappedPort, statementTimeoutMs = 10_000)
            assertTrue(migrate(settings).isRight())
            database = Database.open(settings).getOrNull()!!
        }

        @JvmStatic
        @AfterAll
        fun stop() {
            database.close()
            postgres.stop()
        }

        private fun settingsFor(
            host: String,
            port: Int,
            statementTimeoutMs: Long,
        ) = DatabaseSettings(
            Redacted("postgresql://${postgres.username}:${postgres.password}@$host:$port/${postgres.databaseName}"),
            statementTimeoutMs,
        )

        private fun sql(statement: String): Unit =
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { it.execute(statement) }
            }

        private fun count(query: String): Int =
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(query).use { result ->
                        result.next()
                        result.getInt(1)
                    }
                }
            }
    }

    private val users = PgUserRepository(database)

    private fun newUser(email: String) =
        CreateUser(Email.parse(email).getOrNull()!!, DisplayName.parse("Ada Lovelace").getOrNull()!!)

    @BeforeEach
    fun truncate() = sql("truncate users")

    @Test
    fun `migrating again changes nothing`() {
        assertTrue(migrate(settings).isRight())
    }

    @Test
    fun `inserted users are found and listed newest first`() =
        runBlocking {
            val first = users.insert(newUser("ada@example.com")).getOrNull()!!
            val second = users.insert(newUser("grace@example.com")).getOrNull()!!

            assertEquals(first, users.findById(first.id).getOrNull())
            assertEquals(listOf(second, first), users.list(10).getOrNull())
            assertEquals(listOf(second), users.list(1).getOrNull())
        }

    @Test
    fun `a taken email is a domain error`() =
        runBlocking {
            val input = newUser("ada@example.com")

            assertTrue(users.insert(input).isRight())
            assertEquals(InsertUserError.AlreadyExists(UserAlreadyExists(input.email)).left(), users.insert(input))
        }

    @Test
    fun `an unknown id is not found`() =
        runBlocking {
            val id = UserId(UUID.randomUUID())

            assertEquals(FindUserError.NotFound(UserNotFound(id)).left(), users.findById(id))
        }

    @Test
    fun `a stored row the domain rejects is a persistence failure naming the column`() =
        runBlocking {
            sql("insert into users (email, display_name) values ('not-an-email', 'Ada')")

            assertEquals(
                UserRepositoryError(UserOperation.List, Diagnostics("InvalidRow", "email")).left(),
                users.list(10),
            )
        }

    @Test
    fun `readiness passes`() =
        runBlocking {
            assertTrue(PgDatabaseHealth(database).check().isRight())
        }

    @Test
    fun `a call that gives up cancels its statement on the server`() =
        runBlocking {
            val result = bounded(1.seconds, database.dsl.query("select pg_sleep(30)")) { it.execute() }

            assertEquals(CallError.TimedOut.left(), result)

            val sleeping =
                "select count(*) from pg_stat_activity where state = 'active' and query like 'select pg_sleep%'"
            var remaining = count(sleeping)

            repeat(20) {
                if (remaining > 0) {
                    delay(100)
                    remaining = count(sleeping)
                }
            }

            assertEquals(0, remaining)
        }

    @Test
    fun `PostgreSQL ends a statement at the statement timeout`() =
        runBlocking {
            val shortTimeout = settingsFor(postgres.host, postgres.firstMappedPort, statementTimeoutMs = 200)

            Database.open(shortTimeout).getOrNull()!!.use { impatient ->
                val result = bounded(impatient.callLimit, impatient.dsl.query("select pg_sleep(5)")) { it.execute() }

                assertEquals(CallError.Failed(Diagnostics("DatabaseError", "57014"), null).left(), result)
            }
        }

    @Test
    fun `an unreachable database fails after the pool wait`() =
        runBlocking {
            Database.open(settingsFor("127.0.0.1", 1, statementTimeoutMs = 10_000)).getOrNull()!!.use { unreachable ->
                val failure = PgUserRepository(unreachable).list(1).leftOrNull()

                assertIs<UserRepositoryError>(failure)
                assertEquals("PoolTimedOut", failure.diagnostics.reason)
            }
        }
}

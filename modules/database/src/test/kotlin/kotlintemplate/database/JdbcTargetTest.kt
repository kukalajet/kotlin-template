package kotlintemplate.database

import arrow.core.left
import kotlintemplate.application.Diagnostics
import java.sql.SQLException
import java.sql.SQLTransientConnectionException
import kotlin.test.Test
import kotlin.test.assertEquals

class JdbcTargetTest {
    @Test
    fun `splits credentials out of the URL and keeps its parameters`() {
        val target = JdbcTarget.parse("postgresql://app:p%40ss+word@db.internal/app?sslmode=require").getOrNull()

        assertEquals("app", target?.user)
        assertEquals("p@ss+word", target?.password)
        assertEquals(
            "jdbc:postgresql://db.internal:5432/app?sslmode=require&ApplicationName=a+b",
            target?.url(mapOf("ApplicationName" to "a b")),
        )
    }

    @Test
    fun `rejects URLs pgjdbc cannot use`() {
        val invalid = listOf("", "not a url", "mysql://localhost/app", "postgresql://localhost", "postgresql:///app")

        for (url in invalid) {
            assertEquals(InvalidDatabaseUrl.left(), JdbcTarget.parse(url), url)
        }
    }

    @Test
    fun `diagnostics keep the SQLSTATE and drop the message`() {
        val exception = SQLException("duplicate key value (email)=(ada@example.com)", "23505")

        assertEquals(Diagnostics("DatabaseError", "23505"), diagnosticsOf(exception))
        assertEquals(
            Diagnostics("PoolTimedOut", "08001"),
            diagnosticsOf(SQLTransientConnectionException("request timed out", "08001")),
        )
        assertEquals(Diagnostics("UnknownError"), diagnosticsOf(null))
    }
}

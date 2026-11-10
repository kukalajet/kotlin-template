package kotlintemplate.domain

import arrow.core.left
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UserTest {
    @Test
    fun `email is trimmed`() {
        val email = Email.parse("  ada@example.com ").getOrNull()

        assertEquals("ada@example.com", email?.value)
    }

    @Test
    fun `email rejects malformed addresses`() {
        val malformed =
            listOf(
                "",
                "ada",
                "@example.com",
                "ada@",
                "ada@example",
                "ada@.com",
                "ada@example.",
                "ada@@example.com",
                "a da@example.com",
                "ada@exa\u0007mple.com",
                "ada@exa mple.com",
            )

        for (raw in malformed) {
            assertEquals(InvalidEmail.left(), Email.parse(raw), raw)
        }
    }

    @Test
    fun `email has a length limit`() {
        val atLimit = "a".repeat(EMAIL_MAX_CHARS - 12) + "@example.com"

        assertTrue(Email.parse(atLimit).isRight())
        assertEquals(InvalidEmail.left(), Email.parse("a$atLimit"))
    }

    @Test
    fun `display name counts characters not UTF-16 units`() {
        val emoji = "😀"

        assertTrue(DisplayName.parse(emoji.repeat(DISPLAY_NAME_MAX_CHARS)).isRight())
        assertEquals(InvalidDisplayName.left(), DisplayName.parse(emoji.repeat(DISPLAY_NAME_MAX_CHARS + 1)))
    }

    @Test
    fun `display name rejects blank and control characters`() {
        for (raw in listOf("   ", "Ada\u0000", "Ada\nLovelace")) {
            assertEquals(InvalidDisplayName.left(), DisplayName.parse(raw), raw)
        }
    }
}

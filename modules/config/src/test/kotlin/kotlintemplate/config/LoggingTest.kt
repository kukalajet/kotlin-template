package kotlintemplate.config

import arrow.core.left
import arrow.core.right
import ch.qos.logback.classic.Level
import kotlin.test.Test
import kotlin.test.assertEquals

class LoggingTest {
    @Test
    fun `unset and empty mean info`() {
        assertEquals(Level.INFO.right(), parseLogLevel(null))
        assertEquals(Level.INFO.right(), parseLogLevel(""))
    }

    @Test
    fun `levels ignore case`() {
        assertEquals(Level.DEBUG.right(), parseLogLevel("Debug"))
        assertEquals(Level.OFF.right(), parseLogLevel("OFF"))
    }

    @Test
    fun `unknown levels are rejected`() {
        assertEquals(InvalidLogLevel.left(), parseLogLevel("verbose"))
    }
}

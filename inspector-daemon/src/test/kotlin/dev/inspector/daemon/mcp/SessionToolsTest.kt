package dev.inspector.daemon.mcp

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/** `list_sessions` and `session_summary`, from [SessionTools]. */
class SessionToolsTest : McpToolsFixture() {

    @Test
    fun session_summary_gains_signal_counts_and_stays_small() {
        val text = tools.call("session_summary", args("session" to "latest")).text()
        assertContains(text, "signalsByTag")
        assertContains(text, "currentScreen")
        assertContains(text, "KycSubmit")
        assertContains(text, "cache/response", message = "a pull proves a provider exists")
        assertTrue(text.length < 4096, "the digest must stay around a kilobyte, was ${text.length}")
    }
}

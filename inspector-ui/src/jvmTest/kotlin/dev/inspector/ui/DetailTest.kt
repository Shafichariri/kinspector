package dev.inspector.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.inspector.model.NetworkTransaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The detail screen: which face it opens on, what its header says, and the two things a copy
 * menu and a redaction line must get right.
 */
@OptIn(ExperimentalTestApi::class)
class DetailTest {

    private val txn = NetworkTransaction(
        id = "a", ts = "2026-09-22T09:41:07.412Z", mono = 100, method = "GET",
        scheme = "https", host = "api.example.com", path = "/v3/accounts/balance", status = 200, callId = "a",
        reqHeaders = mapOf("Authorization" to listOf("‹redacted›"), "Accept" to listOf("application/json")),
        redacted = listOf("header:authorization"),
    )

    private fun ComposeUiTest.show(onCopy: (String) -> Unit = {}) {
        setContent {
            var tab by mutableStateOf(DetailTab.Response)
            InspectorTheme(dark = true) {
                InspectorDetail(
                    txn = txn, siblings = listOf(txn),
                    requestBody = null, responseBody = """{"ok":true}""".encodeToByteArray(),
                    onSelectSibling = {}, onCopy = onCopy, onBack = {}, onClose = {},
                    tab = tab, onTab = { tab = it },
                )
            }
        }
    }

    private fun ComposeUiTest.leftOf(label: String): Float =
        onNode(hasText(label) and hasClickAction()).fetchSemanticsNode().boundsInRoot.left

    @Test
    fun `it opens on the response and the tabs read response then request then overview`() = runComposeUiTest {
        show()
        // Opened for the body, so the body is what shows — asserted by the body being on screen,
        // not by a tab looking selected.
        onNodeWithText("\"ok\"", substring = true).assertExists()
        assertTrue(leftOf("Response") < leftOf("Request"))
        assertTrue(leftOf("Request") < leftOf("Overview"))
    }

    @Test
    fun `the header says what the call is and the status`() = runComposeUiTest {
        show()
        onNodeWithText("/v3/accounts/balance").assertExists()
        onNodeWithText("200").assertExists()
    }

    @Test
    fun `a redacted header says so rather than showing the placeholder as a value`() = runComposeUiTest {
        show()
        onNode(hasText("Request") and hasClickAction()).performClick()
        waitForIdle()
        onNodeWithText("redacted at capture").assertExists()
        assertEquals(0, onAllNodesWithText("‹redacted›").fetchSemanticsNodes().size)
        // Only the redacted one: every other header still shows its value.
        onNodeWithText("application/json").assertExists()
    }

    @Test
    fun `started reads as the list clock with the absolute moment beneath`() = runComposeUiTest {
        show()
        onNode(hasText("Overview") and hasClickAction()).performClick()
        waitForIdle()
        onNodeWithText(formatClock(txn.ts)).assertExists()
        onNodeWithText("22 Sep 2026 · 09:41:07.412 UTC").assertExists()
    }

    @Test
    fun `copy curl copies the request and says it did`() = runComposeUiTest {
        var copied: String? = null
        show(onCopy = { copied = it })
        onNode(hasContentDescription("more actions")).performClick()
        waitForIdle()
        onNode(hasText("Copy cURL") and hasClickAction()).performClick()
        mainClock.advanceTimeBy(100)
        // What was copied is the thing checked, not the label: a menu that said "Copied" and put
        // nothing on the clipboard is exactly the failure a label assertion would miss.
        assertEquals(toCurl(txn, null), copied)
        onNodeWithText("Copied").assertExists()
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        assertEquals(0, onAllNodesWithText("Copy URL").fetchSemanticsNodes().size)
    }
}

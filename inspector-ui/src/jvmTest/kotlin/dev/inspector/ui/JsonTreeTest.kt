package dev.inspector.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.inspector.model.NetworkTransaction
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The tree on the detail screen, driven by taps. Judged by which lines are on screen and what
 * lands on the clipboard — never by an arrow flipping, which a fold wired to nothing would pass.
 */
@OptIn(ExperimentalTestApi::class)
class JsonTreeTest {

    private val body = """{"id":1234567890123456789,"user":{"name":"Ada","roles":["admin","dev"]},"cursor":"abc"}"""

    private fun txn(truncated: Boolean = false) = NetworkTransaction(
        id = "a", ts = "2026-09-22T09:41:07.412Z", mono = 100, method = "GET",
        scheme = "https", host = "api.example.com", path = "/v1/me", status = 200, callId = "a",
        resContentType = "application/json", resBytes = body.length.toLong(), resBodyTruncated = truncated,
    )

    private fun ComposeUiTest.show(
        txn: NetworkTransaction = txn(),
        responseBody: String = body,
        states: JsonTreeStates = JsonTreeStates(),
        onCopy: (String) -> Unit = {},
    ) {
        setContent {
            InspectorTheme(dark = true) {
                InspectorDetail(
                    txn = txn, siblings = listOf(txn),
                    requestBody = null, responseBody = responseBody.encodeToByteArray(),
                    onSelectSibling = {}, onCopy = onCopy, onBack = {}, onClose = {},
                    treeStates = states,
                )
            }
        }
    }

    private fun ComposeUiTest.lineWith(fragment: String) =
        onNode(hasText(fragment, substring = true) and hasClickAction())

    @Test
    fun `tapping a container folds it to one line and tapping again opens it`() = runComposeUiTest {
        show()
        onNodeWithText("\"Ada\"", substring = true).assertExists()
        lineWith("\"user\": {").performClick()
        waitForIdle()
        onNodeWithText("{ 2 fields }", substring = true).assertExists()
        assertEquals(0, onAllNodesWithText("\"Ada\"", substring = true).fetchSemanticsNodes().size)
        lineWith("{ 2 fields }").performClick()
        waitForIdle()
        onNodeWithText("\"Ada\"", substring = true).assertExists()
    }

    @Test
    fun `collapse folds everything but the root and expand opens it all`() = runComposeUiTest {
        show()
        onNode(hasText("collapse") and hasClickAction()).performClick()
        waitForIdle()
        onNodeWithText("{ 2 fields }", substring = true).assertExists()
        // The root stays open: a tree folded to a single line says nothing.
        onNodeWithText("\"cursor\"", substring = true).assertExists()
        onNode(hasText("expand") and hasClickAction()).performClick()
        waitForIdle()
        onNodeWithText("\"dev\"", substring = true).assertExists()
    }

    @Test
    fun `copy is the whole body however much is folded`() = runComposeUiTest {
        var copied: String? = null
        show(onCopy = { copied = it })
        onNode(hasText("collapse") and hasClickAction()).performClick()
        waitForIdle()
        onNode(hasText("copy") and hasClickAction()).performClick()
        waitForIdle()
        assertEquals(prettyJson(body), copied)
    }

    @Test
    fun `raw shows the text and the id keeps every digit either way`() = runComposeUiTest {
        show()
        onNodeWithText("1234567890123456789", substring = true).assertExists()
        onNode(hasText("raw") and hasClickAction()).performClick()
        waitForIdle()
        onNodeWithText(prettyJson(body)).assertExists()
    }

    @Test
    fun `folding survives leaving the body and coming back`() = runComposeUiTest {
        val states = JsonTreeStates()
        var shown by mutableStateOf(true)
        setContent {
            InspectorTheme(dark = true) {
                if (shown) {
                    val t = txn()
                    InspectorDetail(
                        txn = t, siblings = listOf(t), requestBody = null, responseBody = body.encodeToByteArray(),
                        onSelectSibling = {}, onCopy = {}, onBack = {}, onClose = {}, treeStates = states,
                    )
                }
            }
        }
        lineWith("\"user\": {").performClick()
        waitForIdle()
        shown = false
        waitForIdle()
        shown = true
        waitForIdle()
        onNodeWithText("{ 2 fields }", substring = true).assertExists()
    }

    @Test
    fun `a truncated body is text and never a tree`() = runComposeUiTest {
        // A prefix that happens to parse must not be drawn as the whole body.
        show(txn = txn(truncated = true))
        assertEquals(0, onAllNodesWithText("collapse").fetchSemanticsNodes().size)
        onNodeWithText("truncated at", substring = true).assertExists()
    }
}

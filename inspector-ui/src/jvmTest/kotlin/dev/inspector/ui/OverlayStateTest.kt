package dev.inspector.ui

import androidx.compose.material3.Text
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test

/**
 * The overlay's own wiring, through the real [InspectorOverlay] and its pill.
 *
 * `ListControlsTest` proves the list honours a state it is handed. This proves the overlay hands it
 * one that outlives a close — the half that was actually broken, since the list used to own its
 * arrangement and lost it every time the reader went back to the app.
 */
@OptIn(ExperimentalTestApi::class)
class OverlayStateTest {

    private fun ComposeUiTest.openFromPill() {
        // The pill takes taps through a gesture detector rather than a click action.
        onNodeWithText("no traffic yet").performTouchInput { click() }
        waitForIdle()
    }

    private fun ComposeUiTest.control(label: String) = onNode(hasText(label) and hasClickAction())

    @Test
    fun `closing and reopening keeps the filter and the order and thaws the list`() = runComposeUiTest {
        setContent { InspectorOverlay { Text("the app") } }

        openFromPill()
        onNode(hasSetTextAction()).performTextInput("path:/v2")
        control("newest first").performClick()
        control("live").performClick()
        waitForIdle()
        control("frozen").assertExists()

        control("✕ close").performClick()
        waitForIdle()
        openFromPill()

        onNode(hasSetTextAction()).assert(hasText("path:/v2"))
        control("oldest first").assertExists()
        control("live").assertExists()
    }
}

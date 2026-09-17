package dev.inspector.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.inspector.model.Marker
import dev.inspector.model.Signal
import dev.inspector.model.NetworkTransaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two controls whose whole behaviour is a transition.
 *
 * `ListRenderTest` photographs one state and can never show the step between two, and the state
 * lives inside the composable where no unit test can reach it — so these tap the chips the way a
 * thumb would. The first interaction tests in this module; `compose.uiTest` is a jvmTest-only
 * dependency and is never published, same as skiko.
 */
@OptIn(ExperimentalTestApi::class)
class ListControlsTest {

    private fun txn(id: String, path: String, mono: Long) = NetworkTransaction(
        id = id, ts = "2026-09-17T09:00:00Z", mono = mono, method = "GET",
        scheme = "https", host = "api.example.com", path = path, status = 200, callId = id,
    )

    private val first = txn("a", "/v1/alpha", 100)
    private val second = txn("b", "/v1/beta", 200)

    /**
     * A chip by its label, and never the marker divider that carries the same text.
     *
     * A marker named `checkout` appears twice on screen by design — once as a chip and once as the
     * rule across the list — so matching on text alone finds two nodes. The chip is the one that
     * can be clicked, which is also the only difference that means anything here.
     */
    private fun ComposeUiTest.chip(label: String) = onNode(hasText(label) and hasClickAction())

    @Test
    fun `the order chip names the order the list is currently in`() = runComposeUiTest {
        setContent {
            InspectorTheme(dark = true) {
                InspectorList(
                    transactions = listOf(first, second),
                    markers = emptyList(),
                    onSelect = {}, onClear = {}, onMark = {}, onClose = {},
                )
            }
        }
        // Names the state, not the effect. A phone has no tooltip, so a control labelled with what
        // a tap would do leaves the reader unable to tell which way round the list already is.
        assertTrue(topOf("/v1/alpha") < topOf("/v1/beta"), "oldest first should put alpha above beta")

        chip("oldest first").performClick()
        waitForIdle()
        chip("newest first").assertExists()
        // The label flipping is not the claim worth testing — the rows moving is. A toggle wired
        // to nothing at all would pass an assertion about its own text.
        assertTrue(topOf("/v1/beta") < topOf("/v1/alpha"), "newest first should put beta above alpha")

        chip("newest first").performClick()
        waitForIdle()
        chip("oldest first").assertExists()
        assertTrue(topOf("/v1/alpha") < topOf("/v1/beta"), "the order should restore")
    }

    /** Where a row is drawn, so "above" can be asserted rather than assumed from a label. */
    private fun ComposeUiTest.topOf(path: String): Float =
        onNodeWithText(path).fetchSemanticsNode().boundsInRoot.top

    /**
     * Freezing has to hold the *rows*, not merely stop redrawing.
     *
     * Capture keeps running while the list is held, and the ring keeps evicting. A freeze
     * implemented as a flag would let the reader's rows be replaced underneath them, which is
     * precisely the thing they froze the list to prevent.
     */
    @Test
    fun `a frozen list does not take new traffic`() = runComposeUiTest {
        val live = mutableStateOf(listOf(first))
        setContent {
            InspectorTheme(dark = true) {
                InspectorList(
                    transactions = live.value,
                    markers = emptyList(),
                    onSelect = {}, onClear = {}, onMark = {}, onClose = {},
                )
            }
        }
        onNodeWithText("/v1/alpha").assertExists()

        chip("live").performClick()
        chip("frozen").assertExists()

        live.value = listOf(first, second)
        waitForIdle()
        assertEquals(0, onAllNodesWithText("/v1/beta").fetchSemanticsNodes().size)
        onNodeWithText("/v1/alpha").assertExists()

        // Thawing catches up rather than resuming from where it stopped: the reader wants what is
        // happening now, and the rows they were reading are in the ring either way.
        chip("frozen").performClick()
        waitForIdle()
        onNodeWithText("/v1/beta").assertExists()
    }

    @Test
    fun `a quick filter chip applies its term and a second tap clears it`() = runComposeUiTest {
        val failing = txn("c", "/v1/broken", 300).copy(status = 500)
        setContent {
            InspectorTheme(dark = true) {
                InspectorList(
                    transactions = listOf(first, failing),
                    markers = emptyList(),
                    onSelect = {}, onClear = {}, onMark = {}, onClose = {},
                )
            }
        }
        onNodeWithText("/v1/alpha").assertExists()

        chip("5xx").performClick()
        waitForIdle()
        assertEquals(0, onAllNodesWithText("/v1/alpha").fetchSemanticsNodes().size)
        onNodeWithText("/v1/broken").assertExists()

        // A chip that can only be turned on is a trap on a surface with no obvious way to select
        // and delete the text it put in the field.
        chip("5xx").performClick()
        waitForIdle()
        onNodeWithText("/v1/alpha").assertExists()
    }

    @Test
    fun `a marker chip filters to what happened after it`() = runComposeUiTest {
        setContent {
            InspectorTheme(dark = true) {
                InspectorList(
                    transactions = listOf(first, second),
                    markers = listOf(
                        Marker(ts = "2026-09-17T09:00:00Z", mono = 150, label = "checkout", source = "user"),
                    ),
                    onSelect = {}, onClear = {}, onMark = {}, onClose = {},
                )
            }
        }
        onNodeWithText("/v1/alpha").assertExists()

        chip("checkout").performClick()
        waitForIdle()
        assertEquals(0, onAllNodesWithText("/v1/alpha").fetchSemanticsNodes().size)
        onNodeWithText("/v1/beta").assertExists()
    }

    @Test
    fun `an endpoint chip matches only paths ending in its segment`() = runComposeUiTest {
        // The case a substring match would get wrong: `beta` must not also keep `/v1/beta/detail`.
        val nested = txn("c", "/v1/beta/detail", 300)
        setContent {
            InspectorTheme(dark = true) {
                InspectorList(
                    transactions = listOf(second, nested, nested.copy(id = "d", mono = 400)),
                    markers = emptyList(),
                    onSelect = {}, onClear = {}, onMark = {}, onClose = {},
                )
            }
        }
        // `detail` leads the chips: two calls to one, one to the other.
        chip("beta 1").performClick()
        waitForIdle()
        assertEquals(0, onAllNodesWithText("/v1/beta/detail").fetchSemanticsNodes().size)
        onNodeWithText("/v1/beta").assertExists()
    }
    private var signalSeq = 0

    private fun signal(tag: String, name: String, mono: Long) = Signal(
        id = "s${++signalSeq}", ts = "2026-09-17T09:00:00Z", mono = mono, tag = tag, name = name,
    )

    @Test
    fun `signals are on when a session has them and one tap hides them`() = runComposeUiTest {
        setContent {
            InspectorTheme(dark = true) {
                InspectorList(
                    transactions = listOf(first),
                    markers = emptyList(),
                    signals = listOf(signal("screen", "dashboard", 150)),
                    onSelect = {}, onClear = {}, onMark = {}, onClose = {},
                )
            }
        }
        // On by default, mirroring the web opening a session with signals on its merged view.
        onNodeWithText("dashboard").assertExists()

        chip("signals 1").performClick()
        waitForIdle()
        assertEquals(0, onAllNodesWithText("dashboard").fetchSemanticsNodes().size)
        // The traffic is untouched — hiding signals is not a filter on the calls.
        onNodeWithText("/v1/alpha").assertExists()

        chip("signals off").performClick()
        waitForIdle()
        onNodeWithText("dashboard").assertExists()
    }

    @Test
    fun `a session with no signals offers no toggle`() = runComposeUiTest {
        setContent {
            InspectorTheme(dark = true) {
                InspectorList(
                    transactions = listOf(first),
                    markers = emptyList(),
                    onSelect = {}, onClear = {}, onMark = {}, onClose = {},
                )
            }
        }
        // A control for something the session does not contain can only disappoint, and the strip
        // has no room for one.
        assertEquals(0, onAllNodesWithText("signals 0").fetchSemanticsNodes().size)
        assertEquals(0, onAllNodesWithText("signals off").fetchSemanticsNodes().size)
    }

    /**
     * The behaviour that keeps a chatty app from burying its own traffic.
     *
     * Four adjacent observations of one state holder collapse to a single row carrying the count
     * and the span; tapping it shows the members. One real session had 48 in a row, which on a
     * phone is the entire screen.
     */
    @Test
    fun `a run of identical observations collapses and expands on tap`() = runComposeUiTest {
        setContent {
            InspectorTheme(dark = true) {
                InspectorList(
                    transactions = listOf(first),
                    markers = emptyList(),
                    signals = listOf(
                        signal("state", "form", 200),
                        signal("state", "form", 210),
                        signal("state", "form", 240),
                        signal("state", "form", 290),
                    ),
                    onSelect = {}, onClear = {}, onMark = {}, onClose = {},
                )
            }
        }
        // One row, not four, and it says how many and over how long.
        assertEquals(1, onAllNodesWithText("form").fetchSemanticsNodes().size)
        onNodeWithText("×4 / 90ms").assertExists()

        onNodeWithText("×4 / 90ms").performClick()
        waitForIdle()
        // The header stays, with its four members under it.
        assertEquals(5, onAllNodesWithText("form").fetchSemanticsNodes().size)

        onNodeWithText("×4 / 90ms").performClick()
        waitForIdle()
        assertEquals(1, onAllNodesWithText("form").fetchSemanticsNodes().size)
    }

    @Test
    fun `two adjacent observations are not worth collapsing`() = runComposeUiTest {
        setContent {
            InspectorTheme(dark = true) {
                InspectorList(
                    transactions = listOf(first),
                    markers = emptyList(),
                    signals = listOf(signal("state", "form", 200), signal("state", "form", 210)),
                    onSelect = {}, onClear = {}, onMark = {}, onClose = {},
                )
            }
        }
        // Collapsing two rows hides as much as it saves.
        assertEquals(2, onAllNodesWithText("form").fetchSemanticsNodes().size)
    }

    @Test
    fun `freezing holds the signals too`() = runComposeUiTest {
        val live = mutableStateOf(listOf(signal("screen", "dashboard", 150)))
        setContent {
            InspectorTheme(dark = true) {
                InspectorList(
                    transactions = listOf(first),
                    markers = emptyList(),
                    signals = live.value,
                    onSelect = {}, onClear = {}, onMark = {}, onClose = {},
                )
            }
        }
        chip("live").performClick()
        waitForIdle()

        live.value = live.value + signal("screen", "settings", 400)
        waitForIdle()
        // A list frozen for traffic and live for signals is frozen in the one way nobody wants.
        assertEquals(0, onAllNodesWithText("settings").fetchSemanticsNodes().size)
    }

}

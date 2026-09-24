package dev.inspector.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
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
                    onSelect = {}, onSelectSignal = {}, onClear = {}, onMark = {}, onClose = {},
                )
            }
        }
        // Newest first by default: the overlay is opened to see what the app just sent.
        // Names the state, not the effect. A phone has no tooltip, so a control labelled with what
        // a tap would do leaves the reader unable to tell which way round the list already is.
        assertTrue(topOf("/v1/beta") < topOf("/v1/alpha"), "newest first should put beta above alpha")

        orderControl(newestFirst = true).performClick()
        waitForIdle()
        orderControl(newestFirst = false).assertExists()
        // The label flipping is not the claim worth testing — the rows moving is. A toggle wired
        // to nothing at all would pass an assertion about its own text.
        assertTrue(topOf("/v1/alpha") < topOf("/v1/beta"), "oldest first should put alpha above beta")

        orderControl(newestFirst = false).performClick()
        waitForIdle()
        orderControl(newestFirst = true).assertExists()
        assertTrue(topOf("/v1/beta") < topOf("/v1/alpha"), "the order should restore")
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
                    onSelect = {}, onSelectSignal = {}, onClear = {}, onMark = {}, onClose = {},
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
                    onSelect = {}, onSelectSignal = {}, onClear = {}, onMark = {}, onClose = {},
                )
            }
        }
        onNodeWithText("/v1/alpha").assertExists()

        openFilters()
        sheetChip("5xx").performClick()
        waitForIdle()
        assertEquals(0, onAllNodesWithText("/v1/alpha").fetchSemanticsNodes().size)
        onNodeWithText("/v1/broken").assertExists()

        // A chip that can only be turned on is a trap on a surface with no obvious way to select
        // and delete the text it put in the field.
        sheetChip("5xx").performClick()
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
                    onSelect = {}, onSelectSignal = {}, onClear = {}, onMark = {}, onClose = {},
                )
            }
        }
        onNodeWithText("/v1/alpha").assertExists()

        openFilters()
        sheetChip("checkout").performClick()
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
                    onSelect = {}, onSelectSignal = {}, onClear = {}, onMark = {}, onClose = {},
                )
            }
        }
        // `detail` leads the chips: two calls to one, one to the other.
        openFilters()
        sheetChip("beta").performClick()
        waitForIdle()
        assertEquals(0, onAllNodesWithText("/v1/beta/detail").fetchSemanticsNodes().size)
        onNodeWithText("/v1/beta").assertExists()
    }
    private var signalSeq = 0

    private fun signal(tag: String, name: String, mono: Long) = Signal(
        id = "s${++signalSeq}", ts = "2026-09-17T09:00:00Z", mono = mono, tag = tag, name = name,
    )

    @Test
    fun `signals are hidden by default and one tap shows them`() = runComposeUiTest {
        setContent {
            InspectorTheme(dark = true) {
                InspectorList(
                    transactions = listOf(first),
                    markers = emptyList(),
                    signals = listOf(signal("screen", "dashboard", 150)),
                    onSelect = {}, onSelectSignal = {}, onClear = {}, onMark = {}, onClose = {},
                )
            }
        }
        // Off by default: the overlay is opened for the traffic far more often than for app state.
        // The toggle still says how many there are, so hidden is never mistaken for none.
        assertEquals(0, onAllNodesWithText("dashboard").fetchSemanticsNodes().size)
        onNodeWithText("/v1/alpha").assertExists()

        openFilters()
        sheetChip("signals").performClick()
        waitForIdle()
        onNodeWithText("dashboard").assertExists()
        // The traffic is untouched — showing signals is not a filter on the calls.
        onNodeWithText("/v1/alpha").assertExists()

        sheetChip("signals").performClick()
        waitForIdle()
        assertEquals(0, onAllNodesWithText("dashboard").fetchSemanticsNodes().size)
    }

    /**
     * The arrangement belongs to whoever owns the state, not to the list composable.
     *
     * The list leaves the composition on every close and every tap into a detail screen. While the
     * arrangement lived inside it, each of those reset the filter, the order and the signals
     * toggle — so the reader set the same filter again on every visit.
     */
    @Test
    fun `the arrangement survives the list leaving the screen`() = runComposeUiTest {
        val failing = txn("c", "/v1/broken", 300).copy(status = 500)
        val state = ListViewState()
        val shown = mutableStateOf(true)
        setContent {
            InspectorTheme(dark = true) {
                if (shown.value) {
                    InspectorList(
                        transactions = listOf(first, failing),
                        markers = emptyList(),
                        signals = listOf(signal("screen", "dashboard", 150)),
                        onSelect = {}, onSelectSignal = {}, onClear = {}, onMark = {}, onClose = {},
                        state = state,
                    )
                }
            }
        }
        orderControl(newestFirst = true).performClick()
        openFilters()
        sheetChip("5xx").performClick()
        sheetChip("signals").performClick()
        waitForIdle()

        shown.value = false
        waitForIdle()
        shown.value = true
        waitForIdle()

        // Asserted by what is on screen, not by reading `state` back: a list that ignored the
        // state it was handed would pass an assertion about the holder.
        assertEquals(0, onAllNodesWithText("/v1/alpha").fetchSemanticsNodes().size)
        onNodeWithText("/v1/broken").assertExists()
        orderControl(newestFirst = false).assertExists()
        // The toggle's label rather than the signal row: `5xx` is a traffic term, and traffic terms
        // exclude every signal by design, so the row is hidden by the filter either way.
        openFilters()
        onNode(hasText("signals") and isToggleable() and hasText("1")).assertExists()
    }

    /**
     * Closing keeps the arrangement and drops the freeze.
     *
     * A snapshot still held when the reader comes back later would present stale rows as what the
     * app is doing now, and nothing on reopening would say the list had been frozen before.
     */
    @Test
    fun `closing thaws a frozen list but keeps its filter`() = runComposeUiTest {
        val live = mutableStateOf(listOf(first))
        val state = ListViewState()
        val shown = mutableStateOf(true)
        setContent {
            InspectorTheme(dark = true) {
                if (shown.value) {
                    InspectorList(
                        transactions = live.value,
                        markers = emptyList(),
                        onSelect = {}, onSelectSignal = {}, onClear = {}, onMark = {}, onClose = {},
                        state = state,
                    )
                }
            }
        }
        chip("live").performClick()
        waitForIdle()
        live.value = listOf(first, second)

        shown.value = false
        state.onClosed()
        waitForIdle()
        shown.value = true
        waitForIdle()

        chip("live").assertExists()
        onNodeWithText("/v1/beta").assertExists()
    }

    @Test
    fun `a session with no signals offers no toggle`() = runComposeUiTest {
        setContent {
            InspectorTheme(dark = true) {
                InspectorList(
                    transactions = listOf(first),
                    markers = emptyList(),
                    onSelect = {}, onSelectSignal = {}, onClear = {}, onMark = {}, onClose = {},
                )
            }
        }
        // A control for something the session does not contain can only disappoint.
        openFilters()
        assertEquals(0, onAllNodes(hasText("signals") and isToggleable()).fetchSemanticsNodes().size)
        assertEquals(0, onAllNodesWithText("SHOW").fetchSemanticsNodes().size)
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
                    state = remember { mergedOldestFirst() },
                    transactions = listOf(first),
                    markers = emptyList(),
                    signals = listOf(
                        signal("state", "form", 200),
                        signal("state", "form", 210),
                        signal("state", "form", 240),
                        signal("state", "form", 290),
                    ),
                    onSelect = {}, onSelectSignal = {}, onClear = {}, onMark = {}, onClose = {},
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
                    state = remember { mergedOldestFirst() },
                    transactions = listOf(first),
                    markers = emptyList(),
                    signals = listOf(signal("state", "form", 200), signal("state", "form", 210)),
                    onSelect = {}, onSelectSignal = {}, onClear = {}, onMark = {}, onClose = {},
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
                    state = remember { mergedOldestFirst() },
                    transactions = listOf(first),
                    markers = emptyList(),
                    signals = live.value,
                    onSelect = {}, onSelectSignal = {}, onClear = {}, onMark = {}, onClose = {},
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


    // --- the filters sheet and the overflow menu -----------------------------------------------

    /**
     * One choice per section, typed into the same field, and the field's own text left alone.
     *
     * Asserted on the field's text and on the rows, not on which chip looks lit: the sheet's whole
     * claim is that it is a way of typing into the filter, so what it typed is the thing to check.
     */
    @Test
    fun `the sheet types into the field and keeps one choice per section`() = runComposeUiTest {
        val failing = txn("c", "/v1/broken", 300).copy(status = 500)
        val missing = txn("d", "/v1/missing", 400).copy(status = 404)
        val state = mergedOldestFirst().also { it.filterText = "path:/v1" }
        setContent {
            InspectorTheme(dark = true) {
                InspectorList(
                    state = state,
                    transactions = listOf(first, failing, missing),
                    markers = emptyList(),
                    onSelect = {}, onSelectSignal = {}, onClear = {}, onMark = {}, onClose = {},
                )
            }
        }
        openFilters()
        sheetChip("errors").performClick()
        waitForIdle()
        assertEquals("path:/v1 has:error", state.filterText)
        onNode(hasText("Show 2 calls") and hasClickAction()).assertExists()

        // Another status replaces the first rather than ANDing with it.
        sheetChip("5xx").performClick()
        waitForIdle()
        assertEquals("path:/v1 status>=500", state.filterText)
        onNode(hasText("Show 1 call") and hasClickAction()).assertExists()
        // The button outside says how many sheet terms are on, so a closed sheet is not a mystery.
        onNode(hasText("filters") and hasText("1") and hasClickAction()).assertExists()

        // Reset clears what the sheet set and only that.
        onNode(hasText("Reset") and hasClickAction()).performClick()
        waitForIdle()
        assertEquals("path:/v1", state.filterText)

        onNode(hasText("Show 3 calls") and hasClickAction()).performClick()
        waitForIdle()
        assertEquals(0, onAllNodesWithText("Filters").fetchSemanticsNodes().size)
        onNodeWithText("/v1/missing").assertExists()
    }

    /**
     * Clear empties the only copy of anything the daemon never received, so it takes two taps and
     * says what it will do on the first. Watched through the callback, not the label: a menu that
     * relabelled itself and cleared anyway would pass an assertion about its text.
     */
    @Test
    fun `clear asks once and only clears on the second tap`() = runComposeUiTest {
        var cleared = 0
        setContent {
            InspectorTheme(dark = true) {
                InspectorList(
                    transactions = listOf(first, second),
                    markers = emptyList(),
                    onSelect = {}, onSelectSignal = {}, onClear = { cleared++ }, onMark = {}, onClose = {},
                )
            }
        }
        onNode(hasContentDescription("more actions")).performClick()
        waitForIdle()
        onNode(hasText("Clear…") and hasClickAction()).performClick()
        waitForIdle()
        assertEquals(0, cleared)
        onNode(hasText("Clear 2 calls?") and hasClickAction()).performClick()
        waitForIdle()
        assertEquals(1, cleared)
        // And the menu is gone, so the list it cleared is what is on screen.
        assertEquals(0, onAllNodesWithText("Clear…").fetchSemanticsNodes().size)
    }

    @Test
    fun `an armed clear disarms on its own`() = runComposeUiTest {
        var cleared = 0
        setContent {
            InspectorTheme(dark = true) {
                InspectorList(
                    transactions = listOf(first),
                    markers = emptyList(),
                    onSelect = {}, onSelectSignal = {}, onClear = { cleared++ }, onMark = {}, onClose = {},
                )
            }
        }
        onNode(hasContentDescription("more actions")).performClick()
        onNode(hasText("Clear…") and hasClickAction()).performClick()
        waitForIdle()
        onNodeWithText("Clear 1 call?").assertExists()
        mainClock.advanceTimeBy(3_500)
        waitForIdle()
        // A tap an hour later must not be the second half of this one.
        onNode(hasText("Clear…") and hasClickAction()).assertExists()
        assertEquals(0, cleared)
    }

    /**
     * A long path at phone width shows its end.
     *
     * The renderer's own `StartEllipsis` clips the *end* on desktop and iOS, which was measured
     * before this was written — so this runs on exactly the renderer that got it wrong, and reads
     * the string actually drawn.
     */
    @Test
    fun `a long path keeps the endpoint and loses the front`() = runComposeUiTest {
        val long = txn("z", "/v3/some-service/customers/profile/accounts/balance", 100)
        setContent {
            InspectorTheme(dark = true) {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.width(360.dp)) {
                    InspectorList(
                        transactions = listOf(long),
                        markers = emptyList(),
                        onSelect = {}, onSelectSignal = {}, onClear = {}, onMark = {}, onClose = {},
                    )
                }
            }
        }
        val drawn = onAllNodesWithText("/balance", substring = true).fetchSemanticsNodes()
            .flatMap { it.config[androidx.compose.ui.semantics.SemanticsProperties.Text] }
            .map { it.text }
            .single { it.endsWith("/accounts/balance") }
        assertTrue(drawn.startsWith("…"), "the path was not clipped at the front: $drawn")
    }

    // --- the empty state ---------------------------------------------------------------------

    @Test
    fun `an empty inspector says nothing has happened and what would make it`() = runComposeUiTest {
        var pulled = 0
        setContent {
            InspectorTheme(dark = true) {
                InspectorList(
                    transactions = emptyList(), markers = emptyList(),
                    onSelect = {}, onSelectSignal = {}, onClear = {}, onMark = {}, onClose = {},
                    providers = listOf(dev.inspector.model.SignalKey("cache", "a"), dev.inspector.model.SignalKey("state", "b")),
                    onPull = { pulled++; null },
                )
            }
        }
        onNodeWithText("No calls yet").assertExists()
        onNodeWithText("OkHttpClient", substring = true).assertExists()
        onNode(hasText("Pull signals now") and hasClickAction()).performClick()
        waitForIdle()
        // Every registered provider, not the first one.
        assertEquals(2, pulled)
    }

    @Test
    fun `no providers means no pull button`() = runComposeUiTest {
        setContent {
            InspectorTheme(dark = true) {
                InspectorList(
                    transactions = emptyList(), markers = emptyList(),
                    onSelect = {}, onSelectSignal = {}, onClear = {}, onMark = {}, onClose = {},
                )
            }
        }
        // A button that can only fail is worse than none.
        assertEquals(0, onAllNodesWithText("Pull signals now").fetchSemanticsNodes().size)
    }

    @Test
    fun `a filter that hides every call offers to clear itself`() = runComposeUiTest {
        val state = ListViewState(filterText = "status>=500")
        setContent {
            InspectorTheme(dark = true) {
                InspectorList(
                    state = state, transactions = listOf(first, second), markers = emptyList(),
                    onSelect = {}, onSelectSignal = {}, onClear = {}, onMark = {}, onClose = {},
                )
            }
        }
        onNodeWithText("No calls match this filter").assertExists()
        onNode(hasText("Clear filter") and hasClickAction()).performClick()
        waitForIdle()
        // The rows coming back is the claim, not the field emptying.
        onNodeWithText("/v1/alpha").assertExists()
        assertEquals("", state.filterText)
    }

    @Test
    fun `signals hidden with no calls are not reported as nothing`() = runComposeUiTest {
        setContent {
            InspectorTheme(dark = true) {
                InspectorList(
                    transactions = emptyList(), markers = emptyList(),
                    signals = listOf(signal("screen", "dashboard", 150)),
                    onSelect = {}, onSelectSignal = {}, onClear = {}, onMark = {}, onClose = {},
                )
            }
        }
        onNode(hasText("Show 1 hidden signal") and hasClickAction()).performClick()
        waitForIdle()
        onNodeWithText("dashboard").assertExists()
    }
}

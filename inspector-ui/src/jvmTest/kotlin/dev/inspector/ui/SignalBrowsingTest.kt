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
import dev.inspector.model.NetworkTransaction
import dev.inspector.model.Signal
import dev.inspector.model.SignalTrigger
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Browsing signals: narrowing to one tag, opening one observation, and walking its history.
 *
 * Stage 1 put signals in the list and drew no payloads. Everything here is about the step from
 * "something changed" to "this is what it changed to", which is the whole of stage 2 — and every
 * one of those steps is a transition, so a render test cannot show any of them.
 */
@OptIn(ExperimentalTestApi::class)
class SignalBrowsingTest {

    private fun txn(id: String, path: String, mono: Long) = NetworkTransaction(
        id = id, ts = "2026-09-18T09:00:00Z", mono = mono, method = "GET",
        scheme = "https", host = "api.example.com", path = path, status = 200, callId = id,
    )

    private var seq = 0
    private fun signal(
        tag: String,
        name: String,
        mono: Long,
        data: kotlinx.serialization.json.JsonElement? = null,
        trigger: SignalTrigger = SignalTrigger.App,
        bytes: Long = 0,
    ) = Signal(
        id = "s${++seq}", ts = "2026-09-18T09:00:0${mono / 100 % 10}Z", mono = mono,
        tag = tag, name = name, data = data, trigger = trigger, bytes = bytes,
    )

    private fun ComposeUiTest.chip(label: String) = onNode(hasText(label) and hasClickAction())

    private val call = txn("a", "/v1/alpha", 100)

    @Test
    fun `a tag chip narrows the list to that tag, and hides the traffic`() = runComposeUiTest {
        setContent {
            InspectorTheme(dark = true) {
                InspectorList(
                    transactions = listOf(call),
                    markers = emptyList(),
                    signals = listOf(
                        signal("cache", "profile", 150),
                        signal("screen", "Dashboard", 200),
                    ),
                    onSelect = {}, onSelectSignal = {}, onClear = {}, onMark = {}, onClose = {},
                )
            }
        }
        onNodeWithText("/v1/alpha").assertExists()
        onNodeWithText("profile").assertExists()
        onNodeWithText("Dashboard").assertExists()

        chip("cache 1").performClick()
        waitForIdle()

        onNodeWithText("profile").assertExists()
        assertEquals(0, onAllNodesWithText("Dashboard").fetchSemanticsNodes().size)
        // `tag:` is a SignalTerm, so it drops every transaction. That is the exclusion rule
        // working, and it is what turns a chip into a browser for one tag rather than a highlight
        // over an unchanged list.
        assertEquals(0, onAllNodesWithText("/v1/alpha").fetchSemanticsNodes().size)

        chip("cache 1").performClick()
        waitForIdle()
        onNodeWithText("/v1/alpha").assertExists()
        onNodeWithText("Dashboard").assertExists()
    }

    @Test
    fun `a transaction filter now hides the signals it excludes`() = runComposeUiTest {
        setContent {
            InspectorTheme(dark = true) {
                InspectorList(
                    transactions = listOf(call, txn("b", "/v1/broken", 300).copy(status = 500)),
                    markers = emptyList(),
                    signals = listOf(signal("cache", "profile", 150)),
                    onSelect = {}, onSelectSignal = {}, onClear = {}, onMark = {}, onClose = {},
                )
            }
        }
        onNodeWithText("profile").assertExists()

        chip("5xx").performClick()
        waitForIdle()

        // The overlay used to filter the traffic and let every observation through, so a filter
        // thinned the calls and left the signals sitting between them. The web UI has always
        // passed its filter to the signal route; this is the surface that disagreed.
        assertEquals(0, onAllNodesWithText("profile").fetchSemanticsNodes().size)
        onNodeWithText("/v1/broken").assertExists()
    }

    @Test
    fun `tapping an observation hands back the one that was tapped`() = runComposeUiTest {
        val opened = mutableStateOf<Signal?>(null)
        val target = signal("cache", "profile", 150)
        setContent {
            InspectorTheme(dark = true) {
                InspectorList(
                    transactions = listOf(call),
                    markers = emptyList(),
                    signals = listOf(target, signal("screen", "Dashboard", 200)),
                    onSelect = {}, onSelectSignal = { opened.value = it },
                    onClear = {}, onMark = {}, onClose = {},
                )
            }
        }
        onNode(hasText("profile") and hasClickAction()).performClick()
        waitForIdle()
        // The row that was tapped, not merely "a signal" — two observations a row apart is how a
        // detail screen ends up confidently showing the wrong one.
        assertEquals(target.id, opened.value?.id)
    }

    @Test
    fun `the detail screen shows the payload and says where it came from`() = runComposeUiTest {
        val pulled = signal(
            "cache", "profile", 150,
            data = buildJsonObject { put("items", 7) },
            trigger = SignalTrigger.Request,
            bytes = 42,
        )
        setContent {
            InspectorTheme(dark = true) {
                InspectorSignalDetail(
                    signal = pulled,
                    signals = listOf(pulled),
                    onSelectSignal = {}, onCopy = {}, onBack = {}, onClose = {},
                )
            }
        }
        onNodeWithText("\"items\"", substring = true).assertExists()
        // Provenance is drawn for every row, not only the interesting ones: a snapshot with none
        // reads as the current state of the app, and the common case is the pushed one.
        onNodeWithText("pulled", substring = true).assertExists()
    }

    @Test
    fun `a pushed observation says so rather than saying nothing`() = runComposeUiTest {
        val pushed = signal("state", "CartViewModel", 150, data = JsonPrimitive("Cart(items=3)"))
        setContent {
            InspectorTheme(dark = true) {
                InspectorSignalDetail(
                    signal = pushed,
                    signals = listOf(pushed),
                    onSelectSignal = {}, onCopy = {}, onBack = {}, onClose = {},
                )
            }
        }
        onNodeWithText("pushed by the app").assertExists()
        // A toString() dump is not JSON and must not arrive re-quoted.
        onNodeWithText("Cart(items=3)").assertExists()
    }

    @Test
    fun `the history lists every observation of this key and switching moves to it`() = runComposeUiTest {
        val older = signal("state", "Cart", 100, data = JsonPrimitive("empty"))
        val newer = signal("state", "Cart", 900, data = JsonPrimitive("three items"))
        // Same *name*, different tag. Deliberately not a different name: a decoy that differs in
        // both fields is excluded by either rule, so it cannot tell grouping on `(tag, name)` from
        // grouping on `name` alone — which is exactly what it failed to catch the first time.
        val sameNameOtherTag = signal("cache", "Cart", 500, data = JsonPrimitive("nothing to do with it"))
        val showing = mutableStateOf(newer)

        setContent {
            InspectorTheme(dark = true) {
                InspectorSignalDetail(
                    signal = showing.value,
                    signals = listOf(older, sameNameOtherTag, newer),
                    onSelectSignal = { showing.value = it },
                    onCopy = {}, onBack = {}, onClose = {},
                )
            }
        }
        onNodeWithText("three items").assertExists()
        onNodeWithText("History — 2 observations").assertExists()
        // `cache`/`Cart` must not be in `state`/`Cart`'s history. `name` is documented as identity
        // *within* `tag`, so grouping on the name alone would splice a cache entry into a
        // view-model's history and show a value changing into something it never was.
        assertEquals(0, onAllNodesWithText("nothing to do with it").fetchSemanticsNodes().size)

        // The gap from the previous observation, which is what separates a state holder firing on
        // every keystroke from the app actually changing.
        onNodeWithText("+800ms", substring = true).assertExists()

        onNode(hasText("first") and hasClickAction()).performClick()
        waitForIdle()
        assertEquals(older.id, showing.value.id)
        onNodeWithText("empty").assertExists()
        assertEquals(0, onAllNodesWithText("three items").fetchSemanticsNodes().size)
    }

    @Test
    fun `a key observed once has no history section`() = runComposeUiTest {
        val only = signal("screen", "Home", 150)
        setContent {
            InspectorTheme(dark = true) {
                InspectorSignalDetail(
                    signal = only,
                    signals = listOf(only),
                    onSelectSignal = {}, onCopy = {}, onBack = {}, onClose = {},
                )
            }
        }
        // A history of one is the row you are already looking at.
        assertEquals(0, onAllNodesWithText("History", substring = true).fetchSemanticsNodes().size)
        assertTrue(onAllNodesWithText("none", substring = true).fetchSemanticsNodes().isNotEmpty())
    }
}

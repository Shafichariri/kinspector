package dev.inspector.ui

import androidx.compose.runtime.remember
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
import androidx.compose.foundation.layout.Column
import dev.inspector.model.Signal
import dev.inspector.model.SignalKey
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
                    state = remember { mergedOldestFirst() },
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
                    state = remember { mergedOldestFirst() },
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
                    state = remember { mergedOldestFirst() },
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

    // --- the now strip ------------------------------------------------------

    @Test
    fun `the now strip is collapsed, summarises, and opens`() = runComposeUiTest {
        setContent {
            InspectorTheme(dark = true) {
                InspectorList(
                    state = remember { mergedOldestFirst() },
                    transactions = listOf(call),
                    markers = emptyList(),
                    signals = listOf(
                        signal("cache", "profile", 100),
                        signal("cache", "holdings", 150),
                        signal("state", "CartViewModel", 200),
                    ),
                    onSelect = {}, onSelectSignal = {}, onClear = {}, onMark = {}, onClose = {},
                )
            }
        }
        // Collapsed it is one line, and that line says how much and how stale — a count alone
        // dodges the second half, which is the reason to open it.
        onNodeWithText("now").assertExists()
        onNodeWithText("2 cache · 1 state", substring = true).assertExists()

        // Counted by the provenance word, which only a strip row draws. Counting the *names*
        // was the first attempt and asserted nothing: the list underneath shows every
        // observation, so "holdings" is on screen whether the strip is open or shut.
        assertEquals(0, onAllNodesWithText("pushed").fetchSemanticsNodes().size)

        onNode(hasText("now") and hasClickAction()).performClick()
        waitForIdle()
        // One row per key, and not one per observation.
        assertEquals(3, onAllNodesWithText("pushed").fetchSemanticsNodes().size)
        // Not `onNodeWithText`: the name is now on screen twice, once in the strip and once in
        // the list, and "exactly one node" would fail for the very reason the strip exists.
        assertTrue(onAllNodesWithText("CartViewModel").fetchSemanticsNodes().size >= 2)
    }

    @Test
    fun `the strip shows the latest of each key, not every observation`() = runComposeUiTest {
        setContent {
            InspectorTheme(dark = true) {
                InspectorList(
                    state = remember { mergedOldestFirst() },
                    transactions = listOf(call),
                    markers = emptyList(),
                    signals = listOf(
                        signal("state", "Cart", 100),
                        signal("state", "Cart", 500),
                        signal("state", "Cart", 900),
                    ),
                    onSelect = {}, onSelectSignal = {}, onClear = {}, onMark = {}, onClose = {},
                )
            }
        }
        // Three observations of one key, and the summary counts the key once. A panel headed
        // "now" listing the same name three times is a feed, which is what the list below is.
        onNodeWithText("1 state", substring = true).assertExists()
        onNode(hasText("now") and hasClickAction()).performClick()
        waitForIdle()
        // Exactly one strip row. Counting the name across the whole screen was the first attempt
        // and it coupled this test to run collapsing — three adjacent identical observations
        // collapse to one header, so the "obvious" total was wrong for a reason that has nothing
        // to do with what is being asserted here.
        assertEquals(1, onAllNodesWithText("pushed").fetchSemanticsNodes().size)
    }

    @Test
    fun `tapping a now row opens that observation`() = runComposeUiTest {
        val opened = mutableStateOf<Signal?>(null)
        val latest = signal("cache", "profile", 900)
        setContent {
            InspectorTheme(dark = true) {
                InspectorList(
                    state = remember { mergedOldestFirst() },
                    transactions = listOf(call),
                    markers = emptyList(),
                    signals = listOf(signal("cache", "profile", 100), latest),
                    onSelect = {}, onSelectSignal = { opened.value = it },
                    onClear = {}, onMark = {}, onClose = {},
                )
            }
        }
        onNode(hasText("now") and hasClickAction()).performClick()
        waitForIdle()
        onNode(hasText("pushed") and hasClickAction()).performClick()
        waitForIdle()
        // The *latest* observation of that key, which is the one the panel is showing — opening
        // the first one would be showing a row and then explaining a different one.
        assertEquals(latest.id, opened.value?.id)
    }

    @Test
    fun `the strip disappears with the signals toggle`() = runComposeUiTest {
        setContent {
            InspectorTheme(dark = true) {
                InspectorList(
                    state = remember { mergedOldestFirst() },
                    transactions = listOf(call),
                    markers = emptyList(),
                    signals = listOf(signal("cache", "profile", 100)),
                    onSelect = {}, onSelectSignal = {}, onClear = {}, onMark = {}, onClose = {},
                )
            }
        }
        onNodeWithText("now").assertExists()
        chip("signals 1").performClick()
        waitForIdle()
        // "Signals off" that left a signal panel on screen would be the toggle not meaning what
        // it says.
        assertEquals(0, onAllNodesWithText("now").fetchSemanticsNodes().size)
    }

    @Test
    fun `a session with no signals gets no strip at all`() = runComposeUiTest {
        setContent {
            InspectorTheme(dark = true) {
                InspectorList(
                    state = remember { mergedOldestFirst() },
                    transactions = listOf(call),
                    markers = emptyList(),
                    onSelect = {}, onSelectSignal = {}, onClear = {}, onMark = {}, onClose = {},
                )
            }
        }
        // A panel for something a session does not contain is a line of chrome that can only
        // disappoint, and this screen has no room for one.
        assertEquals(0, onAllNodesWithText("now").fetchSemanticsNodes().size)
    }

    @Test
    fun `freezing holds the ages still, not only the rows`() = runComposeUiTest {
        // 2026-09-19T10:00:00Z, and a clock this test owns.
        val clock = mutableStateOf(1_789_812_000_000L)
        val observed = signal("cache", "profile", 100).copy(ts = "2026-09-19T09:59:00.000Z")
        setContent {
            InspectorTheme(dark = true) {
                InspectorList(
                    state = remember { mergedOldestFirst() },
                    transactions = listOf(call),
                    markers = emptyList(),
                    signals = listOf(observed),
                    onSelect = {}, onSelectSignal = {}, onClear = {}, onMark = {}, onClose = {},
                    nowMsProvider = { clock.value },
                )
            }
        }
        onNodeWithText("oldest 1m ago", substring = true).assertExists()

        chip("live").performClick()
        waitForIdle()

        // Five minutes pass with the list held.
        clock.value += 5 * 60_000
        mainClock.advanceTimeBy(5_000)
        waitForIdle()

        // Still 1m. A held list whose ages kept climbing would be describing a snapshot with a
        // clock that had moved on from it — "oldest 6m ago" over rows that stopped updating five
        // minutes ago is a sentence about two different moments.
        onNodeWithText("oldest 1m ago", substring = true).assertExists()

        chip("frozen").performClick()
        waitForIdle()
        mainClock.advanceTimeBy(2_000)
        waitForIdle()
        // Thawing catches the clock up along with the rows.
        onNodeWithText("oldest 6m ago", substring = true).assertExists()
    }

    // --- pulling on demand ----------------------------------------------------

    @Test
    fun `the pull button appears only where a provider exists`() = runComposeUiTest {
        val observed = signal("cache", "profile", 100)
        setContent {
            InspectorTheme(dark = true) {
                Column {
                    InspectorSignalDetail(
                        signal = observed,
                        signals = listOf(observed),
                        onSelectSignal = {}, onCopy = {}, onBack = {}, onClose = {},
                        onPull = null,
                    )
                }
            }
        }
        // The overlay knows the registry rather than guessing at it, so a button that is not
        // drawn is a provider that does not exist — not one it was unsure about.
        assertEquals(0, onAllNodesWithText("pull").fetchSemanticsNodes().size)
    }

    @Test
    fun `pulling reports the provider's own failure verbatim`() = runComposeUiTest {
        val observed = signal("cache", "profile", 100)
        setContent {
            InspectorTheme(dark = true) {
                InspectorSignalDetail(
                    signal = observed,
                    signals = listOf(observed),
                    onSelectSignal = {}, onCopy = {}, onBack = {}, onClose = {},
                    onPull = { "IllegalStateException: cache closed" },
                )
            }
        }
        onNode(hasText("pull") and hasClickAction()).performClick()
        waitForIdle()
        // A pull can fail because the provider threw, or because it was unregistered between the
        // screen being drawn and the button being pressed. Either way the app said what went
        // wrong, and paraphrasing it here would lose the only diagnosis there is.
        onNodeWithText("IllegalStateException: cache closed").assertExists()
    }

    @Test
    fun `a successful pull leaves no error behind`() = runComposeUiTest {
        val observed = signal("cache", "profile", 100)
        setContent {
            InspectorTheme(dark = true) {
                InspectorSignalDetail(
                    signal = observed,
                    signals = listOf(observed),
                    onSelectSignal = {}, onCopy = {}, onBack = {}, onClose = {},
                    onPull = { null },
                )
            }
        }
        onNode(hasText("pull") and hasClickAction()).performClick()
        waitForIdle()
        onNode(hasText("pull") and hasClickAction()).assertExists()
        assertEquals(0, onAllNodesWithText("Exception", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun `a provider that has never answered is reachable in the now strip`() = runComposeUiTest {
        val pulled = mutableStateOf<SignalKey?>(null)
        setContent {
            InspectorTheme(dark = true) {
                InspectorList(
                    state = remember { mergedOldestFirst() },
                    transactions = listOf(call),
                    markers = emptyList(),
                    signals = listOf(signal("cache", "profile", 100)),
                    onSelect = {}, onSelectSignal = {}, onClear = {}, onMark = {}, onClose = {},
                    providers = listOf(SignalKey("cache", "profile"), SignalKey("state", "Checkout")),
                    onPull = { key -> pulled.value = key; null },
                )
            }
        }
        onNode(hasText("now") and hasClickAction()).performClick()
        waitForIdle()

        // `cache/profile` has an observation, so it appears among the current values with an age.
        // `state/Checkout` has none: with no row to open, the detail screen's pull button is
        // unreachable for it, and without this it would exist nowhere in the UI at all.
        onNodeWithText("never read").assertExists()
        onNodeWithText("Checkout").assertExists()

        onNode(hasText("pull") and hasClickAction()).performClick()
        waitForIdle()
        assertEquals(SignalKey("state", "Checkout"), pulled.value)
    }

    @Test
    fun `a provider that has answered is not listed as never read`() = runComposeUiTest {
        setContent {
            InspectorTheme(dark = true) {
                InspectorList(
                    state = remember { mergedOldestFirst() },
                    transactions = listOf(call),
                    markers = emptyList(),
                    signals = listOf(signal("state", "Checkout", 100)),
                    onSelect = {}, onSelectSignal = {}, onClear = {}, onMark = {}, onClose = {},
                    providers = listOf(SignalKey("state", "Checkout")),
                    onPull = { null },
                )
            }
        }
        onNode(hasText("now") and hasClickAction()).performClick()
        waitForIdle()
        // Once it has been observed it belongs among the current values, where it has an age —
        // which is the more useful thing to know about it than that it could be re-read.
        assertEquals(0, onAllNodesWithText("never read").fetchSemanticsNodes().size)
        onNodeWithText("pushed").assertExists()
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

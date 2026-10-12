package dev.inspector.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A session split into screen visits.
 *
 * Assertions are written as a shape — `Home[a b] Cart[]` — because what matters is which visit
 * each entry landed on, and comparing whole objects would bury it.
 */
class VisitsTest {

    private var seq = 0

    private fun screen(name: String, mono: Long) =
        signal(id = "s${++seq}", tag = SignalTags.SCREEN, name = name, mono = mono)

    private fun call(id: String, mono: Long, ms: Long = 10) =
        txn(id = id, mono = mono, ms = ms, callId = id, path = "/v1/$id")

    private fun shape(visits: List<Visit>) = visits.joinToString(" ") { visit ->
        val name = visit.screen?.name ?: "-"
        val inside = visit.entries.joinToString(" ") {
            when (it) {
                is TimelineEntry.Call -> it.txn.id
                is TimelineEntry.Mark -> "M:${it.marker.label}"
                is TimelineEntry.Observation -> "S:${it.signal.tag}/${it.signal.name}"
            }
        }
        "$name[$inside]"
    }

    @Test
    fun `entries land on the screen open when they started`() {
        val screens = listOf(screen("Home", 100), screen("Cart", 300))
        val result = visits(screens, listOf(call("a", 150), call("b", 350)), emptyList())
        assertEquals("Home[a] Cart[b]", shape(result))
    }

    /**
     * The tie that decides `[from, to)`. A call at the exact millisecond a screen arrived belongs
     * to that screen; on a millisecond clock a view-model firing in `init` lands here often.
     */
    @Test
    fun `a call at the same millisecond as a screen belongs to that screen`() {
        val screens = listOf(screen("Home", 100), screen("Cart", 300))
        val result = visits(screens, listOf(call("a", 300)), emptyList())
        assertEquals("Home[] Cart[a]", shape(result))
    }

    /**
     * No grace window. A call one millisecond before its screen's signal stays on the previous
     * screen — the honest fix is the app emitting `screen` earlier, and a window here would also
     * move the call that *caused* the navigation.
     */
    @Test
    fun `a call just before a late screen signal stays on the previous screen`() {
        val screens = listOf(screen("Home", 100), screen("Cart", 300))
        val result = visits(screens, listOf(call("pay", 299)), emptyList())
        assertEquals("Home[pay] Cart[]", shape(result))
    }

    /**
     * `onClick { tap; navigate() }`: the tap and the screen share a millisecond. Capture order
     * decides, so the tap stays on the screen it was made on.
     */
    @Test
    fun `a tap captured before its navigation in the same millisecond stays behind`() {
        val home = signal(id = "h", tag = SignalTags.SCREEN, name = "Home", mono = 100, seq = 1)
        val cart = signal(id = "c", tag = SignalTags.SCREEN, name = "Cart", mono = 300, seq = 3)
        val tap = signal(id = "t", tag = "tap", name = "add_to_cart", mono = 300, seq = 2)
        val fired = txn(id = "init", mono = 300, callId = "init").copy(seq = 4)
        val result = visits(listOf(home, cart), listOf(fired), emptyList(), signals = listOf(tap))
        assertEquals("Home[S:tap/add_to_cart] Cart[init]", shape(result))
    }

    /** Without `seq` on either side — an archive from before it — a tie still goes to the arrival. */
    @Test
    fun `a tie with no seq still goes to the arriving screen`() {
        val home = signal(id = "h", tag = SignalTags.SCREEN, name = "Home", mono = 100, seq = 1)
        val cart = signal(id = "c", tag = SignalTags.SCREEN, name = "Cart", mono = 300, seq = 3)
        val old = signal(id = "t", tag = "tap", name = "add_to_cart", mono = 300)
        val result = visits(listOf(home, cart), emptyList(), emptyList(), signals = listOf(old))
        assertEquals("Home[] Cart[S:tap/add_to_cart]", shape(result))
    }

    @Test
    fun `a revisit is a new visit`() {
        val screens = listOf(screen("A", 100), screen("B", 200), screen("A", 300))
        assertEquals("A[] B[] A[]", shape(visits(screens, emptyList(), emptyList())))
    }

    @Test
    fun `consecutive same-name signals are one visit with repeats in place`() {
        val screens = listOf(screen("Checkout", 100), screen("Checkout", 200), screen("Done", 400))
        val result = visits(screens, listOf(call("a", 150), call("b", 250)), emptyList())
        assertEquals("Checkout[a S:screen/Checkout b] Done[]", shape(result))
        assertEquals(listOf(200L), result[0].repeats.map { it.mono })
        assertEquals(400L, result[0].toMono)
    }

    @Test
    fun `launch traffic gets a leading visit with no screen`() {
        val screens = listOf(screen("Home", 100))
        val result = visits(screens, listOf(call("config", 20), call("a", 120)), emptyList())
        assertEquals("-[config] Home[a]", shape(result))
        assertNull(result[0].screen)
        assertEquals(20L, result[0].fromMono)
        assertEquals(100L, result[0].toMono)
    }

    @Test
    fun `a session with no screens is one unnamed visit`() {
        val result = visits(emptyList(), listOf(call("a", 10), call("b", 20)), emptyList())
        assertEquals("-[a b]", shape(result))
        assertNull(result[0].toMono)
    }

    @Test
    fun `an empty session has no visits`() {
        assertEquals(emptyList(), visits(emptyList(), emptyList(), emptyList()))
    }

    /**
     * The filter rule. A transaction term drops every signal, so the skeleton comes from its own
     * parameter — and a visit nothing matched is returned empty, never dropped, or the route would
     * skip a screen the user was on.
     */
    @Test
    fun `a visit with no matching entries is kept`() {
        val screens = listOf(screen("Product", 100), screen("Cart", 200), screen("Checkout", 300))
        val result = visits(screens, listOf(call("a", 120), call("b", 320)), emptyList())
        assertEquals("Product[a] Cart[] Checkout[b]", shape(result))
    }

    /**
     * The launch column is skeleton too. Under a filter that matched nothing before the first
     * screen, it must survive — empty — when the caller says the session started earlier.
     */
    @Test
    fun `the leading visit survives a filter when the session start is given`() {
        val screens = listOf(screen("Home", 100))
        val result = visits(screens, emptyList(), emptyList(), sessionStart = 20)
        assertEquals("-[] Home[]", shape(result))
        assertEquals(20L, result[0].fromMono)
        assertEquals("Home[]", shape(visits(screens, emptyList(), emptyList(), sessionStart = 100)))
    }

    @Test
    fun `screen signals in the contents are ignored in favour of the skeleton`() {
        val skeleton = listOf(screen("Home", 100))
        val stray = screen("Ghost", 200)
        val result = visits(skeleton, emptyList(), emptyList(), signals = listOf(stray))
        assertEquals("Home[]", shape(result))
    }

    @Test
    fun `markers and other signals sit in place inside the visit`() {
        val screens = listOf(screen("Cart", 100))
        val result = visits(
            screens,
            transactions = listOf(call("pay", 200)),
            markers = listOf(marker("about to pay", 200)),
            signals = listOf(signal(id = "c1", tag = SignalTags.CACHE, name = "orders", mono = 250)),
        )
        // Marker before the call at a tie: timeline()'s rule, unchanged.
        assertEquals("Cart[M:about to pay pay S:cache/orders]", shape(result))
    }

    @Test
    fun `the last visit is open`() {
        val result = visits(listOf(screen("A", 100), screen("B", 200)), emptyList(), emptyList())
        assertEquals(200L, result[0].toMono)
        assertNull(result[1].toMono)
    }

    @Test
    fun `a call that outlives its visit finishes on a later one`() {
        val screens = listOf(screen("Cart", 100), screen("Confirm", 300))
        val slow = call("orders", 250, ms = 120)
        val result = visits(screens, listOf(slow), emptyList())
        assertEquals("Cart[orders] Confirm[]", shape(result))
        assertEquals(0, visitIndexAt(result, slow.mono))
        assertEquals(1, visitIndexAt(result, slow.mono + slow.ms!!))
        assertEquals(-1, visitIndexAt(result, 50))
    }

    @Test
    fun `screen tags match case-insensitively like the web UI`() {
        val upper = signal(id = "u", tag = "Screen", name = "Home", mono = 100)
        val result = visits(listOf(upper), listOf(call("a", 150)), emptyList())
        assertEquals("Home[a]", shape(result))
        assertTrue(result.single().repeats.isEmpty())
    }
}

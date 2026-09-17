package dev.inspector.ui

import dev.inspector.model.FilterContext
import dev.inspector.model.FilterParser
import dev.inspector.model.Marker
import dev.inspector.model.NetworkTransaction
import dev.inspector.model.matches
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a marker chip actually does when tapped.
 *
 * The chip's whole claim is "show me what happened after this". Asserting the string it emits
 * would only prove it matches whatever the string was written to be, so these parse it with the
 * real grammar and run it over real rows — the same path a typed filter takes.
 */
class MarkerFilterTest {

    private fun txn(id: String, mono: Long) = NetworkTransaction(
        id = id, ts = "2026-09-17T09:00:00Z", mono = mono, method = "GET",
        scheme = "https", host = "api.example.com", path = "/v1/$id", status = 200,
        callId = id,
    )

    private fun mark(label: String, mono: Long) = Marker(
        ts = "2026-09-17T09:00:00Z", mono = mono, label = label, source = "user",
    )

    private fun rowsAfter(label: String, markers: List<Marker>, rows: List<NetworkTransaction>):
        List<String> {
        val filter = FilterParser.parse(markerFilterTerm(label)).getOrThrow()
        return rows.filter { filter.matches(it, FilterContext(markers)) }.map { it.id }
    }

    @Test
    fun `tapping a chip keeps only what happened after that marker`() {
        val markers = listOf(mark("checkout", 200))
        val rows = listOf(txn("before", 100), txn("after", 300))
        assertEquals(listOf("after"), rowsAfter("checkout", markers, rows))
    }

    @Test
    fun `a label with spaces survives the round trip`() {
        // The reason the grammar quotes at all. A chip is the only way most people will ever use
        // this form, so it is the one that has to get the quoting right.
        val markers = listOf(mark("tapped checkout", 200))
        val rows = listOf(txn("before", 100), txn("after", 300))
        assertEquals(listOf("after"), rowsAfter("tapped checkout", markers, rows))
    }

    @Test
    fun `the right marker is picked when several share the list`() {
        val markers = listOf(mark("launch", 10), mark("checkout", 200), mark("done", 900))
        val rows = listOf(txn("a", 100), txn("b", 300), txn("c", 950))
        assertEquals(listOf("b", "c"), rowsAfter("checkout", markers, rows))
        assertEquals(listOf("a", "b", "c"), rowsAfter("launch", markers, rows))
    }

    /**
     * What the chip list's filter is actually for, measured rather than assumed.
     *
     * It is the **parity** of the quote count in the label, not the presence of a quote. The
     * tokenizer toggles on every `"`, so an even number closes and an odd number leaves the token
     * open; `parseMarkerRef` then recovers the label with a prefix/suffix match rather than by
     * parsing quotes, so an even-quoted label survives intact. `say "hello"` works and `it"s` does
     * not, which is not what anyone would guess from reading either.
     */
    @Test
    fun `a label with an odd number of quotes cannot be expressed`() {
        for (label in listOf("it\"s", "hi\"", "a\"b\"c\"")) {
            assertTrue(
                FilterParser.parse(markerFilterTerm(label)).isFailure,
                "'$label' was expected to produce an unparseable term",
            )
        }
    }

    @Test
    fun `an ordinary label is expressible`() {
        // The other direction: the rejection above must not be quietly rejecting everything. The
        // even-quoted cases are here because they are the surprising half of the rule.
        for (label in listOf("checkout", "tapped checkout", "screen:home", "step-3", "café",
                "say \"hello\"")) {
            assertTrue(
                FilterParser.parse(markerFilterTerm(label)).isSuccess,
                "chip for '$label' produced a term the parser rejected",
            )
        }
    }

    @Test
    fun `an even-quoted label still selects the right rows`() {
        // Not merely parseable — the recovered label has to be the one that was marked, or the
        // chip would filter on something the user never saw.
        val markers = listOf(mark("say \"hello\"", 200))
        val rows = listOf(txn("before", 100), txn("after", 300))
        assertEquals(listOf("after"), rowsAfter("say \"hello\"", markers, rows))
    }

    @Test
    fun `an unknown label matches nothing rather than everything`() {
        // Pinned here because it is what makes a stale chip safe. The chips are built from markers
        // that exist, but `clear` empties the markers while a filter is still applied.
        val filter = FilterParser.parse(markerFilterTerm("never-marked")).getOrThrow()
        val context = FilterContext(listOf(mark("checkout", 200)))
        assertFalse(listOf(txn("a", 100), txn("b", 300)).any { filter.matches(it, context) })
    }
}

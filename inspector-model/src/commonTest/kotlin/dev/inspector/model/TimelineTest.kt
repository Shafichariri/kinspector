package dev.inspector.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Traffic and markers on one clock.
 *
 * The assertions are written as a shape — `M` for a marker and the call's id for a row — because
 * what matters here is the sequence, and comparing whole objects would bury it.
 */
class TimelineTest {

    private fun txn(id: String, mono: Long) = NetworkTransaction(
        id = id, ts = "2026-09-17T09:00:00Z", mono = mono, method = "GET",
        scheme = "https", host = "api.example.com", path = "/v1/$id", status = 200,
        callId = id,
    )

    private fun mark(label: String, mono: Long) = Marker(
        ts = "2026-09-17T09:00:00Z", mono = mono, label = label, source = "user",
    )

    /** `a b M:checkout c` reads as ids and marker labels in the order they would be drawn. */
    private fun shape(entries: List<TimelineEntry>) = entries.joinToString(" ") {
        when (it) {
            is TimelineEntry.Call -> it.txn.id
            is TimelineEntry.Mark -> "M:${it.marker.label}"
        }
    }

    @Test
    fun `a marker lands before the traffic that followed it`() {
        val entries = timeline(
            transactions = listOf(txn("a", 100), txn("b", 300)),
            markers = listOf(mark("checkout", 200)),
        )
        assertEquals("a M:checkout b", shape(entries))
    }

    /**
     * The whole reason this is one function rather than a sort.
     *
     * Newest-first is the *reverse of the ascending sequence*, not a descending sort. Both put the
     * newest row at the top; only one keeps each divider attached to the rows it introduced. Here
     * the marker must stay between `b` and `a` — reading downward, `a` is still below the marker
     * and still happened before it.
     */
    @Test
    fun `newest-first reverses the sequence rather than re-sorting it`() {
        val ascending = timeline(
            transactions = listOf(txn("a", 100), txn("b", 300)),
            markers = listOf(mark("checkout", 200)),
        )
        val descending = timeline(
            transactions = listOf(txn("a", 100), txn("b", 300)),
            markers = listOf(mark("checkout", 200)),
            newestFirst = true,
        )
        assertEquals("a M:checkout b", shape(ascending))
        assertEquals("b M:checkout a", shape(descending))
        assertEquals(ascending.asReversed(), descending)
    }

    /**
     * The case that tells a reverse from a re-sort, and the reason the test above is not enough on
     * its own: while every `mono` is distinct, `sortedByDescending` and `reversed` agree, so a
     * re-sort passes. They part company on a tie — and a tie is not exotic, it is what a marker
     * dropped immediately before a request looks like on a millisecond clock.
     *
     * Ascending places the marker before the call it introduces, so reversing must place it
     * after. A stable descending sort keeps the marker *ahead* of the call instead, which reads as
     * "this call happened before the marker" — the opposite of the truth.
     */
    @Test
    fun `a marker tied with a call reverses with it`() {
        val tied = timeline(
            transactions = listOf(txn("a", 100), txn("b", 200)),
            markers = listOf(mark("tap", 200)),
            newestFirst = true,
        )
        assertEquals("b M:tap a", shape(tied))
    }

    @Test
    fun `a marker at exactly a call's mono introduces that call`() {
        // `<=`, not `<`. A marker is dropped just before the thing it is marking.
        val entries = timeline(listOf(txn("a", 100), txn("b", 200)), listOf(mark("tap", 200)))
        assertEquals("a M:tap b", shape(entries))
    }

    @Test
    fun `a marker after the last call is still drawn`() {
        // "I marked it and then nothing happened" is an answer. A marker that only appeared once
        // the next request arrived would hide exactly that.
        val entries = timeline(listOf(txn("a", 100)), listOf(mark("idle", 900)))
        assertEquals("a M:idle", shape(entries))
    }

    @Test
    fun `a marker before any traffic comes first`() {
        val entries = timeline(listOf(txn("a", 500)), listOf(mark("launch", 10)))
        assertEquals("M:launch a", shape(entries))
    }

    @Test
    fun `several markers between two calls keep their own order`() {
        val entries = timeline(
            transactions = listOf(txn("a", 100), txn("b", 900)),
            // Deliberately supplied out of order: the ring appends in arrival order, but an agent
            // posting a marker over HTTP supplies its own mono and can arrive late.
            markers = listOf(mark("third", 500), mark("first", 200), mark("second", 300)),
        )
        assertEquals("a M:first M:second M:third b", shape(entries))
    }

    @Test
    fun `no markers is the list unchanged`() {
        val entries = timeline(listOf(txn("a", 100), txn("b", 200)), emptyList())
        assertEquals("a b", shape(entries))
    }

    @Test
    fun `no traffic is the markers alone`() {
        val entries = timeline(emptyList(), listOf(mark("launch", 10), mark("idle", 20)))
        assertEquals("M:launch M:idle", shape(entries))
    }

    @Test
    fun `out of order transactions are placed by mono`() {
        // The ring is append-ordered, so this should not happen from capture — but a filtered or
        // concatenated list can arrive in any order and the dividers still have to be right.
        val entries = timeline(listOf(txn("b", 300), txn("a", 100)), listOf(mark("checkout", 200)))
        assertEquals("a M:checkout b", shape(entries))
    }

    @Test
    fun `labels are distinct and most recent first`() {
        val labels = markerLabels(
            listOf(
                mark("launch", 10),
                mark("checkout", 200),
                // The same label dropped again on a second pass through the same screen.
                mark("checkout", 900),
            ),
        )
        assertEquals(listOf("checkout", "launch"), labels)
    }

    @Test
    fun `no markers is no labels`() {
        assertEquals(emptyList(), markerLabels(emptyList()))
    }
}

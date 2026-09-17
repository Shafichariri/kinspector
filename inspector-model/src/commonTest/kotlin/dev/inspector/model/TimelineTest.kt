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

    private var signalSeq = 0

    private fun signal(tag: String, name: String, mono: Long) = Signal(
        id = "s${++signalSeq}", ts = "2026-09-17T09:00:00Z", mono = mono, tag = tag, name = name,
    )

    /** `a M:checkout S:screen/home b` reads in the order the entries would be drawn. */
    private fun shape(entries: List<TimelineEntry>) = entries.joinToString(" ") {
        when (it) {
            is TimelineEntry.Call -> it.txn.id
            is TimelineEntry.Mark -> "M:${it.marker.label}"
            is TimelineEntry.Observation -> "S:${it.signal.tag}/${it.signal.name}"
        }
    }

    /** The same, for runs: a run of several reads as `S:state/form×3`. */
    private fun runShape(runs: List<TimelineRun>) = runs.joinToString(" ") { run ->
        val head = shape(listOf(run.first))
        if (run.entries.size == 1) head else "$head×${run.entries.size}"
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
    // --- signals ----------------------------------------------------------------------------

    @Test
    fun `signals land between the calls they happened between`() {
        val entries = timeline(
            transactions = listOf(txn("a", 100), txn("b", 300)),
            markers = emptyList(),
            signals = listOf(signal("screen", "home", 200)),
        )
        assertEquals("a S:screen/home b", shape(entries))
    }

    @Test
    fun `traffic signals and markers merge on one clock`() {
        val entries = timeline(
            transactions = listOf(txn("a", 100), txn("b", 500)),
            markers = listOf(mark("checkout", 300)),
            signals = listOf(signal("screen", "cart", 200), signal("state", "form", 400)),
        )
        assertEquals("a S:screen/cart M:checkout S:state/form b", shape(entries))
    }

    @Test
    fun `no signals is the list unchanged`() {
        // The defaulted parameter has to leave every existing caller reading identically.
        val withNone = timeline(listOf(txn("a", 100), txn("b", 300)), listOf(mark("m", 200)))
        val withEmpty = timeline(listOf(txn("a", 100), txn("b", 300)), listOf(mark("m", 200)), emptyList())
        assertEquals(shape(withNone), shape(withEmpty))
        assertEquals("a M:m b", shape(withNone))
    }

    @Test
    fun `a signal and a call at the same millisecond keep a stable order`() {
        // Which came first is unknowable at this resolution. What matters is that two renders of
        // the same data agree, so the list does not reshuffle under the reader.
        val once = timeline(listOf(txn("a", 200)), emptyList(), listOf(signal("state", "form", 200)))
        val again = timeline(listOf(txn("a", 200)), emptyList(), listOf(signal("state", "form", 200)))
        assertEquals(shape(once), shape(again))
    }

    @Test
    fun `signals reverse with everything else`() {
        val entries = timeline(
            transactions = listOf(txn("a", 100), txn("b", 300)),
            markers = emptyList(),
            signals = listOf(signal("screen", "home", 200)),
            newestFirst = true,
        )
        assertEquals("b S:screen/home a", shape(entries))
    }

    // --- runs -------------------------------------------------------------------------------

    @Test
    fun `adjacent identical observations collapse into one run`() {
        val entries = timeline(
            transactions = emptyList(),
            markers = emptyList(),
            signals = listOf(
                signal("state", "form", 100),
                signal("state", "form", 110),
                signal("state", "form", 120),
            ),
        )
        assertEquals("S:state/form×3", runShape(timelineRuns(entries)))
    }

    /**
     * The rule that keeps a run from erasing the ordering the merged view exists for.
     *
     * A run interrupted by a call says the state settled, something else happened, and it moved
     * again. Collapsing across that gap would make three separate episodes look like one.
     */
    @Test
    fun `a call in the middle splits a run in two`() {
        val entries = timeline(
            transactions = listOf(txn("a", 115)),
            markers = emptyList(),
            signals = listOf(
                signal("state", "form", 100),
                signal("state", "form", 110),
                signal("state", "form", 120),
                signal("state", "form", 130),
            ),
        )
        assertEquals("S:state/form×2 a S:state/form×2", runShape(timelineRuns(entries)))
    }

    @Test
    fun `a marker also splits a run`() {
        val entries = timeline(
            transactions = emptyList(),
            markers = listOf(mark("tapped", 115)),
            signals = listOf(signal("state", "form", 100), signal("state", "form", 120)),
        )
        assertEquals("S:state/form M:tapped S:state/form", runShape(timelineRuns(entries)))
    }

    @Test
    fun `a different name does not join the run`() {
        val entries = timeline(
            transactions = emptyList(),
            markers = emptyList(),
            signals = listOf(
                signal("state", "form", 100),
                signal("state", "other", 110),
                signal("state", "form", 120),
            ),
        )
        assertEquals("S:state/form S:state/other S:state/form", runShape(timelineRuns(entries)))
    }

    @Test
    fun `transactions never group even when identical`() {
        // Two calls to the same endpoint are two separate facts, and the repetition is exactly
        // what the list exists to show. `duplicateGroups` says they are related; it does not say
        // they are one row.
        val entries = timeline(listOf(txn("a", 100), txn("b", 110)), emptyList())
        assertEquals("a b", runShape(timelineRuns(entries)))
    }

    @Test
    fun `a run reports how long it covers`() {
        val entries = timeline(
            transactions = emptyList(),
            markers = emptyList(),
            signals = listOf(
                signal("state", "form", 1_000),
                signal("state", "form", 3_400),
            ),
        )
        val run = timelineRuns(entries).single()
        assertEquals(2, run.entries.size)
        // "48 times" and "48 times over 23 seconds" say different things about the app.
        assertEquals(2_400, run.spanMs)
    }

    /**
     * An expanded run has to stay expanded while the traffic it sits in keeps arriving.
     *
     * Both orders, because they fail differently. A run grows at its **newest** end, so ascending
     * it grows at the tail and the head is stable — but in newest-first the head *is* the new
     * member, and an id taken from the head would re-key on every observation and collapse the
     * run under the reader, in precisely the mode someone watching live traffic is in.
     */
    @Test
    fun `a run keeps its id while it grows in either order`() {
        val first = signal("state", "form", 100)
        val second = signal("state", "form", 110)
        for (newest in listOf(false, true)) {
            val before = timelineRuns(
                timeline(emptyList(), emptyList(), listOf(first), newestFirst = newest),
            ).single()
            val after = timelineRuns(
                timeline(emptyList(), emptyList(), listOf(first, second), newestFirst = newest),
            ).single()
            assertEquals(before.id, after.id, "id moved while growing, newestFirst=$newest")
        }
    }

    @Test
    fun `a run keeps its id when the reading order flips`() {
        // Otherwise every expanded run collapses the moment someone taps the order toggle.
        val signals = listOf(signal("state", "form", 100), signal("state", "form", 200))
        val ascending = timelineRuns(timeline(emptyList(), emptyList(), signals)).single()
        val descending = timelineRuns(
            timeline(emptyList(), emptyList(), signals, newestFirst = true),
        ).single()
        assertEquals(ascending.id, descending.id)
    }

    @Test
    fun `a run reports the same span in either order`() {
        val signals = listOf(signal("state", "form", 100), signal("state", "form", 900))
        val ascending = timelineRuns(timeline(emptyList(), emptyList(), signals)).single()
        val descending = timelineRuns(
            timeline(emptyList(), emptyList(), signals, newestFirst = true),
        ).single()
        assertEquals(800, ascending.spanMs)
        assertEquals(ascending.spanMs, descending.spanMs)
    }

    @Test
    fun `a reversed run draws newest first but still knows which member was earliest`() {
        val entries = timeline(
            transactions = listOf(txn("a", 300)),
            markers = emptyList(),
            signals = listOf(signal("state", "form", 100), signal("state", "form", 200)),
            newestFirst = true,
        )
        val run = timelineRuns(entries).last()
        assertEquals("a S:state/form×2", runShape(timelineRuns(entries)))
        // `first` is the drawing head and follows the order; `earliest` does not.
        assertEquals(200, run.first.mono)
        assertEquals(100, run.earliest.mono)
    }

    @Test
    fun `an empty list has no runs`() {
        assertEquals(emptyList(), timelineRuns(emptyList()))
    }

}

package dev.inspector.model

/**
 * One entry in a traffic list that also shows markers.
 *
 * A sealed type rather than a nullable pair so a renderer has to say what it does with each kind;
 * the alternative silently drops markers on any branch that forgets them, which is the bug this
 * exists to fix — the overlay received markers for four releases and drew none of them.
 */
sealed interface TimelineEntry {
    /** The device clock this entry sits at. Always [NetworkTransaction.mono], never `ts`. */
    val mono: Long

    data class Call(val txn: NetworkTransaction) : TimelineEntry {
        override val mono: Long get() = txn.mono
    }

    data class Mark(val marker: Marker) : TimelineEntry {
        override val mono: Long get() = marker.mono
    }
}

/**
 * Traffic and markers on one device clock, as a list to draw top to bottom.
 *
 * **Interleaved oldest-first and then reversed, never sorted descending.** The arrangement that
 * makes causal sense is "a marker, then the rows that happened after it", and that is only
 * expressible while ascending. Reversing the finished sequence keeps every divider attached to the
 * same rows: read downward in newest-first and a divider *below* a row still means that row
 * happened after the marker. Sorting descending instead would attach each divider to the rows
 * before it, which is the same pixels saying the opposite thing. `app.js` carries a mirror of this
 * for the web UI; keep them in step.
 *
 * A marker at exactly a transaction's `mono` is placed **before** it — `<=`, not `<`. A marker is
 * dropped at the moment something is about to happen, so the boundary belongs on the side of the
 * traffic it is there to introduce.
 *
 * Timing uses `mono` on both sides. `ts` is a wall clock that can step, and a marker posted by an
 * agent over HTTP carries the device's `mono` precisely so it can be placed here.
 *
 * @param newestFirst reverses the finished sequence. It is a parameter rather than the caller's
 *   job only to keep the "reverse last" rule in one place.
 */
fun timeline(
    transactions: List<NetworkTransaction>,
    markers: List<Marker>,
    newestFirst: Boolean = false,
): List<TimelineEntry> {
    val ordered = markers.sortedBy { it.mono }
    val entries = ArrayList<TimelineEntry>(transactions.size + ordered.size)
    var next = 0
    for (txn in transactions.sortedBy { it.mono }) {
        while (next < ordered.size && ordered[next].mono <= txn.mono) {
            entries += TimelineEntry.Mark(ordered[next])
            next++
        }
        entries += TimelineEntry.Call(txn)
    }
    // Markers after the last call still belong on screen: "I marked this, then nothing happened"
    // is an answer, and a marker that vanished until the next request would hide it.
    while (next < ordered.size) {
        entries += TimelineEntry.Mark(ordered[next])
        next++
    }
    return if (newestFirst) entries.asReversed().toList() else entries
}

/**
 * The distinct marker labels, most recent first.
 *
 * Distinct because a label repeated every time a screen opens is one thing to filter on, not
 * twenty. Most recent first because on a small screen only the first few are reachable without
 * scrolling, and the one just dropped is the one being looked for.
 */
fun markerLabels(markers: List<Marker>): List<String> =
    markers.sortedByDescending { it.mono }.map { it.label }.distinct()

package dev.inspector.model

/**
 * One entry in a traffic list that also shows markers and signals.
 *
 * A sealed type rather than a nullable pair so a renderer has to say what it does with each kind;
 * the alternative silently drops an entry on any branch that forgets it, which is the bug this
 * exists to fix — the overlay received markers for four releases and drew none of them, and it
 * received signals for the same four. Adding [Observation] broke every exhaustive `when` in the
 * repository on purpose, which is the type doing its job.
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

    data class Observation(val signal: Signal) : TimelineEntry {
        override val mono: Long get() = signal.mono
    }
}

/**
 * One or more adjacent entries drawn as a single row.
 *
 * Every entry is a run; most runs hold one thing. A run of several is always signals, because
 * [runKeyOf] gives transactions and markers no key — two calls to the same endpoint are two
 * separate facts and collapsing them would hide the repetition the list exists to show.
 */
data class TimelineRun(
    val entries: List<TimelineEntry>,
    /** Null for a run that can never grow: a transaction, or a marker. */
    val key: String?,
) {
    /** The member drawn at the top of the run — whichever way the list is being read. */
    val first: TimelineEntry get() = entries.first()

    /**
     * The member that happened first, which is not [first] in newest-first order.
     *
     * [entries] is in draw order, because [timelineRuns] groups an already-reversed list. Anything
     * that must mean the same thing in both orders has to say so explicitly rather than take the
     * head of the list.
     */
    val earliest: TimelineEntry get() = entries.minBy { it.mono }

    /** How long the run covers, in device milliseconds. Zero for a run of one. */
    val spanMs: Long get() = entries.maxOf { it.mono } - entries.minOf { it.mono }

    /**
     * Stable while new entries arrive and when the reading order flips, so an expanded run stays
     * expanded.
     *
     * Keyed on the **earliest** member, never on the head of [entries]. A run grows at its newest
     * end, so in newest-first order the head changes on every observation — an id taken from it
     * would re-key the run and collapse it under the reader in exactly the mode where traffic is
     * arriving. Taking the earliest also makes the id survive flipping the order, which the head
     * would not.
     */
    val id: String
        get() {
            val head = earliest
            val token = (head as? TimelineEntry.Observation)?.signal?.id ?: head.mono.toString()
            return "${key.orEmpty()}\u0000$token"
        }
}

/** What makes two adjacent entries "the same thing happening again". */
private fun runKeyOf(entry: TimelineEntry): String? =
    (entry as? TimelineEntry.Observation)?.let { "${it.signal.tag}\u0000${it.signal.name}" }

/**
 * Groups **consecutive** identical observations, so a chatty state holder cannot bury the traffic.
 *
 * A state holder that emits on every keystroke produces dozens of adjacent rows differing only in
 * a payload the row cannot show — one real session had 48 in a row — and on a phone that is the
 * whole screen. The count and the span are what a collapsed run must not lose: "48 times" and "48
 * times over 23 seconds" say different things about the app.
 *
 * **Only adjacent entries group.** A run interrupted by a call or a screen change is information:
 * it says the state settled, something else happened, and it moved again. Collapsing across that
 * gap would erase the ordering the merged view exists for.
 *
 * Grouping happens on the list as it will be drawn, so in newest-first order a run's members are
 * newest first too and [TimelineRun.first] is the *latest*, not the earliest. Anything that must
 * mean the same thing in both orders — the span, the id — reads [TimelineRun.earliest] instead.
 * The web UI's `timelineRuns` block carries a mirror of the grouping; keep them in step.
 */
fun timelineRuns(entries: List<TimelineEntry>): List<TimelineRun> {
    val runs = ArrayList<MutableList<TimelineEntry>>()
    val keys = ArrayList<String?>()
    for (entry in entries) {
        val key = runKeyOf(entry)
        if (key != null && keys.isNotEmpty() && keys.last() == key) {
            runs.last() += entry
        } else {
            runs += mutableListOf(entry)
            keys += key
        }
    }
    return runs.mapIndexed { index, members -> TimelineRun(members, keys[index]) }
}

/**
 * Traffic, signals and markers on one device clock, as a list to draw top to bottom.
 *
 * **Interleaved oldest-first and then reversed, never sorted descending.** The arrangement that
 * makes causal sense is "a marker, then the rows that happened after it", and that is only
 * expressible while ascending. Reversing the finished sequence keeps every divider attached to the
 * same rows: read downward in newest-first and a divider *below* a row still means that row
 * happened after the marker. Sorting descending instead would attach each divider to the rows
 * before it, which is the same pixels saying the opposite thing. The web UI's `visits` block
 * carries a mirror of this, as `timelineEntries`; keep them in step.
 *
 * A marker at exactly a transaction's `mono` is placed **before** it — `<=`, not `<`. A marker is
 * dropped at the moment something is about to happen, so the boundary belongs on the side of the
 * traffic it is there to introduce.
 *
 * **Inside one millisecond, rows that carry capture `seq` go by it** — calls, signals and device
 * markers alike — in the places those rows already hold; see [orderTiesBySeq]. A row without `seq`
 * (an older archive, a marker the host posted) keeps the place the rules above give it, so `<=`
 * now decides only where capture order is unknown.
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
    signals: List<Signal> = emptyList(),
    newestFirst: Boolean = false,
): List<TimelineEntry> {
    val ordered = markers.sortedBy { it.mono }
    val body = ArrayList<TimelineEntry>(transactions.size + signals.size)
    transactions.mapTo(body) { TimelineEntry.Call(it) }
    signals.mapTo(body) { TimelineEntry.Observation(it) }
    // Stable sort, so a signal and a call at the same millisecond keep the order they were added
    // in — calls first. That is only the starting order: orderTiesBySeq below corrects it wherever
    // `seq` says what was captured first, and it is the answer only where `seq` cannot.
    body.sortBy { it.mono }

    val entries = ArrayList<TimelineEntry>(body.size + ordered.size)
    var next = 0
    for (entry in body) {
        while (next < ordered.size && ordered[next].mono <= entry.mono) {
            entries += TimelineEntry.Mark(ordered[next])
            next++
        }
        entries += entry
    }
    // Markers after the last entry still belong on screen: "I marked this, then nothing happened"
    // is an answer, and a marker that vanished until the next request would hide it.
    while (next < ordered.size) {
        entries += TimelineEntry.Mark(ordered[next])
        next++
    }
    orderTiesBySeq(entries)
    return if (newestFirst) entries.asReversed().toList() else entries
}

/** Capture order, private so the public sealed [TimelineEntry] gains no member. */
private val TimelineEntry.seq: Long?
    get() = when (this) {
        is TimelineEntry.Call -> txn.seq
        is TimelineEntry.Mark -> marker.seq
        is TimelineEntry.Observation -> signal.seq
    }

/**
 * Inside each run of equal `mono`, the members carrying `seq` take their own slots in `seq` order.
 *
 * `onClick { signal("tap"); navigate() }` puts a tap and the call it fires in one millisecond, and
 * the stable sort alone drew the call first on every surface. The obvious fix, one comparator of
 * `mono` then `seq ?: Long.MIN_VALUE`, moves a row *without* `seq` ahead of a tied row that has
 * one — a signal from an older library jumping ahead of a call — and `schema.md` says to use `seq`
 * only between two rows that both carry it. Comparing only when both carry it is intransitive, and
 * a sort may throw on that. Re-sorting the `seq` members among the slots they already hold needs no
 * comparator over mixed rows at all: inside the slots the key is one counter's value, which is
 * total, and every row without `seq`, a host marker included, stays exactly where it was.
 *
 * [entries] is non-decreasing in `mono`, so each tie is a contiguous run. Allocates only for a run
 * holding two or more `seq` members.
 */
private fun orderTiesBySeq(entries: MutableList<TimelineEntry>) {
    var start = 0
    while (start < entries.size) {
        val mono = entries[start].mono
        var end = start
        var carrying = 0
        while (end < entries.size && entries[end].mono == mono) {
            if (entries[end].seq != null) carrying++
            end++
        }
        if (carrying > 1) {
            val slots = (start until end).filter { entries[it].seq != null }
            val bySeq = slots.map { entries[it] }.sortedBy { it.seq!! }
            slots.forEachIndexed { i, slot -> entries[slot] = bySeq[i] }
        }
        start = end
    }
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

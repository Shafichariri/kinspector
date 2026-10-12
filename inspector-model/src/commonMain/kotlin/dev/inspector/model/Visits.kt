package dev.inspector.model

/**
 * One stretch of a session spent on one screen: the unit the web UI's path and waterfall views
 * are both drawn from.
 *
 * @property screen the `screen` signal that opened it, or null for the stretch before the first
 *   one — which is also the whole session when the app never emits `screen` at all. Null is said,
 *   never guessed: the app was somewhere, and nothing recorded where.
 * @property repeats later `screen` signals with the same name that arrived while this visit was
 *   open. They do not start a visit — see [visits] — and they are also in [entries], in place.
 * @property fromMono where the visit starts. The opening signal's `mono`; for the stretch before
 *   the first screen, the earliest thing in it.
 * @property toMono the next visit's [fromMono], or null for the last visit, which is still open
 *   as far as this session knows.
 * @property entries everything that started inside the visit, in [timeline] order.
 */
data class Visit(
    val screen: Signal?,
    val repeats: List<Signal>,
    val fromMono: Long,
    val toMono: Long?,
    val entries: List<TimelineEntry>,
)

private fun isScreen(signal: Signal) = signal.tag.lowercase() == SignalTags.SCREEN

/**
 * Splits a session into the screens the user was on, and what happened on each.
 *
 * **Attribution is strictly by start.** An entry belongs to the visit open at its `mono`, and a
 * visit owns `[fromMono, toMono)` — so a call at exactly the millisecond a screen arrived belongs
 * to the screen that arrived. There is no grace window for a `screen` signal that lands a few
 * milliseconds after the calls its view-model fired: any window would also move the `POST` that
 * *caused* the navigation onto the screen it navigated to, which is the more important call to get
 * right. The fix for a late signal is in the app — emit it when navigation commits — and
 * `INTEGRATION.md` says so.
 *
 * **A tie in the same millisecond is decided by `seq`.** `onClick { signal("tap", …); navigate() }`
 * routinely puts a tap and the screen it triggers in one millisecond, and handing every tie to the
 * arriving screen attributed the tap to the screen it left for. When both sides carry a capture
 * `seq`, the one captured first is first; when either lacks it — an older archive, a marker posted
 * by the host — the tie still goes to the arriving screen, as before. A call that outlives its visit stays on it; [visitIndexAt] is how a
 * renderer says where it finished.
 *
 * **Consecutive same-name signals are one visit.** A recomposition re-firing an effect, or a
 * wizard stepping through one route, would otherwise draw `Checkout → Checkout → Checkout` — a
 * navigation nobody made. The later signals are kept as [Visit.repeats] and drawn inside the
 * visit, because a double-firing effect is sometimes the bug being hunted.
 *
 * **The skeleton is passed separately from the contents.** The shared filter grammar drops every
 * signal under a transaction term like `path:` and every call under `tag:`, so a skeleton built from
 * the filtered rows would lose screens the moment anyone typed a filter — and a visit with nothing
 * matching would vanish, drawing `Product → Checkout` for a user who went through `Cart`. A route
 * with a hole in it is a fabricated navigation. Callers pass the session's unfiltered `screen`
 * signals as [screens] and whatever survived the filter as the rest; a visit with no matching
 * entries is still returned, empty. `screen` signals in [signals] are ignored for the same reason —
 * the skeleton is the only source of them.
 *
 * The leading visit is part of the skeleton too, so whether it exists cannot depend on the filter
 * either: pass the earliest `mono` of the unfiltered session as [sessionStart]. Without it, a filter
 * matching nothing at launch removed the launch column — which a probe caught on the first run.
 *
 * `app.js` carries a mirror; keep them in step.
 */
fun visits(
    screens: List<Signal>,
    transactions: List<NetworkTransaction>,
    markers: List<Marker>,
    signals: List<Signal> = emptyList(),
    sessionStart: Long? = null,
): List<Visit> {
    val arrivals = screens.filter(::isScreen)
        .sortedWith(compareBy<Signal> { it.mono }.thenBy { it.seq ?: Long.MIN_VALUE })
    val others = signals.filterNot(::isScreen)

    // The skeleton: one opening signal per visit, plus any same-name signals that followed it.
    val openers = ArrayList<Signal>()
    val repeats = ArrayList<MutableList<Signal>>()
    for (arrival in arrivals) {
        if (openers.isNotEmpty() && openers.last().name == arrival.name) {
            repeats.last().add(arrival)
        } else {
            openers += arrival
            repeats.add(mutableListOf())
        }
    }

    val firstArrival = openers.firstOrNull()?.mono
    val earliest = listOfNotNull(
        sessionStart,
        transactions.minOfOrNull { it.mono },
        markers.minOfOrNull { it.mono },
        others.minOfOrNull { it.mono },
    ).minOrNull()
    // A leading stretch exists when something happened before the first screen, or when there is
    // no screen at all and something happened. A session holding nothing has no visits.
    val leading = earliest != null && (firstArrival == null || earliest < firstArrival)

    val starts = ArrayList<Start>()
    if (leading) starts += Start(earliest!!, null)
    openers.mapTo(starts) { Start(it.mono, it.seq) }
    if (starts.isEmpty()) return emptyList()
    val offset = if (leading) 1 else 0

    val calls = List(starts.size) { ArrayList<NetworkTransaction>() }
    val marks = List(starts.size) { ArrayList<Marker>() }
    val observed = List(starts.size) { ArrayList<Signal>() }
    for (txn in transactions) calls[ownerOf(starts, txn.mono, txn.seq)] += txn
    for (marker in markers) marks[ownerOf(starts, marker.mono, marker.seq)] += marker
    for (signal in others) observed[ownerOf(starts, signal.mono, signal.seq)] += signal
    for ((i, extra) in repeats.withIndex()) observed[i + offset].addAll(extra)

    return starts.indices.map { i ->
        val opener = if (i < offset) null else openers[i - offset]
        Visit(
            screen = opener,
            repeats = if (opener == null) emptyList() else repeats[i - offset],
            fromMono = starts[i].mono,
            toMono = starts.getOrNull(i + 1)?.mono,
            entries = timeline(calls[i], marks[i], observed[i].sortedBy { it.mono }),
        )
    }
}

private class Start(val mono: Long, val seq: Long?)

/** Whether [start] opened at or before an entry at ([mono], [seq]): `seq` decides a tie when both have one. */
private fun opensBy(start: Start, mono: Long, seq: Long?): Boolean = when {
    start.mono != mono -> start.mono < mono
    start.seq == null || seq == null -> true
    else -> start.seq < seq
}

/** The last start at or before the entry; anything earlier than every start belongs to the first. */
private fun ownerOf(starts: List<Start>, mono: Long, seq: Long?): Int {
    var lo = 0
    var hi = starts.size - 1
    var found = 0
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (opensBy(starts[mid], mono, seq)) { found = mid; lo = mid + 1 } else hi = mid - 1
    }
    return found
}

/**
 * Which visit was open at [mono], or -1 when it is before all of them.
 *
 * For saying where something *finished*: a call's `mono + ms` past its own visit's [Visit.toMono]
 * lands here on a later visit, and that is the "finished on Cart" a renderer draws — a cue, never a
 * reassignment.
 */
fun visitIndexAt(visits: List<Visit>, mono: Long): Int {
    if (visits.isEmpty() || mono < visits.first().fromMono) return -1
    return ownerOf(visits.map { Start(it.fromMono, null) }, mono, null)
}

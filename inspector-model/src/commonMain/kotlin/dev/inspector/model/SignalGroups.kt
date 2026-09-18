package dev.inspector.model

/**
 * Observations grouped by what they observe.
 *
 * `(tag, name)` is the grouping key the schema already names — `Signal.name` is documented as
 * "app-defined identity within `tag`", and the daemon's last-wins `current` query uses the same
 * pair. Grouping anywhere else would invent a second identity for the same row.
 *
 * Shared rather than written twice: `app.js` builds the same grouping for its tag browsers, and
 * the overlay needs it for a signal's history. Both are answering one question — *every time the
 * app told us about this thing, in order* — and two implementations of that answer are two
 * answers waiting to disagree.
 */
data class SignalKey(val tag: String, val name: String)

/** Every observation of one [SignalKey], oldest first. */
data class SignalGroup(
    val key: SignalKey,
    /**
     * Ascending by `mono`, always — **not** in draw order.
     *
     * `TimelineRun.members` is in draw order and its `first` therefore flips with the sort toggle,
     * which is a trap that already cost a re-keyed run during live tail. A history is read as a
     * sequence of changes, so it has one true order and does not take the list's.
     */
    val observations: List<Signal>,
) {
    /** The most recent observation. What "the current value" means for this key. */
    val latest: Signal get() = observations.last()

    /** The first one recorded, which is not the same as the first the app ever knew. */
    val earliest: Signal get() = observations.first()

    val count: Int get() = observations.size

    /** How long the key was under observation, or null when there is only one. */
    val spanMs: Long? get() = if (observations.size < 2) null else latest.mono - earliest.mono
}

/**
 * One group per `(tag, name)`, most recently observed first.
 *
 * Recency is right here where it is wrong for [endpointShortcuts]: that orders chips that must
 * stay aimable while traffic arrives, and this orders a list somebody is reading to find what
 * changed. What changed last is what they are looking for.
 */
fun signalGroups(signals: List<Signal>): List<SignalGroup> {
    val byKey = LinkedHashMap<SignalKey, MutableList<Signal>>()
    for (signal in signals) {
        byKey.getOrPut(SignalKey(signal.tag, signal.name)) { mutableListOf() }.add(signal)
    }
    return byKey.map { (key, observations) -> SignalGroup(key, observations.sortedBy { it.mono }) }
        .sortedWith(
            compareByDescending<SignalGroup> { it.latest.mono }
                // Ties break on the name, so the order is fully determined and a test can assert
                // it rather than assert a set. Two signals can share a `mono`: the conflation
                // window emits a whole batch at once.
                .thenBy { it.key.tag }
                .thenBy { it.key.name },
        )
}

/**
 * Every observation of one key, oldest first.
 *
 * Takes the pair rather than a [SignalGroup] so a caller holding only a single [Signal] — which is
 * what a detail screen is given — can ask for its siblings without first building every group.
 */
fun signalHistory(signals: List<Signal>, tag: String, name: String): List<Signal> =
    signals.filter { it.tag == tag && it.name == name }.sortedBy { it.mono }

/** One tag worth a chip, and how much of the session it accounts for. */
data class TagShortcut(
    val tag: String,
    /** How many observations carry this tag. */
    val count: Int,
    /** The most recent one's `mono`, used only to break ties deterministically. */
    val latestMono: Long,
)

/**
 * The tags a session actually recorded, most-used first.
 *
 * Built from the session rather than from [SignalTags], and that is the whole point: the tag set
 * is **open**. An app emitting `featureflags` or `bluetooth` gets a chip exactly as `cache` does,
 * because a list of conventional tags would be a list of the tags Inspector happens to know about
 * — which is not a fact about the app being debugged.
 *
 * Ordered by count and not by recency, matching [endpointShortcuts]: these chips sit in a strip
 * somebody aims at while signals keep arriving, and recency reshuffles the row under their thumb.
 * Ties break on the most recent, then alphabetically.
 *
 * A tag that cannot be written as a filter term is skipped, for the same reason an endpoint
 * segment is: the tokenizer splits on whitespace and `|` and toggles on `"`, so a chip carrying
 * one would filter to something other than its label says.
 */
fun signalTagShortcuts(signals: List<Signal>, limit: Int = Int.MAX_VALUE): List<TagShortcut> {
    if (limit <= 0) return emptyList()
    val byTag = LinkedHashMap<String, TagShortcut>()
    for (signal in signals) {
        val tag = signal.tag
        if (tag.isEmpty() || tag.any { it.isWhitespace() || it == '|' || it == '"' }) continue
        val existing = byTag[tag]
        byTag[tag] = existing?.copy(
            count = existing.count + 1,
            latestMono = maxOf(existing.latestMono, signal.mono),
        ) ?: TagShortcut(tag, count = 1, latestMono = signal.mono)
    }
    return byTag.values
        .sortedWith(
            compareByDescending<TagShortcut> { it.count }
                .thenByDescending { it.latestMono }
                .thenBy { it.tag },
        )
        .take(limit)
}

/**
 * The filter term a chip for [tag] applies.
 *
 * `tag:` is a [SignalTerm], so applying one **hides every transaction** — that is the exclusion
 * rule working, not a bug, and it is what makes a tag chip a per-tag browser rather than a
 * highlight. A caller that wants the traffic back alongside it writes the `|` form by hand.
 */
fun tagFilterTerm(tag: String): String = "tag:$tag"

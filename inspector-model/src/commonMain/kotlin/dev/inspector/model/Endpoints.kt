package dev.inspector.model

/** One endpoint worth a one-tap filter, and how much of the session it accounts for. */
data class EndpointShortcut(
    /** The last path segment, e.g. `holdings`. Never blank. */
    val segment: String,
    /** How many transactions end in this segment. */
    val count: Int,
    /** The most recent one's `mono`, used only to break ties deterministically. */
    val latestMono: Long,
)

/**
 * The endpoints a session actually spends its traffic on, most-used first.
 *
 * **Ordered by count, not by recency.** Recency reshuffles the whole row on every request during
 * live tail, which makes a chip impossible to aim at on a phone; and the endpoints worth one tap
 * are the ones dominating the list, not the one that happened last. Ties break on the most recent
 * call and then alphabetically, so the order is fully determined and a test can assert it rather
 * than assert a set.
 *
 * Segments containing whitespace, `|` or `"` are skipped: the filter tokenizer splits on the first
 * two and toggles on the third, so such a segment cannot be written as a term at all. A chip that
 * filtered to the wrong thing would be worse than an endpoint with no chip.
 *
 * `app.js` carries a mirror of this; keep them in step.
 */
fun endpointShortcuts(
    transactions: List<NetworkTransaction>,
    limit: Int,
): List<EndpointShortcut> {
    if (limit <= 0) return emptyList()
    val bySegment = LinkedHashMap<String, EndpointShortcut>()
    for (txn in transactions) {
        val segment = lastPathSegment(txn.path) ?: continue
        if (segment.any { it.isWhitespace() || it == '|' || it == '"' }) continue
        val existing = bySegment[segment]
        bySegment[segment] = if (existing == null) {
            EndpointShortcut(segment, count = 1, latestMono = txn.mono)
        } else {
            existing.copy(
                count = existing.count + 1,
                latestMono = maxOf(existing.latestMono, txn.mono),
            )
        }
    }
    return bySegment.values
        .sortedWith(
            compareByDescending<EndpointShortcut> { it.count }
                .thenByDescending { it.latestMono }
                .thenBy { it.segment },
        )
        .take(limit)
}

/**
 * The filter term a chip for [segment] applies.
 *
 * The leading star is the whole point. A bare `path:holdings` term is a **substring** match, so it
 * would also match `/v3/holdings/summary` — a chip that does not mean what its label says. The
 * star makes it a glob, and `globMatches` anchors the trailing literal with `endsWith`, so this
 * matches only paths that end in `/holdings`.
 *
 * Written with line comments in the source above rather than a KDoc example, because the term
 * contains the sequence that closes a block comment and Kotlin block comments nest.
 */
fun endpointFilterTerm(segment: String): String = "path:*/$segment"

/** Last non-empty path segment, or null for a path that has none. */
private fun lastPathSegment(path: String): String? =
    path.split('/').lastOrNull { it.isNotEmpty() }

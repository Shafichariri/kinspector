package dev.inspector.model

/*
 * Shared by the overlay and the web UI, like `timeline` and `endpointShortcuts` beside it.
 *
 * It began as `Formatting.kt` in `:inspector-ui`, where nothing else needed it. The web UI grew
 * the same bar and the choice was to copy three thresholds and a segment-walk into `app.js` from
 * scratch, or to have one rule that `app.js` mirrors deliberately. The rules here are exactly the
 * kind that drift invisibly: a bar that appears on one surface and not the other, for a session
 * both are showing, reads as a bug in whichever one you are looking at.
 */

/**
 * A host and leading path segments that most of a session shares, lifted out of the rows.
 *
 * @param prefix always both-slashed — `/v3/some-service/` — so [strip] leaves no leading slash and
 *   the remainder reads as a name rather than as a path fragment.
 */
data class PathScope(
    val host: String,
    val prefix: String,
    /** How many transactions the scope covers, for the "n of m" it shows. */
    val covered: Int,
) {
    val label: String get() = host + prefix

    fun covers(txn: NetworkTransaction): Boolean = txn.host == host && txn.path.startsWith(prefix)

    fun strip(path: String): String = path.removePrefix(prefix)
}

/** Fewer rows than this and there is not enough repetition for a shared prefix to mean anything. */
private const val SCOPE_MIN_ROWS = 4

/** A prefix shorter than this spends a bar of chrome to save a few characters. */
private const val SCOPE_MIN_LENGTH = 6

/**
 * The prefix worth lifting out of the list, or null when lifting one would not pay for itself.
 *
 * One app's traffic is mostly one host under one API version, so the front of every path is the
 * same and every row spends its width restating it. On a phone that width is the whole problem:
 * the path column is around 22 characters, and a shared `/v3/some-service/` eats most of them
 * before the part that tells one row from another gets a chance.
 *
 * Three rules keep it honest:
 *
 * **The dominant host only.** A session that talks to an API and an auth server has no single
 * prefix. Taking the busier one and letting the rest show their full path beats finding nothing,
 * and the rows left out name their host so the bar above them is never read as covering them.
 *
 * **Never the last segment.** `/v3/orders/submit` and `/v3/orders/cancel` share `/v3/orders/`, and
 * that is as far as this may go — a prefix that swallowed a whole path would leave a blank row.
 *
 * **Thresholds, so it either earns its place or stays out of the way.** A four-row session, or a
 * two-character saving, is not worth a bar.
 *
 * Callers pass *every* transaction, never the filtered view — the same rule as `duplicateIds`, for
 * the same reason: a prefix that changed as you typed would make each row mean something different
 * mid-search.
 */
fun pathScope(transactions: List<NetworkTransaction>): PathScope? {
    if (transactions.size < SCOPE_MIN_ROWS) return null

    val host = transactions.groupingBy { it.host }.eachCount().maxByOrNull { it.value }?.key ?: return null
    val paths = transactions.filter { it.host == host }.map { it.path }
    if (paths.size < SCOPE_MIN_ROWS) return null

    val segmented = paths.map { path -> path.split('/').filter { it.isNotEmpty() } }
    val shared = mutableListOf<String>()
    var index = 0
    while (true) {
        val segment = segmented.first().getOrNull(index) ?: break
        // `size <= index + 1` is the never-the-last-segment rule: this path has nothing left over.
        if (segmented.any { it.size <= index + 1 || it[index] != segment }) break
        shared += segment
        index++
    }
    if (shared.isEmpty()) return null

    val prefix = shared.joinToString(separator = "/", prefix = "/", postfix = "/")
    if (prefix.length < SCOPE_MIN_LENGTH) return null

    return PathScope(host = host, prefix = prefix, covered = paths.size)
}

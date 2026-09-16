package dev.inspector.ui

import dev.inspector.model.NetworkTransaction

/** `143ms`, `1.2s`, `—` while in flight. */
internal fun formatDuration(ms: Long?): String = when {
    ms == null -> "—"
    ms < 1000 -> "${ms}ms"
    ms < 10_000 -> "${(ms / 100) / 10.0}s"
    else -> "${ms / 1000}s"
}

/** `2.8 KB`, `1.4 MB`. Kept short because it sits in a dense list row. */
internal fun formatBytes(bytes: Long): String = when {
    bytes <= 0 -> "—"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${(bytes * 10 / 1024) / 10.0} KB"
    else -> "${(bytes * 10 / (1024 * 1024)) / 10.0} MB"
}

/**
 * Trailing part of the path, so the pill stays narrow.
 *
 * Keeps the last two segments where they exist: `/me` alone is ambiguous across a dozen
 * endpoints, while `/users/me` usually is not.
 */
internal fun pathTail(path: String): String {
    val segments = path.trim('/').split('/').filter { it.isNotEmpty() }
    return when {
        segments.isEmpty() -> "/"
        segments.size == 1 -> "/${segments.last()}"
        else -> "/${segments[segments.size - 2]}/${segments.last()}"
    }
}

/** Status column text; transport failures have no code to show. */
internal fun statusLabel(txn: NetworkTransaction): String = txn.status?.toString() ?: "ERR"

/**
 * A host and leading path segments that most of a session shares, lifted out of the rows.
 *
 * @param prefix always both-slashed — `/v3/some-service/` — so [strip] leaves no leading slash and
 *   the remainder reads as a name rather than as a path fragment.
 */
internal data class PathScope(
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
internal fun pathScope(transactions: List<NetworkTransaction>): PathScope? {
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

/**
 * Rebuilds the call as a cURL command.
 *
 * Redacted headers are emitted with their placeholder rather than silently dropped — a pasted
 * command that quietly lacks its auth header wastes more time than one that visibly says so.
 */
internal fun toCurl(txn: NetworkTransaction, requestBody: ByteArray?): String = buildString {
    append("curl -X ").append(txn.method)
    append(" '").append(txn.url).append("'")
    for ((name, values) in txn.reqHeaders) {
        for (value in values) {
            append(" \\\n  -H '").append(name).append(": ").append(value.replace("'", "'\\''")).append("'")
        }
    }
    requestBody?.decodeToString()?.takeIf { it.isNotEmpty() }?.let { body ->
        append(" \\\n  -d '").append(body.replace("'", "'\\''")).append("'")
    }
}

/**
 * Minimal JSON pretty-printer.
 *
 * Hand-rolled rather than parse-and-re-serialize so it also formats bodies that are truncated or
 * malformed — exactly the bodies you most want to look at.
 */
internal fun prettyJson(raw: String, indent: String = "  "): String {
    val out = StringBuilder(raw.length + raw.length / 4)
    var depth = 0
    var inString = false
    var escaped = false

    for (char in raw) {
        if (inString) {
            out.append(char)
            when {
                escaped -> escaped = false
                char == '\\' -> escaped = true
                char == '"' -> inString = false
            }
            continue
        }
        when (char) {
            '"' -> { inString = true; out.append(char) }
            '{', '[' -> {
                out.append(char)
                depth++
                out.append('\n').append(indent.repeat(depth))
            }
            '}', ']' -> {
                depth--
                out.append('\n').append(indent.repeat(maxOf(depth, 0))).append(char)
            }
            ',' -> out.append(char).append('\n').append(indent.repeat(depth))
            ':' -> out.append(": ")
            ' ', '\n', '\t', '\r' -> Unit
            else -> out.append(char)
        }
    }
    return out.toString()
}

internal fun looksLikeJson(contentType: String?, body: String?): Boolean {
    if (contentType?.contains("json", ignoreCase = true) == true) return true
    val trimmed = body?.trimStart() ?: return false
    return trimmed.startsWith("{") || trimmed.startsWith("[")
}

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

/*
 * `pathScope` used to live here. It moved to `:inspector-model` when the web UI grew a scope bar
 * of its own, for the same reason `timeline` and `endpointShortcuts` did: the thresholds and the
 * never-the-last-segment rule are one rule, and two copies of a rule are two rules that have not
 * disagreed yet.
 */

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

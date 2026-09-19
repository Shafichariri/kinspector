package dev.inspector.ui

import dev.inspector.model.InspectorJsonPretty
import dev.inspector.model.NetworkTransaction
import dev.inspector.model.Signal
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

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

/**
 * A signal's payload as text worth reading, or null when there is none to read.
 *
 * The truncated case is the one that needs the care, and it is not rare — it is what a large cache
 * snapshot produces. `Recorder.emit` cuts the *encoded* payload at `maxPayloadBytes` and keeps the
 * prefix as a `JsonPrimitive` string, because a cut JSON document is not JSON and the archive
 * holds one payload type rather than two. Pretty-encoding that element would therefore render the
 * whole snapshot as a single quoted string with every `"` escaped — the least readable form of
 * exactly the payload someone opened the row to read.
 *
 * So a truncated payload is unwrapped to its content and run through [prettyJson], which formats
 * malformed input rather than giving up on it. That is the same reason the body viewer uses it:
 * the bodies worth reading are disproportionately the broken ones.
 *
 * An untruncated payload is encoded from the element, so `{"a":1}` arrives indented rather than as
 * the app happened to spell it.
 */
internal fun formatSignalPayload(signal: Signal): String? {
    val data = signal.data ?: return null
    if (signal.dataTruncated) {
        val prefix = (data as? JsonPrimitive)?.takeIf { it.isString }?.content
        // Falls through to the ordinary path when the flag and the shape disagree, rather than
        // rendering nothing: the flag describes what capture did, and a reader is owed whatever
        // is actually there.
        if (prefix != null) return prettyJson(prefix)
    }
    if (data is JsonPrimitive && data.isString) {
        // The `signal(tag, name, text)` overload wraps a `toString()` dump as a JSON string. It is
        // not JSON and must not be re-quoted — the app wrote a line of text and that is what to
        // show.
        return data.content
    }
    return InspectorJsonPretty.encodeToString(JsonElement.serializer(), data)
}

/**
 * How long ago something happened, in the coarsest unit that is still true.
 *
 * Mirrors the web UI's `fmtAge` so one session does not describe the same observation two ways.
 * Coarse on purpose past a minute: nobody reads "127s ago", and the question this answers is
 * "is this stale", which a rounded minute answers as well as a precise one.
 *
 * Negative input is clamped rather than rendered as the future. That is not defensive
 * programming — `ts` is the device's own wall clock and the overlay reads it from the same
 * process, so the two disagree exactly when the clock moved between the observation and now.
 */
internal fun formatAge(ms: Long): String {
    val age = ms.coerceAtLeast(0)
    return when {
        age < 2_000 -> "just now"
        age < 60_000 -> "${age / 1_000}s ago"
        age < 3_600_000 -> "${age / 60_000}m ago"
        age < 86_400_000 -> "${age / 3_600_000}h ago"
        else -> "${age / 86_400_000}d ago"
    }
}

/**
 * Milliseconds between an ISO-8601 instant and [nowMs], or null when it cannot be read.
 *
 * **Wall clock, not `mono`, and that is a compromise rather than a preference.** The overlay runs
 * in the same process that recorded the row, so in principle `mono` — which cannot jump — is the
 * better clock for an age. It is unusable here: `mono` is measured from an origin private to
 * `:inspector-core`, and a `TimeSource.Monotonic.markNow()` taken in this module would be a
 * different origin and produce ages that are wrong by however long the process had been running.
 * Exposing the origin would be a public API change to carry an age column, which is not a trade
 * worth making — see `docs/ROADMAP.md`.
 *
 * So this is what the web UI does, for a different reason: it must use wall clock because it is a
 * different machine, and this uses it because the better clock is out of reach. Both are wrong in
 * the same single case — a clock change mid-session — and both report it rather than hide it.
 */
@OptIn(ExperimentalTime::class)
internal fun ageMsOf(ts: String, nowMs: Long): Long? {
    val at = runCatching { Instant.parse(ts).toEpochMilliseconds() }.getOrNull() ?: return null
    return nowMs - at
}

/** Wall-clock milliseconds now, from the same source that stamped `ts`. */
@OptIn(ExperimentalTime::class)
internal fun nowEpochMs(): Long = Clock.System.now().toEpochMilliseconds()

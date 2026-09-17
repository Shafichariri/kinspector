package dev.inspector.ui

/**
 * The device's current offset from UTC, in seconds.
 *
 * Needed because `ts` is ISO-8601 **UTC** and the point of showing a wall clock at all is lining a
 * row up against something outside the app — logcat, a backend log, a stopwatch — all of which are
 * in the reader's own zone. A UTC column would be correct and useless.
 *
 * `expect`/`actual` rather than a datetime library: `kotlin.time` has no zone support, and
 * `kotlinx-datetime` would be a transitive dependency pushed onto every consumer of this module to
 * render eight characters.
 *
 * **Current offset, not the offset at the row's own instant.** Getting the latter right means
 * converting the timestamp to an epoch first, which is the whole civil-date calculation this is
 * avoiding. The cost is that rows captured before a DST transition would read an hour out — in a
 * ring buffer holding one debugging session, which is not a case worth the code.
 */
internal expect fun localUtcOffsetSeconds(): Int

/**
 * `14:03:22` in the device's own zone, from an ISO-8601 UTC timestamp.
 *
 * Seconds and no milliseconds: the web row shows `.mmm` because it has the width, and here the
 * column competes with the path — which is the one thing that tells one row from another. Ordering
 * within a second is already given by the list, and sub-second *distance* is what the duration and
 * the repeat span say in a form that can actually be read.
 *
 * Parsed by position rather than by a date library because the producer is known: `nowIso()` is
 * `kotlin.time.Clock.System.now().toString()`, which is always `…THH:MM:SS[.sss]Z`. Anything that
 * does not look like that renders as `--:--:--`, which holds the column and does not invent a time.
 *
 * @param offsetSeconds defaulted rather than read inside, so the conversion can be tested at a
 *   fixed offset instead of at whatever this machine happens to be set to.
 */
internal fun formatClock(ts: String, offsetSeconds: Int = localUtcOffsetSeconds()): String {
    val t = ts.indexOf('T')
    if (t < 0 || ts.length < t + 9) return NO_CLOCK
    val hours = ts.substring(t + 1, t + 3).toIntOrNull() ?: return NO_CLOCK
    if (ts[t + 3] != ':' || ts[t + 6] != ':') return NO_CLOCK
    val minutes = ts.substring(t + 4, t + 6).toIntOrNull() ?: return NO_CLOCK
    val seconds = ts.substring(t + 7, t + 9).toIntOrNull() ?: return NO_CLOCK
    // Seconds stop at 59. A leap second is not an arcane case this has to honour: the producer is
    // `kotlin.time.Clock.System.now()`, which is Unix time and has no 60th second to emit. Allowing
    // one only meant `23:59:60` wrapping silently to `00:00:00`, which is a wrong time rather than
    // an admitted one.
    if (hours !in 0..23 || minutes !in 0..59 || seconds !in 0..59) return NO_CLOCK

    // `mod`, not `%`: a negative offset west of Greenwich must wrap to the previous day rather
    // than produce a negative hour. Only the time of day is shown, so the date wrapping with it
    // is not visible and does not need saying.
    val ofDay = (hours * 3600 + minutes * 60 + seconds + offsetSeconds).mod(SECONDS_PER_DAY)
    return buildString(8) {
        pad2(ofDay / 3600)
        append(':')
        pad2((ofDay % 3600) / 60)
        append(':')
        pad2(ofDay % 60)
    }
}

private fun StringBuilder.pad2(value: Int) {
    if (value < 10) append('0')
    append(value)
}

/** Holds the column when a timestamp cannot be read, rather than inventing a time for the row. */
internal const val NO_CLOCK = "--:--:--"

private const val SECONDS_PER_DAY = 24 * 60 * 60

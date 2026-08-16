package dev.inspector.internal

import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.TimeSource

/**
 * Monotonic milliseconds since process start.
 *
 * `TimeSource.Monotonic` is multiplatform stdlib, so this needs no expect/actual. Transactions
 * are ordered by this rather than by wall clock: the device and host clocks are unrelated, and
 * wall clock can jump backwards mid-session.
 */
private val monoOrigin = TimeSource.Monotonic.markNow()

internal fun monoMs(): Long = monoOrigin.elapsedNow().inWholeMilliseconds

/** ISO-8601 UTC with millis. Display only — never used for ordering. */
@OptIn(ExperimentalTime::class)
internal fun nowIso(): String = Clock.System.now().toString()

private const val HEX = "0123456789abcdef"

/** 8-char lowercase hex, matching the id format the schema specifies. */
internal fun newId(): String = buildString(8) {
    repeat(8) { append(HEX[Random.nextInt(16)]) }
}

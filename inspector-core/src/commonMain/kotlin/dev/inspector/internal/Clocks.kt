package dev.inspector.internal

import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
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

@OptIn(ExperimentalAtomicApi::class)
private val seqCounter = AtomicLong(0L)

/**
 * The next capture sequence number: the order calls, signals and markers were captured in.
 *
 * Taken immediately after [monoMs] at every capture site, so it breaks the ties a millisecond clock
 * leaves — a tap and the navigation it triggers routinely share one. Across threads two captures
 * in one millisecond are genuinely unordered, and this then reports whichever reached the counter
 * first; on one thread it is program order, which is the case that matters.
 */
@OptIn(ExperimentalAtomicApi::class)
internal fun nextSeq(): Long = seqCounter.incrementAndFetch()

/** ISO-8601 UTC with millis. Display only — never used for ordering. */
@OptIn(ExperimentalTime::class)
internal fun nowIso(): String = Clock.System.now().toString()

private const val HEX = "0123456789abcdef"

/** 8-char lowercase hex, matching the id format the schema specifies. */
internal fun newId(): String = buildString(8) {
    repeat(8) { append(HEX[Random.nextInt(16)]) }
}

package dev.inspector.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/** The absolute moment under a call's clock: the date, to the millisecond, and the zone said aloud. */
class UtcCaptionTest {

    @Test
    fun `a capture timestamp reads as a date and a UTC time`() {
        assertEquals("22 Sep 2026 · 09:41:07.412 UTC", utcCaption("2026-09-22T09:41:07.412Z"))
    }

    @Test
    fun `fractions are shown to the millisecond whatever precision was recorded`() {
        assertEquals("1 Jan 2026 · 00:00:00.000 UTC", utcCaption("2026-01-01T00:00:00Z"))
        assertEquals("1 Jan 2026 · 00:00:00.123 UTC", utcCaption("2026-01-01T00:00:00.123456789Z"))
        assertEquals("1 Jan 2026 · 00:00:00.100 UTC", utcCaption("2026-01-01T00:00:00.1Z"))
    }

    @Test
    fun `anything not in the shape capture writes is shown as recorded`() {
        // A guess at a malformed or offset timestamp would be a confident wrong answer.
        assertEquals("nonsense", utcCaption("nonsense"))
        assertEquals("2026-09-22T09:41:07+04:00", utcCaption("2026-09-22T09:41:07+04:00"))
        assertEquals("2026-13-22T09:41:07Z", utcCaption("2026-13-22T09:41:07Z"))
    }
}

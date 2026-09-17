package dev.inspector.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The wall-clock column.
 *
 * Every case passes an explicit offset. Reading the machine's own zone would make these pass in
 * one timezone and fail in another, which is the kind of test that gets deleted rather than fixed.
 */
class LocalClockTest {

    private val hour = 3_600

    @Test
    fun `a UTC timestamp is shown in the device's zone`() {
        assertEquals("13:00:02", formatClock("2026-09-15T10:00:02.000Z", offsetSeconds = 3 * hour))
    }

    @Test
    fun `an offset of zero is the timestamp itself`() {
        assertEquals("10:00:02", formatClock("2026-09-15T10:00:02.000Z", offsetSeconds = 0))
    }

    @Test
    fun `a timestamp without milliseconds reads the same`() {
        // `kotlin.time.Clock.System.now().toString()` drops the fraction when it is zero, so both
        // forms genuinely occur in one archive.
        assertEquals("13:00:02", formatClock("2026-09-15T10:00:02Z", offsetSeconds = 3 * hour))
    }

    @Test
    fun `nanosecond precision is read the same way`() {
        assertEquals("13:00:02", formatClock("2026-09-15T10:00:02.123456789Z", 3 * hour))
    }

    @Test
    fun `a negative offset wraps back to the previous day`() {
        // The case `%` would get wrong: 00:00:30 minus an hour is 23:00:30, not -1:00:30. Only the
        // time of day is shown, so the date going back with it is invisible and does not matter.
        assertEquals("23:00:30", formatClock("2026-09-15T00:00:30Z", offsetSeconds = -hour))
    }

    @Test
    fun `a positive offset wraps forward past midnight`() {
        assertEquals("02:30:00", formatClock("2026-09-15T23:30:00Z", offsetSeconds = 3 * hour))
    }

    @Test
    fun `offsets that are not whole hours work`() {
        // India is +05:30 and Nepal +05:45. A formatter that only handled whole hours would be
        // wrong for most of South Asia and right everywhere it was tested.
        assertEquals("15:30:02", formatClock("2026-09-15T10:00:02Z", offsetSeconds = 5 * hour + 1800))
        assertEquals("15:45:02", formatClock("2026-09-15T10:00:02Z", offsetSeconds = 5 * hour + 2700))
    }

    @Test
    fun `a sixtieth second is not a time`() {
        // Unix time has no leap second, so `nowIso()` cannot produce this — it is malformed input.
        // Worth pinning because the arithmetic would otherwise wrap it to 00:00:00, which is a
        // wrong time shown confidently rather than a gap admitted.
        assertEquals(NO_CLOCK, formatClock("2016-12-31T23:59:60Z", offsetSeconds = 0))
    }

    @Test
    fun `a timestamp that cannot be read holds the column`() {
        // Never invent a time for a row. The width is kept so the columns below stay in line.
        for (bad in listOf("", "not a timestamp", "2026-09-15", "2026-09-15T", "2026-09-15T10:0",
                "2026-09-15Txx:00:02Z", "2026-09-15T10-00-02Z")) {
            assertEquals(NO_CLOCK, formatClock(bad, offsetSeconds = 0), "for '$bad'")
        }
    }

    @Test
    fun `an out of range field is not shown as a time`() {
        assertEquals(NO_CLOCK, formatClock("2026-09-15T99:00:02Z", offsetSeconds = 0))
        assertEquals(NO_CLOCK, formatClock("2026-09-15T10:99:02Z", offsetSeconds = 0))
    }

    @Test
    fun `every field is zero padded`() {
        // A ragged column is the reason this is monospace and fixed in the first place.
        assertEquals("01:02:03", formatClock("2026-09-15T01:02:03Z", offsetSeconds = 0))
        assertEquals("00:00:00", formatClock("2026-09-15T00:00:00Z", offsetSeconds = 0))
    }

    @Test
    fun `the device's own offset is a real one`() {
        // The one assertion that touches the platform. It cannot check a value — the machine
        // running this could be anywhere — but it can check the actual exists and is answering
        // with something a timezone could be, rather than a stub returning zero forever.
        val offset = localUtcOffsetSeconds()
        assertEquals(true, offset in -12 * hour..14 * hour, "implausible offset: $offset")
    }
}

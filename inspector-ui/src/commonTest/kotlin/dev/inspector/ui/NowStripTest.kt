package dev.inspector.ui

import dev.inspector.model.Signal
import dev.inspector.model.SignalTrigger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What "now" is, and how stale it is allowed to look.
 *
 * The rules here are the ones that decide whether the word "now" in the header is a claim or a
 * label — which observation is chosen per key, in what order, and what the summary admits.
 */
class NowStripTest {

    private fun obs(
        tag: String,
        name: String,
        mono: Long,
        ts: String = "2026-09-19T10:00:00.000Z",
        trigger: SignalTrigger = SignalTrigger.App,
    ) = Signal(id = "s$tag$name$mono", ts = ts, mono = mono, tag = tag, name = name, trigger = trigger)

    /** 2026-09-19T10:00:00Z. Computed, not typed from memory — the first attempt was a year out. */
    private val now = 1_789_812_000_000L

    @Test
    fun the_latest_observation_of_each_key_is_the_current_one() {
        val current = currentObservations(
            listOf(
                obs("state", "Cart", 100),
                obs("state", "Cart", 900),
                obs("cache", "profile", 500),
            )
        )
        assertEquals(2, current.size)
        // Last wins per key — the same rule the daemon's `current` query uses.
        assertEquals(900L, current.single { it.name == "Cart" }.mono)
    }

    @Test
    fun the_order_is_stable_rather_than_by_recency() {
        val current = currentObservations(
            listOf(obs("state", "Zebra", 900), obs("cache", "apple", 100), obs("state", "Alpha", 500))
        )
        // Alphabetical by tag then name. `signalGroups` orders by recency, which is right for a
        // history somebody is reading for what changed and wrong for a panel they glance at: a row
        // that moves whenever the app mentions something else is one you have to find again.
        assertEquals(
            listOf("cache" to "apple", "state" to "Alpha", "state" to "Zebra"),
            current.map { it.tag to it.name },
        )
    }

    @Test
    fun the_summary_counts_by_tag_and_names_the_stalest() {
        val summary = nowSummary(
            currentObservations(
                listOf(
                    obs("cache", "a", 100, ts = "2026-09-19T09:56:00.000Z"),
                    obs("cache", "b", 200, ts = "2026-09-19T09:59:58.000Z"),
                    obs("state", "c", 300, ts = "2026-09-19T10:00:00.000Z"),
                )
            ),
            now,
        )
        // The staleness is the half worth the characters: a count says the panel has something in
        // it, and the age says whether opening it is worth doing.
        assertEquals("2 cache · 1 state — oldest 4m ago", summary)
    }

    @Test
    fun an_unreadable_timestamp_is_skipped_rather_than_counted_as_fresh() {
        val summary = nowSummary(
            listOf(obs("cache", "a", 100, ts = "not a timestamp"), obs("cache", "b", 200, ts = "2026-09-19T09:55:00.000Z")),
            now,
        )
        // Counting it as zero would report the unreadable row as the freshest thing on screen,
        // which is this panel's one job done backwards.
        assertEquals("2 cache — oldest 5m ago", summary)
        assertNull(ageMsOf("not a timestamp", now))
    }

    @Test
    fun a_summary_with_nothing_readable_still_counts_what_is_there() {
        assertEquals("1 cache", nowSummary(listOf(obs("cache", "a", 100, ts = "nonsense")), now))
    }

    @Test
    fun an_empty_session_has_no_summary() {
        assertTrue(currentObservations(emptyList()).isEmpty())
        assertEquals("", nowSummary(emptyList(), now))
    }

    @Test
    fun ages_are_coarse_past_a_minute_and_never_in_the_future() {
        assertEquals("just now", formatAge(0))
        assertEquals("just now", formatAge(1_999))
        assertEquals("2s ago", formatAge(2_000))
        assertEquals("59s ago", formatAge(59_999))
        assertEquals("1m ago", formatAge(60_000))
        assertEquals("59m ago", formatAge(3_599_999))
        assertEquals("1h ago", formatAge(3_600_000))
        assertEquals("2d ago", formatAge(2 * 86_400_000L))
        // Clamped rather than rendered as the future. `ts` is the device's own wall clock read
        // from this same process, so the two disagree exactly when the clock moved.
        assertEquals("just now", formatAge(-5_000))
    }

    @Test
    fun an_age_is_measured_from_the_timestamp_the_row_carries() {
        assertEquals(240_000L, ageMsOf("2026-09-19T09:56:00.000Z", now))
        assertEquals(0L, ageMsOf("2026-09-19T10:00:00.000Z", now))
    }
}

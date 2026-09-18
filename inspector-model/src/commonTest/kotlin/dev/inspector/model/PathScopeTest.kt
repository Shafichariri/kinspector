package dev.inspector.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rules in [pathScope], each pinned by the case that would break it.
 *
 * The bar this feeds is a standing claim that every path below it is missing the same front. A
 * wrong prefix is worse than no prefix: rows would read as endpoints nobody called.
 */
class PathScopeTest {

    private var next = 0

    /**
     * Named `row` rather than `txn` because this package already has a top-level `txn`
     * fixture: a same-named member would shadow it inside this class only, which is exactly
     * the sort of thing that reads as the shared fixture until someone changes the shared one.
     */
    private fun row(path: String, host: String = "api.example.com"): NetworkTransaction {
        val id = (next++).toString(16).padStart(8, '0')
        return NetworkTransaction(
            id = id,
            ts = "2026-09-15T10:00:00.000Z",
            mono = next.toLong(),
            method = "GET",
            scheme = "https",
            host = host,
            path = path,
            callId = id,
        )
    }

    @Test
    fun lifts_the_segments_every_path_shares() {
        val scope = pathScope(
            listOf(
                row("/v3/some-service/client-dashboard"),
                row("/v3/some-service/client-settings"),
                row("/v3/some-service/orders/submit"),
                row("/v3/some-service/portfolio/holdings"),
            )
        )

        assertEquals("/v3/some-service/", scope?.prefix)
        assertEquals("api.example.com", scope?.host)
        assertEquals(4, scope?.covered)
        assertEquals("api.example.com/v3/some-service/", scope?.label)
    }

    @Test
    fun strip_leaves_the_part_that_differs() {
        val scope = pathScope(
            listOf(
                row("/v3/some-service/client-dashboard"),
                row("/v3/some-service/client-settings"),
                row("/v3/some-service/orders/submit"),
                row("/v3/some-service/market/quotes"),
            )
        )!!

        assertEquals("client-dashboard", scope.strip("/v3/some-service/client-dashboard"))
        assertEquals("orders/submit", scope.strip("/v3/some-service/orders/submit"))
    }

    @Test
    fun never_takes_the_last_segment_of_a_path() {
        // Every path is directly under /v3/orders/, so a prefix one segment longer would consume
        // a whole path and leave that row with nothing to show.
        val scope = pathScope(
            listOf(
                row("/v3/orders/submit"),
                row("/v3/orders/cancel"),
                row("/v3/orders/amend"),
                row("/v3/orders/status"),
            )
        )

        assertEquals("/v3/orders/", scope?.prefix)
        assertTrue(scope!!.strip("/v3/orders/submit").isNotEmpty())
    }

    @Test
    fun a_session_that_polled_one_endpoint_still_leaves_that_endpoint_on_the_row() {
        // Every path identical — a poll, which is an ordinary way for a session to look. Taking
        // the whole path as the prefix would leave every row blank, and the bar covering nothing.
        val scope = pathScope(
            listOf(
                row("/v3/orders/status"),
                row("/v3/orders/status"),
                row("/v3/orders/status"),
                row("/v3/orders/status"),
            )
        )!!

        assertEquals("/v3/orders/", scope.prefix)
        assertEquals("status", scope.strip("/v3/orders/status"))
        assertTrue(scope.covers(row("/v3/orders/status")))
    }

    @Test
    fun a_path_that_is_the_front_of_the_others_stops_the_prefix_short() {
        // `/v3/orders` has nothing after the segments the rest share, so the prefix has to stop
        // short of it — otherwise the collection endpoint is the one row the bar does not cover.
        val collection = row("/v3/orders")
        val scope = pathScope(
            listOf(
                collection,
                row("/v3/orders/submit"),
                row("/v3/orders/cancel"),
                row("/v3/orders/amend"),
            )
        )

        // Stopping short leaves only "/v3/", which is below the length threshold — so the honest
        // answer here is no bar at all, rather than one that misses a row.
        assertNull(scope)
    }

    @Test
    fun stops_at_the_first_segment_that_differs() {
        val scope = pathScope(
            listOf(
                row("/v3/some-service/a/one"),
                row("/v3/some-service/a/two"),
                row("/v3/some-service/b/three"),
                row("/v3/some-service/b/four"),
            )
        )

        assertEquals("/v3/some-service/", scope?.prefix)
    }

    @Test
    fun no_shared_front_means_no_scope() {
        assertNull(
            pathScope(
                listOf(
                    row("/alpha/one"),
                    row("/beta/two"),
                    row("/gamma/three"),
                    row("/delta/four"),
                )
            )
        )
    }

    @Test
    fun a_short_session_is_left_alone() {
        // Three rows share a long prefix, and it still does not engage: a bar of chrome needs more
        // than a handful of rows to earn its place.
        assertNull(
            pathScope(
                listOf(
                    row("/v3/some-service/client-dashboard"),
                    row("/v3/some-service/client-settings"),
                    row("/v3/some-service/orders/submit"),
                )
            )
        )
    }

    @Test
    fun a_prefix_too_short_to_be_worth_a_bar_is_declined() {
        // Shared front is only "/v3/" — four characters, against a whole row of chrome.
        assertNull(
            pathScope(
                listOf(
                    row("/v3/alpha/one"),
                    row("/v3/beta/two"),
                    row("/v3/gamma/three"),
                    row("/v3/delta/four"),
                )
            )
        )
    }

    @Test
    fun the_busier_host_wins_and_the_other_is_not_covered() {
        val other = row("/v1/oauth/token", host = "auth.example.com")
        val scope = pathScope(
            listOf(
                row("/v3/some-service/client-dashboard"),
                row("/v3/some-service/client-settings"),
                row("/v3/some-service/orders/submit"),
                row("/v3/some-service/market/quotes"),
                other,
            )
        )!!

        assertEquals("api.example.com", scope.host)
        assertEquals("/v3/some-service/", scope.prefix)
        // covered counts the dominant host only, so "4 of 5" is what the bar can honestly claim.
        assertEquals(4, scope.covered)
        assertFalse(scope.covers(other))
    }

    @Test
    fun a_path_outside_the_prefix_on_the_same_host_is_not_covered() {
        val outside = row("/assets/config.json")
        val scope = pathScope(
            listOf(
                row("/v3/some-service/client-dashboard"),
                row("/v3/some-service/client-settings"),
                row("/v3/some-service/orders/submit"),
                row("/v3/some-service/market/quotes"),
                outside,
            )
        )

        // One path on the dominant host breaks the run, so there is no prefix every row shares.
        assertNull(scope)
    }

    @Test
    fun the_dominant_host_is_scoped_even_when_another_host_has_no_prefix() {
        val scope = pathScope(
            listOf(
                row("/v3/some-service/client-dashboard"),
                row("/v3/some-service/client-settings"),
                row("/v3/some-service/orders/submit"),
                row("/v3/some-service/market/quotes"),
                row("/one", host = "cdn.example.com"),
                row("/two", host = "cdn.example.com"),
            )
        )!!

        assertEquals("api.example.com", scope.host)
        assertTrue(scope.covers(row("/v3/some-service/anything")))
    }

    @Test
    fun an_empty_session_has_no_scope() {
        assertNull(pathScope(emptyList()))
    }
}

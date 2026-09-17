package dev.inspector.ui

import dev.inspector.model.FilterContext
import dev.inspector.model.FilterParser
import dev.inspector.model.NetworkTransaction
import dev.inspector.model.matches
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The preset chips.
 *
 * They are strings, so the only way they can rot is silently: a grammar change turns a chip into
 * one that shows a parse error when tapped, and nothing else notices. These parse every one, and
 * then check each actually selects what its label claims — a term that parses but matches the
 * wrong rows is the worse failure of the two.
 */
class QuickFiltersTest {

    private fun txn(
        id: String,
        status: Int? = 200,
        ms: Long? = 40,
        attempt: Int = 1,
        error: String? = null,
    ) = NetworkTransaction(
        id = id, ts = "2026-09-17T09:00:00Z", mono = 100, method = "GET", scheme = "https",
        host = "api.example.com", path = "/v1/$id", status = status, error = error, ms = ms,
        attempt = attempt, callId = id,
    )

    private fun keptBy(label: String, rows: List<NetworkTransaction>): List<String> {
        val term = QUICK_FILTERS.single { it.first == label }.second
        val filter = FilterParser.parse(term).getOrThrow()
        return rows.filter { filter.matches(it, FilterContext(emptyList())) }.map { it.id }
    }

    @Test
    fun `every preset is a term the parser accepts`() {
        for ((label, term) in QUICK_FILTERS) {
            assertTrue(FilterParser.parse(term).isSuccess, "chip '$label' has an invalid term")
        }
    }

    @Test
    fun `the labels are distinct`() {
        // Two chips reading the same would be indistinguishable on screen, and the lookup above
        // would throw rather than pick one.
        assertEquals(QUICK_FILTERS.size, QUICK_FILTERS.map { it.first }.distinct().size)
    }

    @Test
    fun `errors keeps transport failures`() {
        val rows = listOf(txn("ok"), txn("dead", status = null, error = "SocketTimeoutException"))
        assertEquals(listOf("dead"), keptBy("errors", rows))
    }

    @Test
    fun `5xx keeps server errors and not client ones`() {
        val rows = listOf(txn("ok"), txn("notfound", status = 404), txn("boom", status = 500))
        assertEquals(listOf("boom"), keptBy("5xx", rows))
    }

    @Test
    fun `slow keeps the calls worth looking at`() {
        val rows = listOf(txn("quick", ms = 40), txn("dragging", ms = 900))
        assertEquals(listOf("dragging"), keptBy("slow", rows))
    }

    @Test
    fun `retries keeps later attempts and not first ones`() {
        val rows = listOf(txn("once"), txn("again", attempt = 2))
        assertEquals(listOf("again"), keptBy("retries", rows))
    }
}

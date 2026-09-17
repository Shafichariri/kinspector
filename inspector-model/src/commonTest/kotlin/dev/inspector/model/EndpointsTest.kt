package dev.inspector.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Which endpoints get a one-tap chip, in what order, and what the chip actually filters to. */
class EndpointsTest {

    private var seq = 0

    private fun txn(path: String, mono: Long = (++seq) * 100L) = NetworkTransaction(
        id = "t${seq}", ts = "2026-09-17T09:00:00Z", mono = mono, method = "GET",
        scheme = "https", host = "api.example.com", path = path, status = 200, callId = "c${seq}",
    )

    private fun segments(shortcuts: List<EndpointShortcut>) = shortcuts.map { it.segment }

    @Test
    fun `the busiest endpoint comes first`() {
        val rows = listOf(
            txn("/v1/orders"), txn("/v1/holdings"), txn("/v1/holdings"), txn("/v1/holdings"),
            txn("/v1/orders"),
        )
        assertEquals(listOf("holdings", "orders"), segments(endpointShortcuts(rows, limit = 10)))
    }

    /**
     * The rule that keeps the row still.
     *
     * Ordering by recency would reshuffle every chip on every request during live tail, which on a
     * phone means a chip moves out from under a thumb already travelling towards it.
     */
    @Test
    fun `a newer endpoint does not jump ahead of a busier one`() {
        val rows = listOf(
            txn("/v1/holdings", mono = 100), txn("/v1/holdings", mono = 200),
            txn("/v1/quotes", mono = 9_000),
        )
        assertEquals(listOf("holdings", "quotes"), segments(endpointShortcuts(rows, limit = 10)))
    }

    @Test
    fun `a tie breaks on the most recent call`() {
        val rows = listOf(txn("/v1/alpha", mono = 100), txn("/v1/beta", mono = 900))
        assertEquals(listOf("beta", "alpha"), segments(endpointShortcuts(rows, limit = 10)))
    }

    @Test
    fun `a tie on both count and recency breaks alphabetically`() {
        // Fully determined rather than merely stable: a set assertion would pass for an order that
        // reshuffles between runs, and nobody would notice until a chip moved under a thumb.
        val rows = listOf(txn("/v1/beta", mono = 500), txn("/v1/alpha", mono = 500))
        assertEquals(listOf("alpha", "beta"), segments(endpointShortcuts(rows, limit = 10)))
    }

    @Test
    fun `the limit caps the list`() {
        val rows = listOf(txn("/a"), txn("/b"), txn("/c"), txn("/d"))
        assertEquals(3, endpointShortcuts(rows, limit = 3).size)
        assertEquals(emptyList(), endpointShortcuts(rows, limit = 0))
        assertEquals(emptyList(), endpointShortcuts(rows, limit = -1))
    }

    @Test
    fun `a segment the grammar cannot tokenize is skipped`() {
        // The tokenizer splits on whitespace and `|` and toggles on `"`. A chip built from such a
        // segment would filter to something other than its label.
        val rows = listOf(
            txn("/v1/two words"), txn("/v1/a|b"), txn("/v1/say\"what"), txn("/v1/fine"),
        )
        assertEquals(listOf("fine"), segments(endpointShortcuts(rows, limit = 10)))
    }

    @Test
    fun `a path with no segments has no chip`() {
        assertEquals(emptyList(), endpointShortcuts(listOf(txn("/"), txn("")), limit = 10))
    }

    @Test
    fun `the count and the latest mono are carried`() {
        val rows = listOf(txn("/v1/holdings", mono = 100), txn("/v1/holdings", mono = 700))
        val only = endpointShortcuts(rows, limit = 10).single()
        assertEquals(2, only.count)
        assertEquals(700, only.latestMono)
    }

    /**
     * The reason the term carries a star.
     *
     * A bare `path:holdings` is a substring match, so it would also claim `/v1/holdings/summary` —
     * a chip that does not mean what its label says. This is the assertion that would catch the
     * star being dropped as "redundant".
     */
    @Test
    fun `a chip matches only paths ending in its segment`() {
        val filter = FilterParser.parse(endpointFilterTerm("holdings")).getOrThrow()
        val ctx = FilterContext(emptyList())
        assertTrue(filter.matches(txn("/v3/portfolio/holdings"), ctx))
        assertTrue(!filter.matches(txn("/v3/holdings/summary"), ctx))
        assertTrue(!filter.matches(txn("/v3/portfolio/holdingsx"), ctx))
    }

    @Test
    fun `every chip a session produces is a term the parser accepts`() {
        val rows = listOf(txn("/v1/holdings"), txn("/v3/accounts/1299651"), txn("/a-b_c.d~e"))
        for (shortcut in endpointShortcuts(rows, limit = 10)) {
            assertTrue(
                FilterParser.parse(endpointFilterTerm(shortcut.segment)).isSuccess,
                "chip for '${shortcut.segment}' produced a term the parser rejected",
            )
        }
    }
}

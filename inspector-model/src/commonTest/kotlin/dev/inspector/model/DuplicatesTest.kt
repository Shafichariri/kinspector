package dev.inspector.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Duplicate-request detection.
 *
 * Modelled on the case that prompted it: `/v3/accounts/profile/status` fetched twice 1.9s apart,
 * byte-identical, by two different calls. Nothing failed, so nothing drew attention to it.
 */
class DuplicatesTest {

    private fun txn(
        id: String,
        mono: Long,
        callId: String = id,
        path: String = "/v1/profile/status",
        method: String = "GET",
        status: Int? = 200,
        resBytes: Long = 972,
        attempt: Int = 1,
    ) = NetworkTransaction(
        id = id, ts = "2026-08-18T14:00:00Z", mono = mono, method = method,
        scheme = "https", host = "api.example.com", path = path, status = status,
        callId = callId, resBytes = resBytes, attempt = attempt,
    )

    @Test
    fun `two separate calls for the same thing inside the window are a duplicate`() {
        val groups = duplicateGroups(listOf(txn("a", 1000), txn("b", 2900)))
        assertEquals(1, groups.size)
        assertEquals(listOf("a", "b"), groups.single().ids)
        assertEquals(1900, groups.single().spanMs)
        assertEquals(2, groups.single().callCount)
    }

    @Test
    fun `the same pair outside the window is not`() {
        assertEquals(emptyList(), duplicateGroups(listOf(txn("a", 1000), txn("b", 4500))))
    }

    @Test
    fun `the window is configurable`() {
        val pair = listOf(txn("a", 1000), txn("b", 4500))
        assertEquals(emptyList(), duplicateGroups(pair, windowMs = 3_000))
        assertEquals(1, duplicateGroups(pair, windowMs = 5_000).size)
    }

    /**
     * The rule that keeps this from being noise. Retries and redirect hops share a `callId`: they
     * are one logical call, already rendered as an attempt chain. Flagging them would both spam the
     * list and describe what happened wrongly.
     */
    @Test
    fun `retry attempts of one call are not duplicates`() {
        val retried = listOf(
            txn("a", 1000, callId = "call-1", attempt = 1, status = 500, resBytes = 20),
            txn("b", 1100, callId = "call-1", attempt = 2, status = 500, resBytes = 20),
            txn("c", 1200, callId = "call-1", attempt = 3, status = 500, resBytes = 20),
        )
        assertEquals(emptyList(), duplicateGroups(retried), "attempts of one call must not be flagged")
    }

    @Test
    fun `a retried call that is also duplicated still reports the duplication`() {
        val txns = listOf(
            txn("a", 1000, callId = "call-1", attempt = 1),
            txn("b", 1100, callId = "call-1", attempt = 2),
            txn("c", 1200, callId = "call-2"),
        )
        val group = duplicateGroups(txns).single()
        assertEquals(listOf("a", "b", "c"), group.ids)
        assertEquals(2, group.callCount, "two logical calls, three rows")
    }

    @Test
    fun `different paths are unrelated`() {
        val txns = listOf(txn("a", 1000, path = "/one"), txn("b", 1500, path = "/two"))
        assertEquals(emptyList(), duplicateGroups(txns))
    }

    @Test
    fun `a different response size is treated as a different answer`() {
        val txns = listOf(txn("a", 1000, resBytes = 972), txn("b", 1500, resBytes = 512))
        assertEquals(emptyList(), duplicateGroups(txns))
    }

    @Test
    fun `a different method is unrelated`() {
        val txns = listOf(txn("a", 1000, method = "GET"), txn("b", 1500, method = "POST"))
        assertEquals(emptyList(), duplicateGroups(txns))
    }

    /** A steady poll should read as one group, not a fragmented pile of pairs. */
    @Test
    fun `a run of close calls is one group measured between neighbours`() {
        val polled = (0..4).map { txn("p$it", 1000 + it * 2_000L) }
        val group = duplicateGroups(polled).single()
        assertEquals(5, group.ids.size)
        assertEquals(8_000, group.spanMs, "span is first to last, even though each gap is 2s")
    }

    @Test
    fun `a gap splits one run into two groups`() {
        val txns = listOf(txn("a", 0), txn("b", 1000), txn("c", 60_000), txn("d", 61_000))
        val groups = duplicateGroups(txns)
        assertEquals(2, groups.size)
        assertEquals(listOf("a", "b"), groups[0].ids)
        assertEquals(listOf("c", "d"), groups[1].ids)
    }

    @Test
    fun `headers are not part of the identity so signed requests still match`() {
        // Every request carries a fresh nonce and signature by design. Including headers in the
        // key would mean this feature found nothing on exactly the apps that need it most.
        val signed = listOf(
            txn("a", 1000).copy(reqHeaders = mapOf("X-Device-Nonce" to listOf("aaa"))),
            txn("b", 2000).copy(reqHeaders = mapOf("X-Device-Nonce" to listOf("bbb"))),
        )
        assertEquals(1, duplicateGroups(signed).size)
    }

    @Test
    fun `ids are flattened for surfaces that only highlight`() {
        val ids = duplicateIds(listOf(txn("a", 1000), txn("b", 2000), txn("c", 90_000)))
        assertEquals(setOf("a", "b"), ids)
    }

    @Test
    fun `a non-positive window disables detection`() {
        assertTrue(duplicateGroups(listOf(txn("a", 1000), txn("b", 1100)), windowMs = 0).isEmpty())
    }

    @Test
    fun `the port is part of the identity`() {
        val txns = listOf(
            txn("a", 1000).copy(port = 8080),
            txn("b", 1500).copy(port = 9090),
        )
        assertEquals(emptyList(), duplicateGroups(txns))
    }
}

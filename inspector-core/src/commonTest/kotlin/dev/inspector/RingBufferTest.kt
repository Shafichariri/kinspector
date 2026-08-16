package dev.inspector

import dev.inspector.internal.RingBuffer
import dev.inspector.model.NetworkTransaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The ring buffer is budgeted in **bytes**, not entries, because what actually threatens the host
 * app is retained body memory: one 256 KB response outweighs a thousand metadata-only rows.
 */
class RingBufferTest {

    private fun txn(id: String, path: String = "/x") = NetworkTransaction(
        id = id,
        ts = "2026-08-16T10:14:02.311Z",
        mono = 0,
        method = "GET",
        scheme = "https",
        host = "api.example.com",
        path = path,
        callId = "c-$id",
    )

    @Test
    fun newest_first_ordering() {
        val ring = RingBuffer(1024 * 1024)
        ring.add(txn("a"), null, null)
        ring.add(txn("b"), null, null)
        ring.add(txn("c"), null, null)

        assertEquals(listOf("c", "b", "a"), ring.snapshot().map { it.id })
    }

    @Test
    fun evicts_oldest_once_the_byte_budget_is_exceeded() {
        // Budget fits roughly two 4 KB bodies plus metadata.
        val ring = RingBuffer(10_000)
        val body = ByteArray(4_000)

        ring.add(txn("a"), null, body)
        ring.add(txn("b"), null, body)
        ring.add(txn("c"), null, body)

        val ids = ring.snapshot().map { it.id }
        assertTrue(ids.size < 3, "budget must have forced an eviction, kept $ids")
        assertEquals("c", ids.first(), "the newest entry must survive")
        assertTrue("a" !in ids, "the oldest entry must be the one evicted, kept $ids")
        assertTrue(ring.byteSize <= 10_000, "byte total must stay within budget, was ${ring.byteSize}")
    }

    @Test
    fun a_single_oversized_entry_is_still_kept() {
        // Dropping the very call the user just triggered would read as a bug, not as a budget.
        val ring = RingBuffer(1_000)
        ring.add(txn("huge"), null, ByteArray(500_000))

        assertEquals(1, ring.size)
        assertEquals("huge", ring.snapshot().single().id)
    }

    @Test
    fun body_bytes_count_toward_the_budget_not_just_row_count() {
        val small = RingBuffer(100_000)
        repeat(50) { small.add(txn("m$it"), null, null) }
        val metadataOnly = small.size

        val heavy = RingBuffer(100_000)
        repeat(50) { heavy.add(txn("h$it"), null, ByteArray(8_000)) }

        assertEquals(50, metadataOnly, "metadata-only rows are cheap and should all fit")
        assertTrue(
            heavy.size < metadataOnly,
            "rows carrying bodies must be evicted sooner; kept ${heavy.size} of 50"
        )
    }

    @Test
    fun clear_resets_size_and_bytes() {
        val ring = RingBuffer(100_000)
        ring.add(txn("a"), null, ByteArray(1_000))
        ring.clear()

        assertEquals(0, ring.size)
        assertEquals(0L, ring.byteSize)
        assertTrue(ring.snapshot().isEmpty())
    }

    @Test
    fun entries_snapshot_carries_bodies_for_the_detail_view() {
        val ring = RingBuffer(100_000)
        val body = "hello".encodeToByteArray()
        ring.add(txn("a"), null, body)

        val entry = ring.entriesSnapshot().single()
        assertEquals("a", entry.txn.id)
        assertEquals("hello", entry.resBody?.decodeToString())
    }
}

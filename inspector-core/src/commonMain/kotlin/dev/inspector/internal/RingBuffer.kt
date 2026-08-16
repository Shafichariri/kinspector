package dev.inspector.internal

import dev.inspector.model.NetworkTransaction

/**
 * Byte-budgeted FIFO of captured transactions.
 *
 * Budgeted by bytes rather than by count because the thing that actually threatens the app is
 * retained body memory, and one 256 KB response is worth a thousand metadata-only rows. Not
 * thread-safe by design: it is only ever touched from the single capture worker.
 */
internal class RingBuffer(maxBytes: Long) {

    /** Adjustable so `Inspector.init` can retune without replacing the buffer and orphaning
     *  every StateFlow subscriber already collecting from it. */
    var maxBytes: Long = maxBytes
        set(value) {
            field = value
            evictToBudget()
        }


    internal class Entry(
        val txn: NetworkTransaction,
        val reqBody: ByteArray?,
        val resBody: ByteArray?,
    ) {
        val bytes: Long = estimateBytes(txn) + (reqBody?.size ?: 0) + (resBody?.size ?: 0)
    }

    private val entries = ArrayDeque<Entry>()
    private var totalBytes = 0L

    val size: Int get() = entries.size
    val byteSize: Long get() = totalBytes

    fun add(txn: NetworkTransaction, reqBody: ByteArray?, resBody: ByteArray?) {
        val entry = Entry(txn, reqBody, resBody)
        entries.addLast(entry)
        totalBytes += entry.bytes
        evictToBudget()
    }

    /** Newest first, which is the order both the in-app list and the overlay want. */
    fun snapshot(): List<NetworkTransaction> = entries.map { it.txn }.asReversed()

    fun entriesSnapshot(): List<Entry> = entries.toList().asReversed()

    fun clear() {
        entries.clear()
        totalBytes = 0
    }

    private fun evictToBudget() {
        // Always keep at least one entry, even if a single body exceeds the whole budget —
        // dropping the very transaction the user just triggered would look like a bug.
        while (totalBytes > maxBytes && entries.size > 1) {
            totalBytes -= entries.removeFirst().bytes
        }
    }

    private companion object {
        /**
         * Approximate serialized weight of a transaction's metadata. Exact accounting would mean
         * serializing every row on the capture path purely to measure it, which costs more than
         * the precision is worth.
         */
        fun estimateBytes(txn: NetworkTransaction): Long {
            var n = 256L // fixed field overhead
            n += txn.host.length + txn.path.length + (txn.query?.length ?: 0)
            n += txn.error?.length ?: 0
            n += headerBytes(txn.reqHeaders) + headerBytes(txn.resHeaders)
            n += txn.redacted.sumOf { it.length + 4 }
            return n
        }

        fun headerBytes(headers: Map<String, List<String>>): Long =
            headers.entries.sumOf { (key, values) ->
                key.length + 8L + values.sumOf { it.length + 4 }
            }
    }
}

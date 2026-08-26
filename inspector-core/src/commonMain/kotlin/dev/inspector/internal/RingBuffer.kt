package dev.inspector.internal

import dev.inspector.model.NetworkTransaction
import dev.inspector.model.Signal

/**
 * Byte-budgeted FIFO.
 *
 * Budgeted by bytes rather than by count because the thing that actually threatens the app is
 * retained memory, and one 256 KB response is worth a thousand metadata-only rows. Not thread-safe
 * by design: it is only ever touched from the single capture worker.
 *
 * Generic over its entry type, with the weight of an entry supplied by [sizeOf], so transactions
 * and signals can each have **their own budget**. Sharing one budget would let a single large cache
 * snapshot evict the entire network history — at exactly the moment somebody needs both side by
 * side.
 */
internal class RingBuffer<T>(
    maxBytes: Long,
    private val sizeOf: (T) -> Long,
) {

    /** Adjustable so `Inspector.init` can retune without replacing the buffer and orphaning
     *  every StateFlow subscriber already collecting from it. */
    var maxBytes: Long = maxBytes
        set(value) {
            field = value
            evictToBudget()
        }

    /** Weight is computed once on insert: [sizeOf] must not be re-run during eviction. */
    private class Weighed<T>(val item: T, val bytes: Long)

    private val entries = ArrayDeque<Weighed<T>>()
    private var totalBytes = 0L

    val size: Int get() = entries.size
    val byteSize: Long get() = totalBytes

    fun add(item: T) {
        val entry = Weighed(item, sizeOf(item))
        entries.addLast(entry)
        totalBytes += entry.bytes
        evictToBudget()
    }

    /** Newest first, which is the order both the in-app list and the overlay want. */
    fun snapshot(): List<T> = entries.map { it.item }.asReversed()

    fun clear() {
        entries.clear()
        totalBytes = 0
    }

    private fun evictToBudget() {
        // Always keep at least one entry, even if a single item exceeds the whole budget —
        // dropping the very transaction the user just triggered would look like a bug.
        while (totalBytes > maxBytes && entries.size > 1) {
            totalBytes -= entries.removeFirst().bytes
        }
    }
}

/** A captured attempt together with the bodies retained for it. */
internal class CapturedTxn(
    val txn: NetworkTransaction,
    val reqBody: ByteArray?,
    val resBody: ByteArray?,
)

/**
 * Approximate retained weight of a captured transaction.
 *
 * Exact accounting would mean serializing every row on the capture path purely to measure it,
 * which costs more than the precision is worth.
 */
internal fun sizeOfCapturedTxn(entry: CapturedTxn): Long =
    estimateBytes(entry.txn) + (entry.reqBody?.size ?: 0) + (entry.resBody?.size ?: 0)

private fun estimateBytes(txn: NetworkTransaction): Long {
    var n = 256L // fixed field overhead
    n += txn.host.length + txn.path.length + (txn.query?.length ?: 0)
    n += txn.error?.length ?: 0
    n += headerBytes(txn.reqHeaders) + headerBytes(txn.resHeaders)
    n += txn.redacted.sumOf { it.length + 4 }
    return n
}

private fun headerBytes(headers: Map<String, List<String>>): Long =
    headers.entries.sumOf { (key, values) ->
        key.length + 8L + values.sumOf { it.length + 4 }
    }

/**
 * A signal together with the size of the payload actually retained for it.
 *
 * The retained size is carried rather than derived from [Signal.bytes], because `bytes` is the
 * *true* payload size even when the payload was truncated — budgeting against it would charge the
 * ring for memory it is not holding.
 */
internal class CapturedSignal(
    val signal: Signal,
    val retainedPayloadBytes: Long,
)

/** Approximate retained weight of a signal. */
internal fun sizeOfCapturedSignal(entry: CapturedSignal): Long {
    val signal = entry.signal
    var n = 128L // fixed field overhead
    n += signal.tag.length + signal.name.length + signal.id.length + signal.ts.length
    n += signal.dataRef?.length ?: 0
    n += signal.requestId?.length ?: 0
    n += entry.retainedPayloadBytes
    return n
}

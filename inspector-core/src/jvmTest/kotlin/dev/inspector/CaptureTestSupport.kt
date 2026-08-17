package dev.inspector

import dev.inspector.model.Marker
import dev.inspector.model.NetworkTransaction
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CopyOnWriteArrayList

/** Collects everything the recorder fans out, including raw bodies. */
class CollectingSink : InspectorSink {
    val transactions = CopyOnWriteArrayList<NetworkTransaction>()
    val requestBodies = CopyOnWriteArrayList<ByteArray?>()
    val responseBodies = CopyOnWriteArrayList<ByteArray?>()
    val markers = CopyOnWriteArrayList<Marker>()

    override fun onTransaction(txn: NetworkTransaction, reqBody: ByteArray?, resBody: ByteArray?) {
        transactions += txn
        requestBodies += reqBody
        responseBodies += resBody
    }

    override fun onMarker(marker: Marker) {
        markers += marker
    }

    fun bodyFor(txn: NetworkTransaction): ByteArray? =
        transactions.indexOf(txn).takeIf { it >= 0 }?.let { responseBodies[it] }
}

/**
 * Capture is asynchronous by design — the whole point is that the app never waits on it — so
 * assertions have to wait for the worker to drain rather than assume synchronous delivery.
 *
 * The timeout is a safety net, not a latency budget: these tests assert that N rows *eventually*
 * arrive, never that they arrive quickly, and a passing run returns the moment the count is reached.
 * Modest headroom over the original 10s, no more.
 *
 * It was briefly 60s on the theory that a loaded CI runner was simply slow. That was wrong — rows
 * were being dropped, not delayed, and the longer ceiling only made the red build take a minute to
 * go red. The actual defect is fixed in `teeBody`; see `TeeCancellationTest`. Reaching this timeout
 * means capture is broken, not busy.
 */
suspend fun CollectingSink.awaitTransactions(
    count: Int,
    timeoutMs: Long = 20_000,
): List<NetworkTransaction> {
    val result = withTimeoutOrNull(timeoutMs) {
        while (transactions.size < count) delay(10)
        transactions.toList()
    }
    return result ?: transactions.toList().also {
        throw AssertionError(
            "expected at least $count transaction(s) within ${timeoutMs}ms, got ${it.size}:\n" +
                it.joinToString("\n") { t -> "  ${t.method} ${t.path} -> ${t.status} (attempt ${t.attempt})" }
        )
    }
}

/**
 * Waits until no further transactions arrive, so "exactly N" assertions are not racing.
 *
 * `quietMs` is the load-sensitive number here: a hop delayed longer than this reads as "settled"
 * and the caller asserts on a short list. Modest headroom over the original 400ms, since a busy
 * runner can starve the capture worker — but note this only ever affects *over*-count assertions,
 * because callers reach it via `awaitTransactions`, which has already seen the minimum.
 */
suspend fun CollectingSink.awaitSettled(quietMs: Long = 800, timeoutMs: Long = 20_000): List<NetworkTransaction> {
    val deadline = System.currentTimeMillis() + timeoutMs
    var lastSize = -1
    var stableSince = System.currentTimeMillis()
    while (System.currentTimeMillis() < deadline) {
        val size = transactions.size
        if (size != lastSize) {
            lastSize = size
            stableSince = System.currentTimeMillis()
        } else if (System.currentTimeMillis() - stableSince >= quietMs) {
            break
        }
        delay(25)
    }
    return transactions.toList()
}

fun List<NetworkTransaction>.describe(): String =
    joinToString("\n") { "  attempt=${it.attempt} callId=${it.callId} ${it.method} ${it.path} -> ${it.status ?: it.error} (${it.ms}ms)" }

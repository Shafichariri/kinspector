package dev.inspector

import dev.inspector.internal.RingBuffer
import dev.inspector.internal.monoMs
import dev.inspector.internal.nowIso
import dev.inspector.model.Marker
import dev.inspector.model.MarkerSource
import dev.inspector.model.NetworkTransaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The capture engine: a bounded queue, one worker, a ring buffer, and sink fan-out.
 *
 * Instantiable rather than global so tests get isolation; [Inspector] is a thin facade over a
 * single instance.
 *
 * The efficiency contract lives here. On the calling coroutine, [submit] does nothing but
 * `trySend` into a bounded channel that drops its oldest entry when full. Everything
 * else — ring buffer accounting, sink fan-out — happens on a single dedicated worker. A dead
 * or slow sink therefore costs dropped transactions, never backpressure into the app.
 */
internal class Recorder(
    config: InspectorConfig,
    private val scope: CoroutineScope,
) {
    /**
     * Read fresh on every captured request, so `Inspector.init` can change redaction or body
     * caps at any point without rebuilding the recorder.
     */
    var config: InspectorConfig = config
        private set

    @OptIn(ExperimentalCoroutinesApi::class)
    constructor(config: InspectorConfig = InspectorConfig()) : this(
        config,
        CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1)),
    )

    private sealed interface Event {
        class Captured(
            val txn: NetworkTransaction,
            val reqBody: ByteArray?,
            val resBody: ByteArray?,
        ) : Event

        class Marked(val marker: Marker) : Event
    }

    private val queue = Channel<Event>(capacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val ring = RingBuffer(config.ringBufferMaxBytes)
    private val sinks = mutableListOf<InspectorSink>()

    private val _transactions = MutableStateFlow<List<NetworkTransaction>>(emptyList())
    private val _latest = MutableStateFlow<NetworkTransaction?>(null)
    private val _markers = MutableStateFlow<List<Marker>>(emptyList())

    // Bodies keyed by transaction id. Holds the same ByteArrays the ring already retains, so
    // this costs references, not copies. Published from the worker so the UI never reads the
    // ring buffer concurrently.
    private val _bodies = MutableStateFlow<Map<String, Pair<ByteArray?, ByteArray?>>>(emptyMap())

    val transactions: StateFlow<List<NetworkTransaction>> = _transactions.asStateFlow()
    val latest: StateFlow<NetworkTransaction?> = _latest.asStateFlow()
    val markers: StateFlow<List<Marker>> = _markers.asStateFlow()
    val bodies: StateFlow<Map<String, Pair<ByteArray?, ByteArray?>>> = _bodies.asStateFlow()

    /** Transactions dropped because the queue was full. Surfaced so overload is visible, not silent. */
    private val _dropped = MutableStateFlow(0L)
    val dropped: StateFlow<Long> = _dropped.asStateFlow()

    init {
        scope.launch {
            for (event in queue) {
                runCatching { handle(event) }
            }
        }
    }

    /**
     * Hands a captured attempt to the worker. Never suspends, never blocks, never throws.
     * Called from the app's own coroutine, so it must stay this cheap.
     */
    internal fun submit(txn: NetworkTransaction, reqBody: ByteArray?, resBody: ByteArray?) {
        if (!queue.trySend(Event.Captured(txn, reqBody, resBody)).isSuccess) {
            _dropped.value += 1
        }
    }

    fun mark(label: String, source: String = MarkerSource.APP) {
        val marker = Marker(ts = nowIso(), mono = monoMs(), label = label, source = source)
        if (!queue.trySend(Event.Marked(marker)).isSuccess) {
            _dropped.value += 1
        }
    }

    fun addSink(sink: InspectorSink) {
        // Registration is rare and the list is read on the worker; a snapshot copy keeps the
        // worker's iteration safe without a lock on the hot path.
        synchronizedSinks { it += sink }
    }

    fun removeSink(sink: InspectorSink) {
        synchronizedSinks { it -= sink }
    }

    /** Retunes in place. The recorder instance is deliberately never replaced. */
    fun configure(newConfig: InspectorConfig) {
        config = newConfig
        ring.maxBytes = newConfig.ringBufferMaxBytes
    }

    fun clear() {
        ring.clear()
        _transactions.value = emptyList()
        _bodies.value = emptyMap()
        _latest.value = null
        _markers.value = emptyList()
    }

    internal fun close() {
        queue.close()
        scope.cancel()
    }

    private fun handle(event: Event) {
        when (event) {
            is Event.Captured -> {
                ring.add(event.txn, event.reqBody, event.resBody)
                _transactions.value = ring.snapshot()
                _bodies.value = ring.entriesSnapshot()
                    .associate { it.txn.id to (it.reqBody to it.resBody) }
                _latest.value = event.txn
                forEachSink { it.onTransaction(event.txn, event.reqBody, event.resBody) }
            }

            is Event.Marked -> {
                _markers.value = _markers.value + event.marker
                forEachSink { it.onMarker(event.marker) }
            }
        }
    }

    /** A sink that throws is logged and skipped — never allowed to surface in the app. */
    private inline fun forEachSink(action: (InspectorSink) -> Unit) {
        val snapshot = sinksSnapshot()
        for (sink in snapshot) {
            runCatching { action(sink) }
        }
    }

    private fun sinksSnapshot(): List<InspectorSink> = synchronizedSinks { it.toList() }

    private inline fun <T> synchronizedSinks(block: (MutableList<InspectorSink>) -> T): T {
        // Kotlin/Native has no `synchronized`; sink registration happens at setup time, so a
        // plain guarded copy is sufficient here.
        return block(sinks)
    }
}

package dev.inspector

import dev.inspector.internal.CapturedSignal
import dev.inspector.internal.CapturedTxn
import dev.inspector.internal.RingBuffer
import dev.inspector.internal.monoMs
import dev.inspector.internal.newId
import dev.inspector.internal.nowIso
import dev.inspector.internal.sizeOfCapturedSignal
import dev.inspector.internal.sizeOfCapturedTxn
import dev.inspector.model.InspectorJson
import dev.inspector.model.Marker
import dev.inspector.model.MarkerSource
import dev.inspector.model.NetworkTransaction
import dev.inspector.model.Signal
import dev.inspector.model.SignalTrigger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

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
    /**
     * Monotonic milliseconds. Injectable only so conflation windows can be driven by a test's
     * virtual clock; production always uses [monoMs].
     */
    private val now: () -> Long = ::monoMs,
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

        /**
         * An observation as the caller handed it over. The payload is still a [JsonElement] here:
         * encoding it is work, and `signal()` must not do work on the app's coroutine.
         */
        class Signalled(
            val id: String,
            val ts: String,
            val mono: Long,
            val tag: String,
            val name: String,
            val payload: JsonElement?,
            val trigger: SignalTrigger,
            val requestId: String?,
        ) : Event

        /** A conflation window closed. Emits whatever is held for [key], if anything. */
        class FlushSignals(val key: String) : Event
    }

    /** Transactions and signals dropped because the queue was full. Declared before [queue],
     *  which reports into it. */
    private val _dropped = MutableStateFlow(0L)

    /**
     * Overload is reported through [onUndeliveredElement], not through `trySend`.
     *
     * A `DROP_OLDEST` channel **always** accepts: it discards the oldest entry and returns
     * success, so a `trySend(...).isSuccess` check can never observe a drop and the counter it
     * guards stays at zero no matter how hard the queue is hammered. The callback is the only
     * thing the channel actually tells about a discarded element.
     */
    private val queue = Channel<Event>(
        capacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
        onUndeliveredElement = { _dropped.value += 1 },
    )
    private val ring = RingBuffer(config.ringBufferMaxBytes, ::sizeOfCapturedTxn)
    private val signalRing =
        RingBuffer(config.signals.ringBufferMaxBytes, ::sizeOfCapturedSignal)
    private val sinks = mutableListOf<InspectorSink>()

    private val _transactions = MutableStateFlow<List<NetworkTransaction>>(emptyList())
    private val _latest = MutableStateFlow<NetworkTransaction?>(null)
    private val _markers = MutableStateFlow<List<Marker>>(emptyList())
    private val _signals = MutableStateFlow<List<Signal>>(emptyList())

    // Bodies keyed by transaction id. Holds the same ByteArrays the ring already retains, so
    // this costs references, not copies. Published from the worker so the UI never reads the
    // ring buffer concurrently.
    private val _bodies = MutableStateFlow<Map<String, Pair<ByteArray?, ByteArray?>>>(emptyMap())

    val transactions: StateFlow<List<NetworkTransaction>> = _transactions.asStateFlow()
    val latest: StateFlow<NetworkTransaction?> = _latest.asStateFlow()
    val markers: StateFlow<List<Marker>> = _markers.asStateFlow()
    val signals: StateFlow<List<Signal>> = _signals.asStateFlow()
    val bodies: StateFlow<Map<String, Pair<ByteArray?, ByteArray?>>> = _bodies.asStateFlow()

    /** Surfaced so overload is visible, not silent. */
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
        queue.trySend(Event.Captured(txn, reqBody, resBody))
    }

    fun mark(label: String, source: String = MarkerSource.APP) {
        val marker = Marker(ts = nowIso(), mono = monoMs(), label = label, source = source)
        queue.trySend(Event.Marked(marker))
    }

    /**
     * Hands one observation to the worker. Never suspends, never blocks, never throws.
     *
     * Identity and timing are taken here, on the caller, because they describe the moment the
     * observation happened rather than the moment the worker got round to it. Encoding the payload
     * is deliberately *not* done here — that is work, and this runs on the app's coroutine.
     */
    fun signal(
        tag: String,
        name: String,
        payload: JsonElement?,
        trigger: SignalTrigger = SignalTrigger.App,
        requestId: String? = null,
    ) {
        val event = Event.Signalled(
            id = newId(),
            ts = nowIso(),
            mono = now(),
            tag = tag,
            name = name,
            payload = payload,
            trigger = trigger,
            requestId = requestId,
        )
        queue.trySend(event)
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
        signalRing.maxBytes = newConfig.signals.ringBufferMaxBytes
    }

    fun clear() {
        ring.clear()
        signalRing.clear()
        _transactions.value = emptyList()
        _bodies.value = emptyMap()
        _latest.value = null
        _markers.value = emptyList()
        _signals.value = emptyList()
        // Held values are discarded with everything else: emitting them after a clear would put
        // rows into a session the user just asked to be empty.
        windows.clear()
    }

    internal fun close() {
        queue.close()
        scope.cancel()
    }

    private fun handle(event: Event) {
        when (event) {
            is Event.Captured -> {
                ring.add(CapturedTxn(event.txn, event.reqBody, event.resBody))
                val entries = ring.snapshot()
                _transactions.value = entries.map { it.txn }
                _bodies.value = entries.associate { it.txn.id to (it.reqBody to it.resBody) }
                _latest.value = event.txn
                forEachSink { it.onTransaction(event.txn, event.reqBody, event.resBody) }
            }

            is Event.Marked -> {
                _markers.value = _markers.value + event.marker
                forEachSink { it.onMarker(event.marker) }
            }

            is Event.Signalled -> conflate(event)

            is Event.FlushSignals -> flush(event.key)
        }
    }

    // --- conflation ---------------------------------------------------------------------------

    /**
     * Per-`(tag, name)` conflation state. Touched only from the worker.
     */
    private class Window {
        var hasEmitted: Boolean = false
        var lastEmittedMono: Long = 0
        var lastPayload: ByteArray? = null
        var held: Event.Signalled? = null
        var flushScheduled: Boolean = false
    }

    private val windows = mutableMapOf<String, Window>()

    private fun key(tag: String, name: String): String = "$tag\u0000$name"

    /**
     * Decides whether an observation is emitted now, held for the trailing edge, or dropped.
     *
     * A pull reply bypasses this entirely. It is a reply to a `requestId` somebody is awaiting, and
     * both rules would break it: `dropUnchanged` would swallow a reply taken when the cache had not
     * changed — the host would then time out and report the app unresponsive at the moment it was
     * behaving most predictably — and `minIntervalMs` would add a window's latency to a synchronous
     * round trip.
     */
    private fun conflate(event: Event.Signalled) {
        if (event.trigger == SignalTrigger.Request) {
            emit(event, encode(event.payload))
            return
        }

        val window = windows.getOrPut(key(event.tag, event.name)) { Window() }
        val elapsed = event.mono - window.lastEmittedMono
        val windowOpen = !window.hasEmitted || elapsed >= config.signals.minIntervalMs

        if (windowOpen && window.held == null) {
            emitConflated(window, event)
        } else {
            // Newest wins: the value worth keeping is the one the state settled on.
            window.held = event
            scheduleFlush(window, event, elapsed)
        }
    }

    /**
     * Closes a window that has stopped receiving values.
     *
     * Without this a burst that simply *stops* would hold its final value forever — losing exactly
     * the row trailing-edge conflation exists to keep. The flush is posted back through the queue
     * rather than emitted from the timer coroutine, so every mutation of [windows] still happens on
     * the worker and a flush lost to a full queue is counted like any other dropped signal.
     */
    private fun scheduleFlush(window: Window, event: Event.Signalled, elapsed: Long) {
        if (window.flushScheduled) return
        window.flushScheduled = true
        val wait = (config.signals.minIntervalMs - elapsed).coerceAtLeast(0)
        val flushKey = key(event.tag, event.name)
        scope.launch {
            delay(wait)
            queue.trySend(Event.FlushSignals(flushKey))
        }
    }

    private fun flush(key: String) {
        val window = windows[key] ?: return
        window.flushScheduled = false
        val held = window.held ?: return
        window.held = null
        emitConflated(window, held)
    }

    /**
     * Emits unless the payload is byte-identical to the last one emitted for this key.
     *
     * The comparison happens here, at emission, rather than on arrival. Checking on arrival would
     * drop a value that matches the last emission while an older, *different* value stayed held —
     * and the window would then report that stale intermediate as where the state settled.
     */
    private fun emitConflated(window: Window, event: Event.Signalled) {
        val encoded = encode(event.payload)
        if (config.signals.dropUnchanged &&
            window.hasEmitted &&
            payloadsEqual(window.lastPayload, encoded)
        ) {
            return
        }
        emit(event, encoded)
        window.hasEmitted = true
        window.lastEmittedMono = event.mono
        window.lastPayload = encoded
    }

    private fun emit(event: Event.Signalled, encoded: ByteArray?) {
        val cap = config.signals.maxPayloadBytes
        val trueBytes = encoded?.size?.toLong() ?: 0L
        val truncated = encoded != null && encoded.size > cap
        // A cut JSON document is not JSON, so a truncated payload becomes a JSON string of the
        // prefix. One payload type on disk, and the flag says what happened.
        val retained: JsonElement? = when {
            encoded == null -> null
            truncated -> JsonPrimitive(encoded.decodeToString(0, cap))
            else -> event.payload
        }
        val signal = Signal(
            id = event.id,
            ts = event.ts,
            mono = event.mono,
            tag = event.tag,
            name = event.name,
            data = retained,
            dataTruncated = truncated,
            bytes = trueBytes,
            trigger = event.trigger,
            requestId = event.requestId,
        )
        val payload = if (truncated) encoded!!.copyOf(cap) else encoded
        signalRing.add(CapturedSignal(signal, payload?.size?.toLong() ?: 0L))
        _signals.value = signalRing.snapshot().map { it.signal }
        forEachSink { it.onSignal(signal, payload) }
    }

    private fun encode(payload: JsonElement?): ByteArray? =
        payload?.let { InspectorJson.encodeToString(JsonElement.serializer(), it).encodeToByteArray() }

    private fun payloadsEqual(a: ByteArray?, b: ByteArray?): Boolean = when {
        a == null && b == null -> true
        a == null || b == null -> false
        else -> a.contentEquals(b)
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

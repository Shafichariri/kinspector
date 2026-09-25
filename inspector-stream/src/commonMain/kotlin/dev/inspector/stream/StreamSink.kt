package dev.inspector.stream

import dev.inspector.InspectorSink
import dev.inspector.model.Bye
import dev.inspector.model.ClientInfo
import dev.inspector.model.Hello
import dev.inspector.model.HelloAck
import dev.inspector.model.InspectorJson
import dev.inspector.model.Marker
import dev.inspector.Inspector
import dev.inspector.model.MarkerMsg
import dev.inspector.model.Signal
import dev.inspector.model.SignalError
import dev.inspector.model.SignalMsg
import dev.inspector.model.SignalRequest
import dev.inspector.model.SignRequest
import dev.inspector.model.SignResponse
import dev.inspector.model.NetworkTransaction
import dev.inspector.model.Txn
import dev.inspector.model.WireMsg
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Connection state, surfaced so the in-app UI can show whether the host is attached. */
enum class StreamState { Disconnected, Connecting, Connected }

/**
 * Produces the per-request headers a replayed request needs regenerated.
 *
 * Registered by the app, called by the host when it replays a captured request. The app returns
 * whatever must be fresh — a timestamp, a nonce, a signature over them — and the host merges the
 * result over the captured headers.
 *
 * This exists because the interesting keys cannot leave the device. On Android and iOS a device
 * key lives in the Keystore or the Secure Enclave and is non-exportable by construction: the only
 * operation exposed is "sign these bytes". A host-side replay therefore cannot reproduce a signed
 * request on its own, and the alternatives are a debug-only software key (a different key, so not
 * production behaviour) or sending it unsigned (not a test of anything). Asking the app is the
 * only option that reproduces what production does.
 *
 * The contract is deliberately "give me headers for this request", not "sign these bytes":
 * Inspector never learns the signing scheme, so this works for any of them, and no part of this
 * codebase becomes a signing oracle.
 *
 * Called off the main thread. Implementations may suspend; the host applies its own timeout.
 * **Debug builds only** — the release swap removes this whole module.
 */
fun interface ReplaySigner {
    /**
     * Headers to apply to a replay of [method] [url]. Returning an empty map means "nothing to
     * add"; throwing is reported to the host as a failed replay rather than being swallowed.
     */
    suspend fun headersFor(method: String, url: String): Map<String, String>
}

/**
 * Streams captured transactions to the host daemon over `WS /ingest` — or, on a physical iPhone,
 * over USB, where the host dials in instead; see [UsbListenerTransport].
 *
 * The contract, in order of importance:
 * - **The app never waits on this.** [onTransaction] only offers into a bounded DROP_OLDEST
 *   queue. A dead, slow or absent daemon costs dropped rows, never backpressure into the app.
 * - Its own [HttpClient] deliberately does **not** carry the capture plugin, so the inspector
 *   can never observe and thereby amplify its own traffic.
 * - Reconnects with exponential backoff and re-sends `resumeSessionId`, so a brief disconnect
 *   continues one session folder instead of fragmenting a debugging run.
 *
 * [host] defaults per platform with no discovery; see [defaultDaemonHost] for what that means
 * on a phone as opposed to an emulator. On a physical iPhone [port] is the one the app listens on,
 * and it must match the daemon's: the USB bridge dials the port number `inspector serve` runs on.
 */
class StreamSink(
    private val client: ClientInfo,
    private val host: String = defaultDaemonHost(),
    private val port: Int = 8099,
    private val engineFactory: () -> HttpClient = ::defaultStreamClient,
    /**
     * Optional. Without it the host can still replay, but any request whose headers must be
     * regenerated will be rejected by the server; the host says so explicitly rather than letting
     * it look like a backend fault.
     *
     * Added last so existing `StreamSink(clientInfo)` call sites are unaffected.
     */
    private val signer: ReplaySigner? = null,
) : InspectorSink {

    private sealed interface Outbound {
        class Transaction(val txn: NetworkTransaction, val req: ByteArray?, val res: ByteArray?) : Outbound
        class MarkerOut(val marker: Marker) : Outbound
        class SignalOut(val signal: Signal, val data: ByteArray?) : Outbound
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default.limitedParallelism(1)
    )

    /** Declared before [queue], which reports into it. */
    private val _dropped = MutableStateFlow(0L)

    /**
     * Rows discarded because the daemon could not keep up. Reported through
     * [onUndeliveredElement], not through `trySend`.
     *
     * A `DROP_OLDEST` channel always accepts — it discards the oldest entry and returns success —
     * so a `trySend(...).isSuccess` check can never observe a drop and would leave this counter at
     * zero however far behind the daemon fell.
     */
    private val queue = Channel<Outbound>(
        capacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
        onUndeliveredElement = { _dropped.value += 1 },
    )

    private val _state = MutableStateFlow(StreamState.Disconnected)
    val state: StateFlow<StreamState> = _state.asStateFlow()

    val dropped: StateFlow<Long> = _dropped.asStateFlow()

    /**
     * Why the last connection attempt failed, or null once connected.
     *
     * Surfaced rather than swallowed because "the web UI is empty" is otherwise undiagnosable
     * from inside the app: the most common causes — Android blocking cleartext, nothing
     * listening on the port — are invisible unless the reason is reported.
     */
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private var reportedError: String? = null
    private var resumeSessionId: String? = null

    /**
     * How the host is reached. A `var` only so this module's tests can drive the USB route on the
     * JVM, where [listensForUsb] is never true; nothing else assigns it. Internal members are
     * name-mangled on the JVM, so this stays out of the public API and the parity golden file.
     */
    internal var transport: Transport =
        if (listensForUsb(host)) UsbListenerTransport(port) else WebSocketTransport(host, port, engineFactory)

    fun start() {
        scope.launch { runConnectionLoop() }
    }

    fun stop() {
        queue.close()
        transport.close()
        scope.cancel()
        _state.value = StreamState.Disconnected
    }

    override fun onTransaction(txn: NetworkTransaction, reqBody: ByteArray?, resBody: ByteArray?) {
        queue.trySend(Outbound.Transaction(txn, reqBody, resBody))
    }

    override fun onSignal(signal: Signal, data: ByteArray?) {
        queue.trySend(Outbound.SignalOut(signal, data))
    }

    override fun onMarker(marker: Marker) {
        queue.trySend(Outbound.MarkerOut(marker))
    }

    private suspend fun runConnectionLoop() {
        var backoffMs = MIN_BACKOFF_MS
        while (scope.isActive) {
            _state.value = StreamState.Connecting
            val outcome = runCatching { connectAndPump() }
            _state.value = StreamState.Disconnected

            val failure = outcome.exceptionOrNull()
            if (failure != null) {
                val described = "${failure::class.simpleName}: ${failure.message}"
                _lastError.value = described
                // Report each distinct reason once. Reconnect attempts repeat forever, and a
                // message printed every 250ms is one nobody reads.
                if (described != reportedError) {
                    reportedError = described
                    println("inspector: cannot reach ${transport.description} — $described")
                    println("inspector: ${connectionHelp(host, port)}")
                }
            } else {
                _lastError.value = null
                reportedError = null
            }

            // A clean close still means the daemon went away; back off either way rather than
            // spinning a reconnect loop against a host that is not there.
            backoffMs = if (failure == null) MIN_BACKOFF_MS else (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
            delay(backoffMs)
        }
    }

    private suspend fun connectAndPump() {
        transport.session { link -> pump(link) }
    }

    private suspend fun pump(link: WireLink) {
        link.send(InspectorJson.encodeToString<WireMsg>(Hello(client, resumeSessionId)))

        val ack = link.receive()
            ?: throw IllegalStateException("the host closed the connection before acknowledging")
        val message = runCatching { InspectorJson.decodeFromString<WireMsg>(ack) }.getOrNull()
        if (message is HelloAck) resumeSessionId = message.sessionId
        _state.value = StreamState.Connected
        _lastError.value = null
        reportedError = null
        println("inspector: connected to ${transport.description}")

        // Two concurrent jobs, each cancelling the other on completion.
        //
        // The sender alone is not enough: when the queue is idle it parks in `receive()`, so
        // a daemon that dies goes unnoticed until the next transaction happens to arrive —
        // which is exactly the reconnect bug the round-trip test caught. Reading the link is
        // what actually observes the peer going away.
        coroutineScope {
            val sender = launch {
                for (item in queue) {
                    val frame = when (item) {
                        is Outbound.Transaction -> InspectorJson.encodeToString<WireMsg>(
                            Txn(
                                txn = item.txn,
                                reqBody = item.req?.let { encodeBody(it) },
                                resBody = item.res?.let { encodeBody(it) },
                                reqBodyB64 = item.req?.let { !isUtf8(it) } ?: false,
                                resBodyB64 = item.res?.let { !isUtf8(it) } ?: false,
                            )
                        )
                        is Outbound.MarkerOut ->
                            InspectorJson.encodeToString<WireMsg>(MarkerMsg(item.marker))
                        // `data = null` on the row: the payload rides beside it, and the
                        // daemon is what assigns `dataRef`. Sending both would ship the
                        // payload twice.
                        is Outbound.SignalOut -> InspectorJson.encodeToString<WireMsg>(
                            SignalMsg(
                                signal = item.signal.copy(data = null),
                                data = item.data?.let { encodeBody(it) },
                                dataB64 = item.data?.let { !isUtf8(it) } ?: false,
                            )
                        )
                    }
                    link.send(frame)
                }
                // Queue closed means stop() was called: say goodbye if the socket still lives.
                runCatching { link.send(InspectorJson.encodeToString<WireMsg>(Bye)) }
            }

            // Reads to observe the peer going away, and now also to serve the host's sign
            // requests. The read itself is still what detects a dead daemon, so the liveness
            // role is unchanged.
            val watcher = launch {
                runCatching {
                    while (true) {
                        val text = link.receive() ?: break
                        val message = runCatching {
                            InspectorJson.decodeFromString<WireMsg>(text)
                        }.getOrNull()
                        // Launched rather than awaited inline: signing can touch a hardware
                        // key and may prompt for user presence, and a provider may read a
                        // cache or a database. Blocking here would stall the liveness read
                        // for as long as either takes.
                        when (message) {
                            is SignRequest -> launch { link.answerSignRequest(message) }
                            is SignalRequest -> launch { link.answerSignalRequest(message) }
                            else -> Unit
                        }
                    }
                }
            }

            watcher.invokeOnCompletion { sender.cancel() }
            sender.invokeOnCompletion { watcher.cancel() }
        }
    }


    /**
     * Answers one [SignalRequest] by reading the app's registered provider.
     *
     * On success nothing is sent from here: [Inspector.answerSignalRequest] records the row, which
     * reaches this sink through [onSignal] and goes out as an ordinary [SignalMsg] carrying
     * `trigger = request` and the same `requestId`. One path for every row, whoever asked for it.
     *
     * On failure a [SignalError] goes back instead, so the host reports why rather than waiting
     * out its timeout and calling the app unresponsive.
     */
    private suspend fun WireLink.answerSignalRequest(request: SignalRequest) {
        val error = runCatching {
            Inspector.answerSignalRequest(request.tag, request.name, request.requestId)
        }.getOrElse { "${it::class.simpleName}: ${it.message}" }

        if (error != null) {
            runCatching {
                send(
                    InspectorJson.encodeToString<WireMsg>(
                        SignalError(requestId = request.requestId, error = error)
                    )
                )
            }
        }
    }

    /**
     * Answers one [SignRequest] on the socket it arrived on.
     *
     * Always replies, including on failure. A silent drop would leave the host waiting for its
     * timeout and then reporting something vague; naming the reason here is the difference between
     * "no signer is registered in this build" and "replay didn't work".
     */
    private suspend fun WireLink.answerSignRequest(request: SignRequest) {
        val reply = if (signer == null) {
            SignResponse(
                requestId = request.requestId,
                error = "no ReplaySigner is registered on this StreamSink, so per-request headers " +
                    "cannot be regenerated on the device",
            )
        } else {
            try {
                SignResponse(
                    requestId = request.requestId,
                    headers = signer.headersFor(request.method, request.url),
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (cause: Throwable) {
                SignResponse(
                    requestId = request.requestId,
                    error = "${cause::class.simpleName}: ${cause.message}",
                )
            }
        }
        // The socket may already be gone; the host times out on its side either way.
        runCatching { send(InspectorJson.encodeToString<WireMsg>(reply)) }
    }

    private fun encodeBody(bytes: ByteArray): String =
        if (isUtf8(bytes)) bytes.decodeToString() else base64Encode(bytes)

    private companion object {
        const val MIN_BACKOFF_MS = 250L
        const val MAX_BACKOFF_MS = 5_000L
    }
}

/**
 * Builds a [ClientInfo] with platform, device and OS version filled in.
 *
 * Exists so integrating an app is three arguments rather than six, and so the platform string
 * is decided by code that can actually detect a simulator instead of by whoever is wiring it up.
 */
expect fun defaultClientInfo(
    appId: String,
    appVersion: String,
    buildType: String = "debug",
): ClientInfo

/**
 * Default daemon host per platform. Nothing is discovered: every supported route is a fixed
 * address, and the one that varies is Android, where an emulator reaches the host machine through
 * `10.0.2.2` and a USB-attached phone reaches it through its own loopback once `adb reverse` is
 * set up. Desktop and the iOS simulator share the host's loopback outright.
 */
expect fun defaultDaemonHost(): String

/**
 * What to try when the daemon cannot be reached, as one line of prose after the exception itself.
 *
 * Per-platform because the remedies are: `adb reverse` and a cleartext exemption mean nothing on
 * iOS, and the address that is wrong tells you which mistake was made. The message is the whole
 * diagnosis available from inside a device — "the web UI is empty" says nothing on its own — so
 * it names the address it actually tried rather than the one the author assumed.
 */
internal expect fun connectionHelp(host: String, port: Int): String

/**
 * The sink's own client. Must never carry the capture plugin — an inspector observing its own
 * uploads would amplify every transaction into more transactions.
 */
expect fun defaultStreamClient(): HttpClient

/** True when the bytes are valid UTF-8, deciding whether a body rides as text or base64. */
internal expect fun isUtf8(bytes: ByteArray): Boolean

internal expect fun base64Encode(bytes: ByteArray): String

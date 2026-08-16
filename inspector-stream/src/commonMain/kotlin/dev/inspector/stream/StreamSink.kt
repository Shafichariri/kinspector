package dev.inspector.stream

import dev.inspector.InspectorSink
import dev.inspector.model.Bye
import dev.inspector.model.ClientInfo
import dev.inspector.model.Hello
import dev.inspector.model.HelloAck
import dev.inspector.model.InspectorJson
import dev.inspector.model.Marker
import dev.inspector.model.MarkerMsg
import dev.inspector.model.NetworkTransaction
import dev.inspector.model.Txn
import dev.inspector.model.WireMsg
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
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
 * Streams captured transactions to the host daemon over `WS /ingest`.
 *
 * The contract, in order of importance:
 * - **The app never waits on this.** [onTransaction] only offers into a bounded DROP_OLDEST
 *   queue. A dead, slow or absent daemon costs dropped rows, never backpressure into the app.
 * - Its own [HttpClient] deliberately does **not** carry the capture plugin, so the inspector
 *   can never observe and thereby amplify its own traffic.
 * - Reconnects with exponential backoff and re-sends `resumeSessionId`, so a brief disconnect
 *   continues one session folder instead of fragmenting a debugging run.
 *
 * v1 targets simulators and emulators only, so [host] defaults per platform with no discovery.
 */
class StreamSink(
    private val client: ClientInfo,
    private val host: String = defaultDaemonHost(),
    private val port: Int = 8099,
    private val engineFactory: () -> HttpClient = ::defaultStreamClient,
) : InspectorSink {

    private sealed interface Outbound {
        class Transaction(val txn: NetworkTransaction, val req: ByteArray?, val res: ByteArray?) : Outbound
        class MarkerOut(val marker: Marker) : Outbound
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default.limitedParallelism(1)
    )

    private val queue = Channel<Outbound>(capacity = 512, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private val _state = MutableStateFlow(StreamState.Disconnected)
    val state: StateFlow<StreamState> = _state.asStateFlow()

    private val _dropped = MutableStateFlow(0L)
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
    private var http: HttpClient? = null

    fun start() {
        scope.launch { runConnectionLoop() }
    }

    fun stop() {
        queue.close()
        http?.close()
        scope.cancel()
        _state.value = StreamState.Disconnected
    }

    override fun onTransaction(txn: NetworkTransaction, reqBody: ByteArray?, resBody: ByteArray?) {
        if (!queue.trySend(Outbound.Transaction(txn, reqBody, resBody)).isSuccess) {
            _dropped.value += 1
        }
    }

    override fun onMarker(marker: Marker) {
        if (!queue.trySend(Outbound.MarkerOut(marker)).isSuccess) {
            _dropped.value += 1
        }
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
                    println("inspector: cannot reach daemon at $host:$port — $described")
                    println("inspector: $TROUBLESHOOTING")
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
        val http = this.http ?: engineFactory().also { this.http = it }

        http.webSocket(host = host, port = port, path = "/ingest") {
            send(Frame.Text(InspectorJson.encodeToString<WireMsg>(Hello(client, resumeSessionId))))

            val ack = incoming.receive()
            if (ack is Frame.Text) {
                val message = runCatching {
                    InspectorJson.decodeFromString<WireMsg>(ack.readText())
                }.getOrNull()
                if (message is HelloAck) resumeSessionId = message.sessionId
            }
            _state.value = StreamState.Connected
            _lastError.value = null
            reportedError = null
            println("inspector: connected to daemon at $host:$port")

            // Two concurrent jobs, each cancelling the other on completion.
            //
            // The sender alone is not enough: when the queue is idle it parks in `receive()`, so
            // a daemon that dies goes unnoticed until the next transaction happens to arrive —
            // which is exactly the reconnect bug the round-trip test caught. Reading `incoming`
            // is what actually observes the peer going away.
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
                        }
                        send(Frame.Text(frame))
                    }
                    // Queue closed means stop() was called: say goodbye if the socket still lives.
                    runCatching { send(Frame.Text(InspectorJson.encodeToString<WireMsg>(Bye))) }
                }

                val watcher = launch {
                    runCatching { for (frame in incoming) Unit }
                }

                watcher.invokeOnCompletion { sender.cancel() }
                sender.invokeOnCompletion { watcher.cancel() }
            }
        }
    }

    private fun encodeBody(bytes: ByteArray): String =
        if (isUtf8(bytes)) bytes.decodeToString() else base64Encode(bytes)

    private companion object {
        const val MIN_BACKOFF_MS = 250L
        const val MAX_BACKOFF_MS = 5_000L
        const val TROUBLESHOOTING =
            "is `inspector serve` running? On Android the app must also permit cleartext " +
                "traffic to 10.0.2.2 — see docs/INTEGRATION.md section 6d."
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
 * Default daemon host per platform. v1 is simulator/emulator only, so these are fixed rather
 * than discovered: the iOS simulator and desktop share the host's loopback, while the Android
 * emulator reaches it through 10.0.2.2.
 */
expect fun defaultDaemonHost(): String

/**
 * The sink's own client. Must never carry the capture plugin — an inspector observing its own
 * uploads would amplify every transaction into more transactions.
 */
expect fun defaultStreamClient(): HttpClient

/** True when the bytes are valid UTF-8, deciding whether a body rides as text or base64. */
internal expect fun isUtf8(bytes: ByteArray): Boolean

internal expect fun base64Encode(bytes: ByteArray): String

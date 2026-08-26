package dev.inspector.stream

import dev.inspector.InspectorSink
import dev.inspector.model.ClientInfo
import dev.inspector.model.Marker
import dev.inspector.model.NetworkTransaction
import dev.inspector.model.Platforms
import dev.inspector.model.Signal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Release-build stand-in for `:inspector-stream`.
 *
 * Same public surface, no socket, no queue, no threads. Without this, `-Pinspector=off` would
 * drop the real module and the app would fail to compile — which would push people into
 * `#if DEBUG`-style guards around their own startup code, exactly the thing the noop artifacts
 * exist to avoid.
 */
enum class StreamState { Disconnected, Connecting, Connected }

/**
 * Release-build stand-in for the real `ReplaySigner`.
 *
 * Declared here so an app can pass one at a call site that compiles in both configurations. It is
 * never invoked: the release build has no daemon connection to ask, which is the point — a signing
 * oracle must not exist in a shipped binary.
 *
 * Carries no Ktor types, so this costs a release build nothing.
 */
fun interface ReplaySigner {
    suspend fun headersFor(method: String, url: String): Map<String, String>
}

class StreamSink(
    private val client: ClientInfo,
    private val host: String = defaultDaemonHost(),
    private val port: Int = 8099,
    @Suppress("UNUSED_PARAMETER") signer: ReplaySigner? = null,
) : InspectorSink {

    private val _state = MutableStateFlow(StreamState.Disconnected)
    val state: StateFlow<StreamState> = _state.asStateFlow()

    private val _dropped = MutableStateFlow(0L)
    val dropped: StateFlow<Long> = _dropped.asStateFlow()

    fun start() = Unit
    fun stop() = Unit

    override fun onTransaction(txn: NetworkTransaction, reqBody: ByteArray?, resBody: ByteArray?) = Unit
    override fun onMarker(marker: Marker) = Unit
    override fun onSignal(signal: Signal, data: ByteArray?) = Unit
}

fun defaultDaemonHost(): String = "127.0.0.1"

fun defaultClientInfo(
    appId: String,
    appVersion: String,
    buildType: String = "debug",
): ClientInfo = ClientInfo(
    appId = appId,
    appVersion = appVersion,
    platform = Platforms.DESKTOP,
    device = "noop",
    osVersion = "noop",
    buildType = buildType,
)

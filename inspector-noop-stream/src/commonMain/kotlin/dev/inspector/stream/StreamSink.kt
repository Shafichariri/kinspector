package dev.inspector.stream

import dev.inspector.InspectorSink
import dev.inspector.model.ClientInfo
import dev.inspector.model.Marker
import dev.inspector.model.NetworkTransaction
import dev.inspector.model.Platforms
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

class StreamSink(
    private val client: ClientInfo,
    private val host: String = defaultDaemonHost(),
    private val port: Int = 8099,
) : InspectorSink {

    private val _state = MutableStateFlow(StreamState.Disconnected)
    val state: StateFlow<StreamState> = _state.asStateFlow()

    private val _dropped = MutableStateFlow(0L)
    val dropped: StateFlow<Long> = _dropped.asStateFlow()

    fun start() = Unit
    fun stop() = Unit

    override fun onTransaction(txn: NetworkTransaction, reqBody: ByteArray?, resBody: ByteArray?) = Unit
    override fun onMarker(marker: Marker) = Unit
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

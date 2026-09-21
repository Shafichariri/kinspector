// This file's NAME is part of the published ABI. Renaming it is a breaking change.
//
// `defaultDaemonHost` and `defaultClientInfo` are top-level, so they compile into a JVM facade
// named for this file: `StreamSinkKt`. `:inspector-stream` declares its actuals in
// `Platform.jvm.kt`/`Platform.android.kt` and so produced `Platform_jvmKt`/`Platform_androidKt`
// instead — identical Kotlin API, different JVM class, which breaks at runtime rather than at
// compile time. It now pins `@file:JvmName("StreamSinkKt")` to match this file. See the note
// there.
//
// This side cannot pin the same way, and that is a Kotlin limitation rather than an oversight:
// `kotlin.jvm.JvmName` does not resolve in a common source set shared with Native, so
// `@file:JvmName` here fails `compileKotlinIosArm64` with "Unresolved reference 'JvmName'".
// Splitting the declarations into a jvmMain file to carry the annotation would reintroduce the
// very split it is meant to close.
//
// So the guard carries it instead: `StreamSinkKt` is named in `ApiSurface.STREAM_CONTRACT_CLASSES`
// and this module's own `StreamApiParityTest` fails, with the reason, if renaming this file moves
// the facade. Do not rename it without reading that test.

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

    /**
     * Always null. The real sink reports why it could not reach the daemon; there is no daemon
     * connection here to fail, so there is never a reason to report.
     *
     * Declared because an app that surfaces the reason in its own debug screen must still
     * compile under `-Pinspector=off`, which is the whole contract of this module. The real
     * sink has had it since before v0.1.0 and this one never did, so every release so far
     * shipped the gap — see `StreamApiParityTest`, which is what now makes that visible.
     */
    val lastError: StateFlow<String?> = MutableStateFlow<String?>(null).asStateFlow()

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

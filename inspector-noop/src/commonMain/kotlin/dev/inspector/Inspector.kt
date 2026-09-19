package dev.inspector

import dev.inspector.model.Marker
import dev.inspector.model.NetworkTransaction
import dev.inspector.model.Signal
import dev.inspector.model.SignalKey
import kotlinx.serialization.json.JsonElement
import io.ktor.client.HttpClientConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Release-build stand-in for `:inspector-core`.
 *
 * Same public surface, no capture, no canary. Swapping this in via `-Pinspector=off` leaves the
 * consuming app compiling unchanged while removing every trace of the inspector from the binary
 * — which is the point: production safety is a build-graph property here, not a runtime flag
 * someone can forget to set.
 *
 * The surface is verified against `api/inspector-public-api.txt` by `ApiParityTest`, the same
 * golden file `:inspector-core` is checked against. The two therefore cannot drift apart.
 */
object Inspector {

    /**
     * Deliberately not the core canary value. `scripts/check-release-clean.sh` greps for the
     * core string; finding it would mean capture code shipped.
     */
    @Suppress("MemberVisibilityCanBePrivate")
    var canary: String = "INSPECTOR_NOOP"
        internal set

    private val _transactions = MutableStateFlow<List<NetworkTransaction>>(emptyList())
    private val _latest = MutableStateFlow<NetworkTransaction?>(null)
    private val _markers = MutableStateFlow<List<Marker>>(emptyList())
    private val _signals = MutableStateFlow<List<Signal>>(emptyList())

    val transactions: StateFlow<List<NetworkTransaction>> = _transactions.asStateFlow()
    val latest: StateFlow<NetworkTransaction?> = _latest.asStateFlow()
    val markers: StateFlow<List<Marker>> = _markers.asStateFlow()
    val signals: StateFlow<List<Signal>> = _signals.asStateFlow()

    fun init(config: InspectorConfig = InspectorConfig()) = Unit

    fun install(config: HttpClientConfig<*>) = Unit

    fun mark(label: String) = Unit

    fun signal(tag: String, name: String, data: JsonElement? = null) = Unit

    fun signal(tag: String, name: String, text: String) = Unit

    /**
     * Discards [provider] rather than storing it. Deliberate, and the one thing here that is not
     * merely "does nothing": a retained lambda holds a reference to whatever it closes over — a
     * cache, a repository, an object graph — for the life of the process. That is how a no-op
     * stops being free, and no signature check would catch it.
     */
    @Suppress("UNUSED_PARAMETER")
    fun registerProvider(tag: String, name: String, provider: suspend () -> JsonElement?) = Unit

    fun unregisterProvider(tag: String, name: String) = Unit

    /**
     * Always an error, because this build captures nothing and registers nothing. Naming the
     * build is what stops a host operator reading the empty answer as a broken app.
     */
    suspend fun answerSignalRequest(tag: String, name: String, requestId: String): String? =
        "this build has no capture code; signals are not recorded"

    /**
     * Always empty. `registerProvider` above discards what it is given without storing it, so
     * there is no registry to report — and reporting one would be worse than reporting none: a
     * caller offering a pull control from this list would offer a control that cannot work.
     */
    fun signalProviders(): List<SignalKey> = emptyList()

    /**
     * Always an error, for the same reason [answerSignalRequest] is. A local pull has even less to
     * work with than a host one: there is no provider, because none was ever kept.
     */
    suspend fun pullSignal(tag: String, name: String): String? =
        "this build has no capture code; signals are not recorded"

    fun requestBody(txn: NetworkTransaction): ByteArray? = null

    fun responseBody(txn: NetworkTransaction): ByteArray? = null

    fun addSink(sink: InspectorSink) = Unit

    fun clear() = Unit
}

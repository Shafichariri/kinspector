package dev.inspector

import dev.inspector.model.Marker
import dev.inspector.model.NetworkTransaction
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

    val transactions: StateFlow<List<NetworkTransaction>> = _transactions.asStateFlow()
    val latest: StateFlow<NetworkTransaction?> = _latest.asStateFlow()
    val markers: StateFlow<List<Marker>> = _markers.asStateFlow()

    fun init(config: InspectorConfig = InspectorConfig()) = Unit

    fun install(config: HttpClientConfig<*>) = Unit

    fun mark(label: String) = Unit

    fun requestBody(txn: NetworkTransaction): ByteArray? = null

    fun responseBody(txn: NetworkTransaction): ByteArray? = null

    fun addSink(sink: InspectorSink) = Unit

    fun clear() = Unit
}

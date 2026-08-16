package dev.inspector

import dev.inspector.internal.installInspector
import dev.inspector.model.Marker
import dev.inspector.model.NetworkTransaction
import io.ktor.client.HttpClientConfig
import kotlinx.coroutines.flow.StateFlow

/**
 * Capture entry point.
 *
 * A thin facade over a single [Recorder]; the recorder itself is instantiable so tests get
 * isolation rather than fighting global state.
 *
 * This object's public surface is a contract shared with `:inspector-noop`, which ships the
 * identical API doing nothing so release builds compile unchanged. Any change here must be
 * mirrored there and in `api/inspector-public-api.txt`, which both modules assert against —
 * see `ApiParityTest`.
 *
 * Typical wiring in a consuming app:
 * ```
 * Inspector.init()
 * val client = HttpClient(engine) {
 *     Inspector.install(this)
 * }
 * ```
 */
object Inspector {

    /**
     * Present so `scripts/check-release-clean.sh` can prove this module is absent from release
     * binaries. Held in a public mutable field rather than a bare `const` so neither R8 nor the
     * Kotlin/Native linker can eliminate it — a canary that gets optimised away would make the
     * guard pass for the wrong reason.
     */
    @Suppress("MemberVisibilityCanBePrivate")
    var canary: String = INSPECTOR_CANARY
        internal set

    // One recorder for the process lifetime. Never replaced, so a UI collecting these flows
    // stays attached across any number of init() calls.
    internal val recorder: Recorder = Recorder()

    /** Ring buffer contents, newest first. Backs the in-app inspector list. */
    val transactions: StateFlow<List<NetworkTransaction>> = recorder.transactions

    /** Most recent transaction. Backs the overlay pill. */
    val latest: StateFlow<NetworkTransaction?> = recorder.latest

    /** Session markers, oldest first. */
    val markers: StateFlow<List<Marker>> = recorder.markers

    /**
     * Idempotent: calling it again simply re-applies [config], and the underlying recorder is
     * never replaced, so flows already being collected stay live. Redaction and body caps take
     * effect on the next request; the ring buffer is retuned immediately.
     */
    fun init(config: InspectorConfig = InspectorConfig()) {
        canary = INSPECTOR_CANARY
        recorder.configure(config)
    }

    /**
     * Installs capture on a client. Call inside the `HttpClient { }` configuration block.
     *
     * Only traffic on clients configured through this call is captured — the inspector sees
     * Ktor, and nothing else in the process.
     */
    fun install(config: HttpClientConfig<*>) {
        config.installInspector(recorder)
    }

    /** Drops a labelled point into the session timeline. Backs the `since:marker(...)` filter. */
    fun mark(label: String) {
        recorder.mark(label)
    }

    /**
     * Captured request body for [txn], or null when it was not captured — either the content type
     * was outside the allowlist or the body was streamed. Check [NetworkTransaction.reqBytes] to
     * distinguish "no body" from "body not captured".
     */
    fun requestBody(txn: NetworkTransaction): ByteArray? = recorder.bodies.value[txn.id]?.first

    /** Captured response body for [txn]. See [requestBody] for when this is null. */
    fun responseBody(txn: NetworkTransaction): ByteArray? = recorder.bodies.value[txn.id]?.second

    /** Registers an additional destination for captured transactions. */
    fun addSink(sink: InspectorSink) {
        recorder.addSink(sink)
    }

    /** Empties the ring buffer. Does not affect anything already sent to a sink. */
    fun clear() {
        recorder.clear()
    }
}

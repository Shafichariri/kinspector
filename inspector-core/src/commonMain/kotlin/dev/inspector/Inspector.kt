package dev.inspector

import dev.inspector.internal.installInspector
import dev.inspector.model.Marker
import dev.inspector.model.NetworkTransaction
import dev.inspector.model.Signal
import dev.inspector.model.SignalKey
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
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

    /** Signal ring contents, newest first. */
    val signals: StateFlow<List<Signal>> = recorder.signals

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
     * Records an app-defined observation on the same timeline as captured traffic.
     *
     * [tag] and [name] are free strings — Inspector never learns what they mean. Conventional
     * tags are in [dev.inspector.model.SignalTags]; anything else works identically.
     *
     * Emit freely: throttling is the library's job, not the caller's. Values for one
     * `(tag, name)` are conflated on the capture worker per [SignalPolicy], keeping the last value
     * of a burst rather than the first. This call itself only offers the observation to a bounded
     * queue, so a slow or absent host costs dropped signals, never backpressure into the app.
     */
    fun signal(tag: String, name: String, data: JsonElement? = null) {
        recorder.signal(tag, name, data)
    }

    /**
     * Records an observation whose payload is plain text.
     *
     * The common case is a `toString()` of a state holder: a Kotlin/Native target has no runtime
     * reflection, so serializing arbitrary app state is not free. The text is stored as a JSON
     * string, so there is one payload type on disk rather than two.
     */
    fun signal(tag: String, name: String, text: String) {
        recorder.signal(tag, name, JsonPrimitive(text))
    }

    /**
     * Registers an answer the host can pull on demand, for one `(tag, name)`.
     *
     * A push records what was true at a moment; a provider answers "what is in there *now*". The
     * two are distinguishable in the archive by `trigger`, which every consumer must surface —
     * reporting an app-start snapshot as live state is the failure this whole distinction exists
     * to prevent.
     *
     * [provider] is called off the main thread and may suspend. Throwing is reported to the host
     * as a failed pull rather than swallowed, because a pull that silently returns nothing looks
     * exactly like an app that has gone away.
     *
     * Debug builds only, like everything else here: `:inspector-noop` discards [provider] without
     * storing it, so a release build retains no reference to whatever it closes over.
     */
    fun registerProvider(tag: String, name: String, provider: suspend () -> JsonElement?) {
        recorder.registerProvider(tag, name, provider)
    }

    /** Removes a provider registered by [registerProvider]. Unknown pairs are ignored. */
    fun unregisterProvider(tag: String, name: String) {
        recorder.unregisterProvider(tag, name)
    }

    /**
     * Reads the provider for `(tag, name)` and records its answer with `trigger = request`.
     *
     * **Wiring for `:inspector-stream`, not for apps.** It is public only because `internal` is
     * module-scoped and the stream module is where the host's request arrives. The registry lives
     * here rather than being handed to `StreamSink` at construction, the way `ReplaySigner` is,
     * because providers are registered and removed at runtime as caches and repositories come and
     * go — a constructor parameter cannot express that.
     *
     * Returns null when the answer was recorded, or a message naming what *is* registered. The
     * error is a reply, never a row: a failed pull must leave nothing in the archive.
     */
    suspend fun answerSignalRequest(tag: String, name: String, requestId: String): String? =
        recorder.answerProviderRequest(tag, name, requestId)

    /**
     * The `(tag, name)` pairs an app has registered a provider for.
     *
     * Exists because the **overlay can ask and the host cannot**. A daemon learns a provider's name
     * only once one has answered, so the web UI infers the set from what a session already holds —
     * conservatively, and it says so in `app.js`. In-process there is nothing to infer: this is the
     * registry.
     *
     * Two things follow that the host can never do. A pull is offered exactly where one will work,
     * rather than where one probably will; and a provider that has **never answered** is still
     * findable, where host-side it is invisible until the first time it does.
     *
     * A snapshot, not a flow. Providers come and go as caches and repositories are built, so any
     * answer is already historical by the time it is read — a caller offering a control from it
     * must cope with the pull failing, which [pullSignal] reports rather than throws.
     */
    fun signalProviders(): List<SignalKey> = recorder.providerKeys()

    /**
     * Reads the provider for `(tag, name)` **now** and records the answer with `trigger = request`.
     *
     * For an in-process caller — the overlay — as opposed to [answerSignalRequest], which answers a
     * host that asked over a socket. The difference is not cosmetic: a local pull correlates to no
     * [dev.inspector.model.SignalRequest], so the recorded row carries a null `requestId` rather
     * than an invented one pointing at nothing.
     *
     * Returns null when the answer was recorded, or a message naming what *is* registered. As with
     * a host pull, a failure is a reply and never a row: nothing reaches the ring buffer.
     */
    suspend fun pullSignal(tag: String, name: String): String? =
        recorder.answerProviderRequest(tag, name, requestId = null)

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

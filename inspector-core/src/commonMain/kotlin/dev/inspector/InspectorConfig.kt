package dev.inspector

import dev.inspector.model.Marker
import dev.inspector.model.NetworkTransaction
import dev.inspector.model.Signal

/**
 * Capture tuning.
 *
 * Defaults are chosen so that the inspector cannot meaningfully burden the host app: bodies are
 * capped, non-text content types are counted rather than buffered, and the ring buffer is
 * byte-budgeted. See the efficiency contract in the plan.
 */
data class InspectorConfig(
    /** Total bytes the on-device ring buffer may hold before evicting oldest. */
    val ringBufferMaxBytes: Long = 8L * 1024 * 1024,
    /**
     * Per-body capture ceiling. Beyond this the tee switches to counting bytes instead of
     * buffering them, so a large download costs no memory.
     */
    val bodyCaptureMaxBytes: Int = 256 * 1024,
    /**
     * Content types whose bodies are captured. Anything else contributes a byte count only.
     *
     * Matching is by prefix, so `text/` covers `text/plain` and `text/html`, and additionally by
     * structured-syntax suffix, so `application/vnd.api+json` and `application/hal+json` need no
     * entry here.
     */
    val captureContentTypes: List<String> = listOf(
        "application/json",
        "text/",
        "application/xml",
        "application/x-www-form-urlencoded",
        "application/problem+json",
        "application/graphql",
        "application/x-ndjson",
        "application/javascript",
        "application/jwt",
    ),
    /**
     * Capture every body regardless of content type, up to [bodyCaptureMaxBytes].
     *
     * Off by default: the allowlist is what stops an image-heavy or file-downloading app from
     * paying to have its payloads buffered and shipped to the host. Turn it on when you would
     * rather see a body you did not anticipate than have it silently counted — the same "show me
     * everything" stance as [Redaction.Off], with the same caveat that it all lands in the host
     * archive. Bodies that are not valid UTF-8 still round trip; they travel base64-encoded.
     */
    val captureAllBodies: Boolean = false,
    /**
     * What, if anything, to strip before a transaction reaches any sink.
     *
     * Defaults to [Redaction.Off] — full fidelity, credentials included. This is a debugging
     * tool for debuggable builds whose capture code cannot reach production (see the canary
     * guard), and a debugger that hides the auth header is worse than useless when the bug *is*
     * the auth header.
     *
     * The tradeoff to be aware of: captured traffic is archived to disk on the host and is
     * readable by any agent pointed at it. If a particular session should not carry secrets,
     * use [Redaction.On] for that run.
     */
    val redaction: Redaction = Redaction.Off,
    /**
     * Conflation and budget for [dev.inspector.model.Signal]s. Appended last, so every existing
     * positional `InspectorConfig(...)` call site keeps compiling.
     */
    val signals: SignalPolicy = SignalPolicy(),
)

/**
 * How signals are throttled on the device and how much memory they may hold.
 *
 * Conflation lives here — in the library — rather than in app code, because an app emitting one
 * `state` signal per keystroke across dozens of state holders will saturate the wire and the
 * archive, and every consumer that reinvents the throttle gets it wrong the same way.
 */
data class SignalPolicy(
    /**
     * Minimum gap between emissions for one `(tag, name)`. Within the window the newest value is
     * held and emitted when the window closes — **trailing** edge, because in a rapid sequence of
     * state changes the one worth keeping is the last, where the state settled.
     *
     * A guess, not a measurement. Revisit against a real session before recommending it.
     */
    val minIntervalMs: Long = 150,
    /** Drop a signal whose encoded payload is byte-identical to the last one emitted for its key. */
    val dropUnchanged: Boolean = true,
    /**
     * Cap on one encoded payload. Beyond it the payload is cut and `dataTruncated` is set, while
     * `bytes` still reports the true size.
     */
    val maxPayloadBytes: Int = 64 * 1024,
    /**
     * Budget for the signal ring, kept **separate** from [InspectorConfig.ringBufferMaxBytes] so a
     * single large cache snapshot cannot evict the network history beside it.
     *
     * A guess, not a measurement. Revisit against a real session before recommending it.
     */
    val ringBufferMaxBytes: Long = 2L * 1024 * 1024,
)

/**
 * Redaction policy, applied at capture time — before the transaction reaches the ring buffer,
 * the daemon, or disk. There is no render-time redaction: whatever a sink receives is what was
 * captured.
 */
sealed interface Redaction {

    /** Capture everything verbatim. Headers, query parameters and bodies are untouched. */
    data object Off : Redaction

    /**
     * Strip values matching the configured denylists, recording each removal in
     * [NetworkTransaction.redacted] so consumers can say "this was redacted" rather than
     * "this was absent".
     *
     * Patterns are always matched case-insensitively; do not add an inline `(?i)`.
     */
    data class On(
        /** Header names whose values are replaced. Compared case-insensitively, exact match. */
        val headers: List<String> = DEFAULT_HEADERS,
        /** JSON body keys whose values are replaced. Applied leniently; never fails capture. */
        val bodyKeyPattern: String = DEFAULT_BODY_KEY_PATTERN,
        /** Query parameter names whose values are replaced. */
        val queryKeyPattern: String = DEFAULT_QUERY_KEY_PATTERN,
    ) : Redaction {
        // Held outside the constructor so they stay out of equals/hashCode — Regex compares by
        // identity, which would make two configs with identical patterns unequal.
        val bodyKeys: Regex by lazy { Regex(bodyKeyPattern, RegexOption.IGNORE_CASE) }
        val queryKeys: Regex by lazy { Regex(queryKeyPattern, RegexOption.IGNORE_CASE) }
    }

    companion object {
        val DEFAULT_HEADERS: List<String> = listOf(
            "authorization",
            "cookie",
            "set-cookie",
            "x-api-key",
            "proxy-authorization",
        )

        const val DEFAULT_BODY_KEY_PATTERN: String =
            "(token|secret|password|passwd|api[-_]?key|authorization|bearer|session[-_]?id)"

        const val DEFAULT_QUERY_KEY_PATTERN: String = "(token|key|secret|password)"

        /** Replacement written in place of a redacted value. */
        const val PLACEHOLDER: String = "‹redacted›"
    }
}

/**
 * A destination for captured traffic.
 *
 * Implemented by the in-app store, `:inspector-stream`'s daemon sink, and tests. Sinks are
 * called on the capture worker thread, never on the caller's coroutine, and a sink that throws
 * is logged and skipped rather than allowed to reach the app.
 *
 * Bodies arrive alongside the transaction rather than inside it because on-device they are raw
 * bytes; only the daemon assigns them the `bodies/…` paths that populate
 * [NetworkTransaction.reqBodyRef].
 */
interface InspectorSink {
    fun onTransaction(txn: NetworkTransaction, reqBody: ByteArray?, resBody: ByteArray?)
    fun onMarker(marker: Marker)

    /**
     * One observation, with its encoded payload.
     *
     * Defaulted so every sink written before signals existed — including anything a consumer
     * wrote — compiles untouched. [data] is the payload as it should travel; [Signal.data] carries
     * the same content in memory, and a sink bound for the wire must null it out rather than send
     * both.
     */
    fun onSignal(signal: Signal, data: ByteArray?) = Unit
}

/**
 * Canary string proving `:inspector-core` linked into a binary.
 *
 * `scripts/check-release-clean.sh` greps release artifacts for this value; a hit means capture
 * code shipped. `:inspector-noop` must never contain it.
 */
internal const val INSPECTOR_CANARY: String = "INSPECTOR_CANARY_7f3a9b_DO_NOT_SHIP"

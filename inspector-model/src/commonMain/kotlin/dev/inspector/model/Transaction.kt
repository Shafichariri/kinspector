package dev.inspector.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * One network attempt.
 *
 * A logical call that redirects or retries produces several transactions sharing [callId],
 * distinguished by [attempt]. This is deliberate: the bugs worth catching usually live in the
 * attempts a "one row per call" view would hide.
 *
 * Ordering authority is [mono] (device monotonic clock), never [ts] — see the clock-skew note
 * in the plan. [ts] is for display only.
 *
 * All header, query and body content here is already redacted; see [redacted] for what was
 * removed. Capture-time redaction is the only redaction there is.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class NetworkTransaction(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val v: Int = SCHEMA_VERSION,
    /** 8-char lowercase hex, unique within a session. */
    val id: String,
    /** ISO-8601 UTC with millis, device wall clock at request start. Display only. */
    val ts: String,
    /** Device monotonic milliseconds at request start. Sort by this. */
    val mono: Long,
    /** Uppercase HTTP method. */
    val method: String,
    val scheme: String,
    val host: String,
    /**
     * The port, when it is not the default for [scheme].
     *
     * Null means "the default", which keeps every archive written before this field existed
     * readable. It is separate from [host] so `host:` filters keep matching the hostname alone.
     *
     * Not cosmetic: without it a capture of `127.0.0.1:8080` rendered as `http://127.0.0.1`, and
     * anything built from that — the copied cURL, a replay — silently addressed port 80.
     */
    val port: Int? = null,
    /** Path only, no query string. */
    val path: String,
    /** Raw query string without the leading `?`, post-redaction. */
    val query: String? = null,
    /** Null means the call failed before a response was received; see [error]. */
    val status: Int? = null,
    /** Exception class and message when [status] is null. */
    val error: String? = null,
    /** Total duration in ms; null while still in flight. */
    val ms: Long? = null,
    /** 1-based. Redirects and retries increment this. */
    val attempt: Int = 1,
    /** Groups all attempts belonging to one logical call. */
    val callId: String,
    /** True body size in bytes, counted even when the body was not captured. */
    val reqBytes: Long = 0,
    val resBytes: Long = 0,
    val reqHeaders: Map<String, List<String>> = emptyMap(),
    val resHeaders: Map<String, List<String>> = emptyMap(),
    /** Host-relative body path, e.g. `bodies/7f3a.req`. Null when no body was captured. */
    val reqBodyRef: String? = null,
    val resBodyRef: String? = null,
    val reqBodyTruncated: Boolean = false,
    val resBodyTruncated: Boolean = false,
    val reqContentType: String? = null,
    val resContentType: String? = null,
    /**
     * Why a body is absent, when it is. Null means nothing was withheld — either the body was
     * captured or there was none to capture.
     *
     * Recorded because a viewer cannot infer the reason from a missing body, and guessing it
     * produces confident wrong answers: the first version of the UI told users their JSON was
     * "outside the capture allowlist" when it had in fact been captured and written to disk.
     */
    val reqBodyOmitted: String? = null,
    val resBodyOmitted: String? = null,
    /**
     * What redaction removed, e.g. `header:authorization`, `query:token`, `body:$.password`.
     * Present so consumers can say "this was redacted" rather than "this was absent" — an agent
     * told nothing will otherwise report the request carried no credentials.
     */
    val redacted: List<String> = emptyList(),
) {
    /** True when this attempt failed outright or returned a 4xx/5xx. Matches `has:error`. */
    val isError: Boolean get() = error != null || (status ?: 0) >= 400

    /** `2xx`, `4xx`, … or `error` for transport failures. Used by session summaries. */
    val statusClass: String
        get() = when (val s = status) {
            null -> "error"
            else -> "${s / 100}xx"
        }

    val url: String
        get() = buildString {
            append(scheme).append("://").append(host)
            port?.takeIf { it != defaultPortFor(scheme) }?.let { append(':').append(it) }
            append(path)
            query?.let { append('?').append(it) }
        }
}

/** 80 for http, 443 for https, null for anything else — where "null" means "always show it". */
fun defaultPortFor(scheme: String): Int? = when (scheme.lowercase()) {
    "http", "ws" -> 80
    "https", "wss" -> 443
    else -> null
}

/**
 * A named point in the session timeline, dropped by app code, the user, or an agent.
 *
 * Markers are what make "what happened when I tapped checkout" a lookup instead of timestamp
 * arithmetic, and they back the `since:marker("…")` filter term.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Marker(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val v: Int = SCHEMA_VERSION,
    val ts: String,
    val mono: Long,
    val label: String,
    /** `app`, `agent`, or `user`. */
    val source: String,
)

/**
 * [NetworkTransaction.reqBodyOmitted] / [NetworkTransaction.resBodyOmitted] values.
 *
 * Strings rather than an enum for the same reason [MarkerSource] is: the archive outlives the
 * binary that wrote it, and a reader from an older build must be able to read a session
 * containing a reason it has never heard of rather than fail to decode the row.
 */
object BodyOmission {
    /** The content type was not on [dev.inspector.InspectorConfig.captureContentTypes]. */
    const val CONTENT_TYPE = "contentType"

    /**
     * The body was streamed, not held in memory. Buffering it in order to inspect it is exactly
     * the cost the efficiency contract forbids, so its size is reported and its bytes are not.
     */
    const val STREAMING = "streaming"

    /**
     * The call was abandoned before its body was read — normally a redirect hop or a retried
     * attempt that the client discarded, body unread, in order to make the next one.
     *
     * The row is real: status, timing and headers all describe a response that happened. Only the
     * body was never available to capture. Keeping this distinct from "the body was empty" matters,
     * because the hop may well have carried one, and calling it empty would be exactly the kind of
     * guess this field exists to replace.
     */
    const val DISCARDED = "discarded"
}

/** Marker [Marker.source] values. */
object MarkerSource {
    const val APP = "app"
    const val AGENT = "agent"
    const val USER = "user"
}

/**
 * Session-level metadata, written to `meta.json` in the session folder.
 *
 * Kept deliberately flat rather than nesting client info, because this file is meant to be
 * opened and read by a human in a folder listing.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class SessionMeta(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val v: Int = SCHEMA_VERSION,
    /** Daemon-assigned; also the session folder name. */
    val sessionId: String,
    val appId: String,
    val appVersion: String,
    /** See [Platforms]. */
    val platform: String,
    val device: String,
    val osVersion: String,
    val buildType: String,
    val startedAt: String,
    val endedAt: String? = null,
    val txnCount: Int = 0,
    /** Transactions where [NetworkTransaction.isError] held. */
    val errorCount: Int = 0,
)

/**
 * Known [SessionMeta.platform] values. Deliberately a plain string on the wire so that adding
 * physical devices later does not break older daemons.
 */
object Platforms {
    const val IOS_SIMULATOR = "ios-simulator"
    const val ANDROID_EMULATOR = "android-emulator"
    const val DESKTOP = "desktop"
}

/**
 * Everything the device knows about itself at connect time. The daemon combines this with an
 * assigned session id and running counts to produce [SessionMeta].
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ClientInfo(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val v: Int = SCHEMA_VERSION,
    val appId: String,
    val appVersion: String,
    val platform: String,
    val device: String,
    val osVersion: String,
    val buildType: String,
) {
    fun toSessionMeta(sessionId: String, startedAt: String): SessionMeta = SessionMeta(
        sessionId = sessionId,
        appId = appId,
        appVersion = appVersion,
        platform = platform,
        device = device,
        osVersion = osVersion,
        buildType = buildType,
        startedAt = startedAt,
    )
}

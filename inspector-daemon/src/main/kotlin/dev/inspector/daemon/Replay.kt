package dev.inspector.daemon

import dev.inspector.model.BodyOmission
import dev.inspector.model.NetworkTransaction
import kotlinx.serialization.Serializable

/** What the caller asks to replay, plus any edits applied on top of the capture. */
@Serializable
data class ReplayRequest(
    val txnId: String,
    val session: String = "latest",
    /** Edits. Null means "as captured". */
    val method: String? = null,
    val url: String? = null,
    /** Replaces the captured header set entirely when present. */
    val headers: Map<String, String>? = null,
    val body: String? = null,
    /** Ask the running app to regenerate per-request headers. */
    val resign: Boolean = true,
)

@Serializable
data class ReplayResult(
    val ok: Boolean,
    val status: Int? = null,
    val ms: Long? = null,
    val resHeaders: Map<String, List<String>> = emptyMap(),
    val body: String? = null,
    val bodyTruncated: Boolean = false,
    /** Headers the app regenerated, so the UI can show what actually went out. */
    val resignedHeaders: List<String> = emptyList(),
    val error: String? = null,
    /** A guess at *why*, from the triage table in `docs/REPLAY.md`. Never presented as certain. */
    val diagnosis: String? = null,
)

/** Refusal to attempt a replay at all, because the capture cannot faithfully produce one. */
class ReplayRefusedException(message: String) : Exception(message)

/**
 * Headers that describe *this* connection rather than the request, and so must never be copied
 * from a capture onto a new one.
 *
 * Replaying `Content-Length` from a capture whose body was then edited sends a length that
 * contradicts the payload; replaying `Host` pins the request to the captured host even after the
 * URL is edited; replaying `Accept-Encoding` invites a compressed response the client did not
 * negotiate. These are the classic replay bugs, and they surface as intermittent server errors
 * that look like anything but a header problem.
 */
internal val HOP_BY_HOP_HEADERS = setOf(
    "host",
    "content-length",
    "connection",
    "keep-alive",
    "transfer-encoding",
    "te",
    "trailer",
    "upgrade",
    "proxy-authorization",
    "proxy-authenticate",
    "accept-encoding",
)

/**
 * Decides whether a captured transaction can be replayed faithfully, and refuses when it cannot.
 *
 * Refusing is the point. Every one of these cases could be papered over — send the truncated
 * prefix, send an empty body, send `***` as a bearer token — and each would produce a server-side
 * failure that looks like an application bug rather than a replay that was never valid. A tool
 * that cannot reproduce the request must say so.
 */
internal fun refuseIfUnreplayable(txn: NetworkTransaction, bodyOverridden: Boolean) {
    if (txn.redacted.isNotEmpty()) {
        throw ReplayRefusedException(
            "this capture was redacted (${txn.redacted.joinToString(", ")}), so the real values " +
                "are gone — replaying would send the masks. Re-capture with Redaction.Off.",
        )
    }

    // An edited body replaces the captured one, so a defect in the capture no longer matters.
    if (bodyOverridden) return

    if (txn.reqBodyTruncated) {
        throw ReplayRefusedException(
            "the captured request body was truncated at the capture cap (${txn.reqBytes} bytes " +
                "total), so only a prefix exists — replaying it would send a corrupt body. Edit " +
                "the body to supply it in full, or raise bodyCaptureMaxBytes and re-capture.",
        )
    }

    if (txn.reqBodyOmitted == BodyOmission.STREAMING) {
        throw ReplayRefusedException(
            "the request body was streamed and never buffered, by design — there is nothing " +
                "captured to replay. Edit the body to supply it.",
        )
    }

    if (txn.reqBodyOmitted == BodyOmission.CONTENT_TYPE && txn.reqBytes > 0) {
        throw ReplayRefusedException(
            "the request body was not captured because its content type is outside the capture " +
                "allowlist. Edit the body to supply it, or enable captureAllBodies and re-capture.",
        )
    }
}

/**
 * Flattens captured headers and drops the ones that describe the old connection.
 *
 * Capture stores multi-valued headers; a replay sends one value per name, joined the way HTTP
 * itself would.
 */
internal fun replayableHeaders(captured: Map<String, List<String>>): Map<String, String> =
    captured
        .filterKeys { it.lowercase() !in HOP_BY_HOP_HEADERS }
        .mapValues { (_, values) -> values.joinToString(", ") }

/**
 * Names a likely cause for a failed replay.
 *
 * Explicitly a guess. The single most common replay failure is a captured token that has simply
 * expired, which has nothing to do with signing — and left unnamed it sends people debugging the
 * signature instead. Sourced from the triage table in `docs/REPLAY.md`.
 */
internal fun diagnose(status: Int?, body: String?, resigned: Boolean): String? {
    if (status == null) return null
    if (status !in 400..499) return null

    val text = body?.lowercase().orEmpty()
    val tokenShaped = listOf("token", "expired", "jwt", "unauthorized_client", "invalid_grant")
        .any { it in text }
    val signatureShaped = listOf("signature", "nonce", "timestamp", "device", "skew")
        .any { it in text }

    return when {
        status == 401 && tokenShaped ->
            "The captured Authorization token has most likely expired. This is not a signing " +
                "failure — re-capture to get a fresh token."
        status == 401 && signatureShaped && !resigned ->
            "Per-request headers were not regenerated, so the captured timestamp and nonce were " +
                "replayed as-is. Enable re-signing."
        status == 401 && signatureShaped ->
            "The signature was regenerated but rejected. Check that the device id in this capture " +
                "matches the device now attached — a regenerated device key makes older captures " +
                "permanently unreplayable."
        status == 401 && !resigned ->
            "Not re-signed. If this API signs requests, the captured headers are single-use."
        else -> null
    }
}

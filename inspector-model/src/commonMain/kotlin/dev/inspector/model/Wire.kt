package dev.inspector.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Ingest protocol, sent as JSON text frames over `WS /ingest`.
 *
 * Mostly device → daemon, but not exclusively: [HelloAck], [SignRequest] and [SignalRequest]
 * travel the other way.
 * That the socket is genuinely bidirectional is what makes host-driven replay of a signed request
 * possible at all — see `docs/REPLAY.md`.
 * The `type` discriminator is configured on [InspectorJson].
 *
 * Contract:
 * - The device never blocks waiting on the daemon. After [Hello]/[HelloAck] everything is
 *   fire-and-forget; a dead or slow daemon must degrade to dropped transactions, never to
 *   backpressure reaching the app.
 * - Bodies ride inline on [Txn] rather than being written to device storage. The daemon writes
 *   them to `bodies/` and rewrites the `*BodyRef` fields. This keeps the device free of file I/O.
 * - Transactions recorded while disconnected are NOT replayed on reconnect in v1. They stay
 *   visible in the device ring buffer only.
 */
@Serializable
sealed interface WireMsg

/** First frame after connect. [resumeSessionId] asks to continue an existing session folder. */
@Serializable
@SerialName("hello")
data class Hello(
    val client: ClientInfo,
    val resumeSessionId: String? = null,
) : WireMsg

/**
 * Daemon's reply to [Hello]. [resumed] is true when [Hello.resumeSessionId] was accepted, which
 * only happens within [SESSION_RESUME_GRACE_MS] of the previous connection closing.
 */
@Serializable
@SerialName("helloAck")
data class HelloAck(
    val sessionId: String,
    val resumed: Boolean = false,
) : WireMsg

/**
 * One captured attempt, with its bodies inline.
 *
 * Bodies are UTF-8 strings when the captured bytes are valid UTF-8, otherwise base64 with the
 * corresponding `*B64` flag set. [NetworkTransaction.reqBodyRef] / `resBodyRef` are null on the
 * wire and filled in by the daemon once the body is on disk.
 */
@Serializable
@SerialName("txn")
data class Txn(
    val txn: NetworkTransaction,
    val reqBody: String? = null,
    val resBody: String? = null,
    val reqBodyB64: Boolean = false,
    val resBodyB64: Boolean = false,
) : WireMsg

/** A timeline marker. */
@Serializable
@SerialName("marker")
data class MarkerMsg(
    val marker: Marker,
) : WireMsg

/**
 * One app-defined observation, with its payload inline.
 *
 * Deliberately shaped exactly like [Txn]: the payload rides as a string rather than on the row, so
 * the device never touches the filesystem and the daemon is the only thing that assigns
 * [Signal.dataRef]. [Signal.data] is therefore null on the wire — carrying it in both places would
 * ship every payload twice.
 *
 * [data] is UTF-8 when the captured payload is valid UTF-8, otherwise base64 with [dataB64] set.
 */
@Serializable
@SerialName("signal")
data class SignalMsg(
    val signal: Signal,
    val data: String? = null,
    val dataB64: Boolean = false,
) : WireMsg

/**
 * Daemon → device: read a registered provider and report what it returns now.
 *
 * [name] is required, so one request yields exactly one reply and there is no completion ambiguity
 * to resolve. There is no provider advertisement in v1; discovery is the error path — see
 * [SignalError].
 */
@Serializable
@SerialName("signalReq")
data class SignalRequest(
    val requestId: String,
    val tag: String,
    val name: String,
) : WireMsg

/**
 * Device → daemon: [SignalRequest] could not be answered, correlated by `requestId`.
 *
 * [error] must name what *is* registered, e.g.
 * `no provider for cache/orders; registered: cache/response, cache/prefs`. That makes the error
 * self-documenting and costs one frame rather than a second discovery mechanism.
 *
 * Errors are replies, never archived rows: a failed pull must leave no [Signal] in the archive.
 */
@Serializable
@SerialName("signalErr")
data class SignalError(
    val requestId: String,
    val error: String,
) : WireMsg

/**
 * Daemon → device: produce fresh per-request headers for a request about to be replayed.
 *
 * Deliberately says *what request*, not *what bytes to sign*. The app owns its signing scheme and
 * already has the code that builds these headers; Inspector never learns the canonical-string
 * format, the algorithm, or which headers are involved. That keeps replay working for any scheme
 * rather than only the one it was written against, and it keeps a signing oracle out of this
 * codebase.
 *
 * [url] is the full URL as it will be sent, so a scheme that signs the path can take the path from
 * it. Headers that are unchanged from the capture are not mentioned here; the daemon merges what
 * comes back over the captured set, so the reply need only carry what must be fresh.
 */
@Serializable
@SerialName("signReq")
data class SignRequest(
    val requestId: String,
    val method: String,
    val url: String,
) : WireMsg

/**
 * Device → daemon: the reply to [SignRequest], correlated by [requestId].
 *
 * [error] set means the app could not produce headers — no signer registered, or the signer threw.
 * Reported rather than swallowed, because a replay that silently goes out unsigned fails at the
 * server as something that looks unrelated.
 */
@Serializable
@SerialName("signRes")
data class SignResponse(
    val requestId: String,
    val headers: Map<String, String> = emptyMap(),
    val error: String? = null,
) : WireMsg

/** Clean shutdown. Best-effort — the daemon must handle an abrupt socket close identically. */
@Serializable
@SerialName("bye")
data object Bye : WireMsg

/**
 * How long after a disconnect the daemon will still accept a `resumeSessionId` and keep
 * appending to the same session folder. Beyond this, a reconnect starts a new session.
 */
const val SESSION_RESUME_GRACE_MS: Long = 5 * 60 * 1000

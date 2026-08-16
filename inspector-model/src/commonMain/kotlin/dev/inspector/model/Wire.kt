package dev.inspector.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Device → daemon protocol, sent as JSON text frames over `WS /ingest`.
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

/** Clean shutdown. Best-effort — the daemon must handle an abrupt socket close identically. */
@Serializable
@SerialName("bye")
data object Bye : WireMsg

/**
 * How long after a disconnect the daemon will still accept a `resumeSessionId` and keep
 * appending to the same session folder. Beyond this, a reconnect starts a new session.
 */
const val SESSION_RESUME_GRACE_MS: Long = 5 * 60 * 1000

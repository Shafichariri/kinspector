package dev.inspector.daemon

import dev.inspector.model.SignRequest
import dev.inspector.model.SignResponse
import dev.inspector.model.WireMsg
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Raised when the app could not, or would not, produce headers for a replay. */
class ReplaySignerException(message: String) : Exception(message)

/**
 * The ingest sockets currently attached, so the host can ask a running app for something.
 *
 * The daemon had no such registry: `ingestRoute` kept its session id in a local, which is enough
 * to *receive* but not to *address*. Replay of a signed request needs to address one — the device
 * key cannot leave the device, so the app is the only thing that can produce fresh per-request
 * headers.
 *
 * Keyed by session id, since that is what the rest of the archive is keyed by and what a replay
 * request already carries.
 */
class LiveApps {

    private val connections = ConcurrentHashMap<String, Connection>()

    /**
     * One attached app.
     *
     * [send] is supplied by the socket handler rather than the socket itself, so this class stays
     * free of Ktor and can be faked in tests without standing up a WebSocket.
     */
    class Connection internal constructor(
        val sessionId: String,
        private val send: suspend (WireMsg) -> Unit,
    ) {
        internal val pending = ConcurrentHashMap<String, CompletableDeferred<SignResponse>>()

        /**
         * Asks the app for the headers a replay of [method] [url] needs regenerated.
         *
         * Always terminates: on a reply, on the app reporting failure, or on [timeoutMs]. A hung
         * app must not hang the daemon's request thread — the timeout is generous because signing
         * can prompt for user presence, but it is not optional.
         */
        suspend fun requestHeaders(
            method: String,
            url: String,
            timeoutMs: Long = DEFAULT_SIGN_TIMEOUT_MS,
        ): Map<String, String> {
            val requestId = UUID.randomUUID().toString()
            val reply = CompletableDeferred<SignResponse>()
            pending[requestId] = reply
            try {
                send(SignRequest(requestId, method, url))
                val response = withTimeoutOrNull(timeoutMs) { reply.await() }
                    ?: throw ReplaySignerException(
                        "the app did not answer within ${timeoutMs}ms — it may have no " +
                            "ReplaySigner registered, or be waiting on a device prompt",
                    )
                response.error?.let { throw ReplaySignerException(it) }
                return response.headers
            } finally {
                pending.remove(requestId)
            }
        }
    }

    fun register(sessionId: String, send: suspend (WireMsg) -> Unit): Connection =
        Connection(sessionId, send).also { connections[sessionId] = it }

    /** Only removes the connection still registered, so a reconnect cannot be unregistered by the old socket. */
    fun unregister(sessionId: String, connection: Connection) {
        connections.remove(sessionId, connection)
    }

    fun forSession(sessionId: String): Connection? = connections[sessionId]

    /** The only attached app when there is exactly one, so a replay need not name a session. */
    fun sole(): Connection? = connections.values.singleOrNull()

    fun attachedSessionIds(): Set<String> = connections.keys.toSet()

    /** Routes a reply back to whoever is waiting. Unknown ids are dropped: they have timed out. */
    fun complete(sessionId: String, response: SignResponse) {
        connections[sessionId]?.pending?.remove(response.requestId)?.complete(response)
    }

    companion object {
        /**
         * Generous on purpose. A hardware-backed signer can prompt for biometrics, and a user
         * reaching for a phone is slower than any network call this daemon otherwise makes.
         */
        const val DEFAULT_SIGN_TIMEOUT_MS: Long = 30_000
    }
}

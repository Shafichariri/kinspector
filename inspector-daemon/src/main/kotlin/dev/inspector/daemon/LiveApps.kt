package dev.inspector.daemon

import dev.inspector.model.SignRequest
import dev.inspector.model.SignResponse
import dev.inspector.model.Signal
import dev.inspector.model.SignalError
import dev.inspector.model.SignalRequest
import dev.inspector.model.WireMsg
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.Serializable
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Raised when the app could not, or would not, produce headers for a replay. */
class ReplaySignerException(message: String) : Exception(message)

/** Raised when the app could not answer a signal pull. */
class SignalProviderException(message: String) : Exception(message)

/** Body of `POST /api/sessions/{id}/signals/request`. Both fields are required. */
@Serializable
data class SignalPullRequest(
    val tag: String,
    val name: String,
)

/**
 * How a pull ended.
 *
 * Two outcomes rather than a nullable row, because "the app answered with nothing" and "the app
 * could not answer" are different facts and only one of them is worth reporting as a failure.
 */
internal sealed interface SignalReply {
    class Recorded(val signal: Signal) : SignalReply
    class Failed(val error: String) : SignalReply
}

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

        internal val pendingSignals = ConcurrentHashMap<String, CompletableDeferred<SignalReply>>()

        /**
         * Asks the app to read a registered provider and report what it holds now.
         *
         * Always terminates: on the row coming back, on the app reporting why it cannot, or on
         * [timeoutMs]. Shorter than the signing timeout because a provider reads app memory rather
         * than prompting a human.
         */
        suspend fun requestSignal(
            tag: String,
            name: String,
            timeoutMs: Long = DEFAULT_SIGNAL_TIMEOUT_MS,
        ): Signal {
            val requestId = UUID.randomUUID().toString()
            val reply = CompletableDeferred<SignalReply>()
            pendingSignals[requestId] = reply
            try {
                send(SignalRequest(requestId, tag, name))
                val result = withTimeoutOrNull(timeoutMs) { reply.await() }
                    ?: throw SignalProviderException(
                        "the app did not answer within ${timeoutMs}ms — it may have no provider " +
                            "for $tag/$name, or be blocked reading one",
                    )
                return when (result) {
                    is SignalReply.Recorded -> result.signal
                    is SignalReply.Failed -> throw SignalProviderException(result.error)
                }
            } finally {
                pendingSignals.remove(requestId)
            }
        }

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

    /**
     * Routes a recorded pull row back to whoever asked for it.
     *
     * Called for every archived signal, not only pulled ones, so the requestId check is what
     * separates a reply from an ordinary push. A row with no waiter is simply archived.
     */
    fun completeSignal(sessionId: String, signal: Signal) {
        val requestId = signal.requestId ?: return
        connections[sessionId]?.pendingSignals?.remove(requestId)
            ?.complete(SignalReply.Recorded(signal))
    }

    /** Routes a failed pull back. Unknown ids are dropped: they have timed out. */
    fun failSignal(sessionId: String, error: SignalError) {
        connections[sessionId]?.pendingSignals?.remove(error.requestId)
            ?.complete(SignalReply.Failed(error.error))
    }

    companion object {
        /**
         * Generous on purpose. A hardware-backed signer can prompt for biometrics, and a user
         * reaching for a phone is slower than any network call this daemon otherwise makes.
         */
        const val DEFAULT_SIGN_TIMEOUT_MS: Long = 30_000

        /**
         * Shorter than signing: a provider reads app memory rather than waiting on a person, so a
         * pull that has not answered in this long is stuck rather than slow.
         */
        const val DEFAULT_SIGNAL_TIMEOUT_MS: Long = 5_000
    }
}

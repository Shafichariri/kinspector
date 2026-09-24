package dev.inspector.stream

import dev.inspector.model.UsbFraming
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.exhausted
import io.ktor.utils.io.readFully
import io.ktor.utils.io.readInt
import io.ktor.utils.io.writeFully
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One open conversation with the host: [WireMsg][dev.inspector.model.WireMsg] JSON, both ways.
 *
 * What [StreamSink] talks through, so the sink is the same code whichever way the connection was
 * made. [send] must be safe to call concurrently — the sender and the replies to sign and signal
 * requests all write — and [receive] is only ever called from one coroutine.
 */
internal interface WireLink {
    suspend fun send(text: String)

    /** The next message, or null once the peer has gone. */
    suspend fun receive(): String?
}

/** How [StreamSink] reaches the host. One [session] per connection; the sink loops over them. */
internal interface Transport {
    /** What this reaches, for log lines — "daemon at 10.0.2.2:8099". */
    val description: String

    /** Makes one link, runs [block] over it, then tears it down. Throws when no link can be made. */
    suspend fun session(block: suspend (WireLink) -> Unit)

    fun close()
}

/**
 * The ordinary route: dial the daemon's `WS /ingest`. Every platform except a physical iPhone.
 *
 * The client is built on first use and kept across reconnects, as it always was.
 */
internal class WebSocketTransport(
    private val host: String,
    private val port: Int,
    private val engineFactory: () -> HttpClient,
) : Transport {

    private var http: HttpClient? = null

    override val description: String = "daemon at $host:$port"

    override suspend fun session(block: suspend (WireLink) -> Unit) {
        val http = this.http ?: engineFactory().also { this.http = it }
        http.webSocket(host = host, port = port, path = "/ingest") {
            val socket = this
            block(object : WireLink {
                override suspend fun send(text: String) = socket.send(Frame.Text(text))

                override suspend fun receive(): String? {
                    for (frame in socket.incoming) if (frame is Frame.Text) return frame.readText()
                    return null
                }
            })
        }
    }

    override fun close() {
        http?.close()
    }
}

/**
 * The physical-iPhone route: **accept** a connection from the host rather than dialling one.
 *
 * There is no `adb reverse` on iOS. What there is, is usbmuxd — the service Xcode itself uses —
 * and it tunnels only one way: from the Mac *into* a port on the phone. So the app listens and the
 * daemon's USB bridge dials in, carrying the same messages the WebSocket would, framed by
 * [UsbFraming]. Proven on hardware before this was written: a usbmuxd connection reaches a
 * listener bound to the phone's `127.0.0.1` and arrives from `127.0.0.1`.
 *
 * **Bound to loopback, and that is the boundary.** Over Wi-Fi, usbmuxd reaches the phone's network
 * address, which a loopback listener never sees — the spike showed the network route refused on
 * exactly this socket. So nothing on the phone's network can reach it; only something that can
 * reach the phone's own loopback can, which is the host over a cable and any process on the phone.
 * The second half is the same trust the daemon extends to every process on the Mac, and is why this
 * route, like the rest of the module, exists only in debug builds.
 *
 * The server socket is bound once and kept across sessions: releasing it between links would let
 * the port be taken in the gap, and each link ends every time the cable does.
 *
 * **`reuseAddress` is load-bearing.** Found on hardware: kill the app mid-session and relaunch it,
 * and the new process could not bind for about half a minute — `EADDRINUSE`, because the dead
 * process's connection sat in TIME_WAIT on the same port. The message it printed then blamed
 * another app, which in the commonest case of all was simply wrong. `SO_REUSEADDR` permits a bind
 * over a dead connection and still refuses one over a live listener, so a genuine clash between
 * two apps is still reported. The daemon's own pre-flight bind makes the same choice.
 */
internal class UsbListenerTransport(private val port: Int) : Transport {

    private val selector = SelectorManager(Dispatchers.IO)
    private var server: ServerSocket? = null

    override val description: String = "host over USB (listening on $LOOPBACK:$port)"

    override suspend fun session(block: suspend (WireLink) -> Unit) {
        val server = this.server
            ?: aSocket(selector).tcp().bind(LOOPBACK, port) { reuseAddress = true }.also { this.server = it }
        val socket = server.accept()
        try {
            val input = socket.openReadChannel()
            val output = socket.openWriteChannel(autoFlush = false)
            val writeLock = Mutex()
            block(object : WireLink {
                override suspend fun send(text: String) {
                    val frame = UsbFraming.encode(text)
                    // One frame is two writes' worth of bytes on the wire; interleaving two
                    // senders mid-frame would desynchronise every frame after it.
                    writeLock.withLock {
                        output.writeFully(frame, 0, frame.size)
                        output.flush()
                    }
                }

                override suspend fun receive(): String? {
                    if (input.exhausted()) return null
                    val length = UsbFraming.checkLength(input.readInt())
                    val payload = ByteArray(length)
                    input.readFully(payload, 0, length)
                    return payload.decodeToString()
                }
            })
        } finally {
            socket.close()
        }
    }

    override fun close() {
        server?.close()
        selector.close()
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
    }
}

/**
 * True when this build should wait for the host over USB instead of dialling [host].
 *
 * Only on a physical iPhone, and only while [host] is loopback — which is what
 * [defaultDaemonHost] returns there. An app that names some other host has its own route to the
 * daemon (a tunnel, a proxy) and keeps dialling it, exactly as before.
 */
internal expect fun listensForUsb(host: String): Boolean

internal fun isLoopback(host: String): Boolean = host == "127.0.0.1" || host == "localhost" || host == "::1"

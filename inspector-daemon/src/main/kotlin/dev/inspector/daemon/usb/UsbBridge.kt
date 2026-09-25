package dev.inspector.daemon.usb

import dev.inspector.model.UsbFraming
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Carries a USB-attached iPhone's stream into the daemon — the iOS counterpart of `adb reverse`.
 *
 * usbmuxd can only dial *into* the phone, so the app listens on its own `127.0.0.1:<port>` and this
 * dials it. Each frame that arrives is handed to the daemon's ordinary `WS /ingest` on loopback, and
 * each reply goes back the same way, so the daemon cannot tell a bridged app from one that dialled
 * it — which is the point: one ingest path, the one every test already covers.
 *
 * **Nothing here widens what the daemon exposes.** The daemon still binds `127.0.0.1` alone; the
 * bridge only makes outbound connections, to usbmuxd's local socket and to the daemon's own port.
 * Only `USB` routes are dialled: over `Network` usbmuxd reaches the phone's LAN address, which the
 * app's loopback listener never sees, so those attempts could only ever be refused.
 *
 * Discovery is a poll rather than usbmuxd's `Listen` subscription, because the poll has to happen
 * anyway: a phone that is attached with the app not yet running refuses the dial, and the only way
 * to learn that it has started is to dial again.
 */
class UsbBridge(
    private val usbmux: Usbmux,
    private val daemonPort: Int,
    /** The port the app listens on — the daemon's own, unless told otherwise. */
    private val devicePort: Int = daemonPort,
    private val pollMillis: Long = DEFAULT_POLL_MILLIS,
    private val log: (String) -> Unit = ::println,
) {
    private val links = ConcurrentHashMap<Int, Link>()
    private val known = mutableSetOf<Int>()
    private var reportedFailure: String? = null
    private val running = AtomicBoolean(false)
    private val http: HttpClient = HttpClient.newHttpClient()

    fun start(): UsbBridge {
        if (!running.compareAndSet(false, true)) return this
        Thread({
            // Sleep first: started alongside the daemon, the first dial would otherwise race the
            // daemon's own bind and log a failure to reach /ingest that fixes itself a second later.
            while (running.get()) {
                Thread.sleep(pollMillis)
                if (running.get()) pollOnce()
            }
        }, "inspector-usb").apply { isDaemon = true }.start()
        return this
    }

    fun stop() {
        running.set(false)
        links.values.forEach { it.close() }
    }

    /** Devices currently bridged, by usbmuxd id. For tests and the log. */
    val linkedDeviceIds: Set<Int> get() = links.keys.toSet()

    /** One round: notice what is attached, and dial every phone not already linked. */
    fun pollOnce() {
        val devices = try {
            usbmux.listDevices().filter { it.isUsb }.also { reportedFailure = null }
        } catch (e: Exception) {
            // Reported once, not every two seconds: usbmuxd going away is a state, not an event.
            val reason = e.message ?: e::class.simpleName.orEmpty()
            if (reason != reportedFailure) {
                reportedFailure = reason
                log("inspector: usb — $reason")
            }
            return
        }

        val attached = devices.associateBy { it.id }
        for (device in devices) {
            if (known.add(device.id)) {
                log(
                    "inspector: usb — iPhone ${device.serial} attached; " +
                        "waiting for an app listening on port $devicePort"
                )
            }
        }
        known.retainAll(attached.keys)

        for (device in devices) {
            if (links.containsKey(device.id)) continue
            val channel = try {
                usbmux.connect(device.id, devicePort)
            } catch (e: Exception) {
                log("inspector: usb — cannot dial ${device.serial}: ${e.message}")
                null
            } ?: continue // Refused: no app listening yet. The ordinary state; say nothing.
            Link(device, channel).open()
        }
    }

    /** One phone's stream: a USB channel on one side, the daemon's `/ingest` on the other. */
    private inner class Link(val device: UsbDevice, val channel: SocketChannel) : WebSocket.Listener {

        private val closed = AtomicBoolean(false)
        private val text = StringBuilder()
        private var socket: WebSocket? = null

        fun open() {
            // Registered before anything can fail, so close() always has an entry to remove and
            // the next poll never dials a second link to a phone that already has one.
            links[device.id] = this
            val ws = try {
                http.newWebSocketBuilder()
                    .buildAsync(URI("ws://127.0.0.1:$daemonPort/ingest"), this)
                    .get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } catch (e: Exception) {
                log("inspector: usb — cannot reach the daemon's own /ingest on port $daemonPort: ${e.message}")
                close()
                return
            }
            socket = ws
            log("inspector: usb — connected to ${device.serial} on port $devicePort")
            Thread({ pumpFromDevice(ws) }, "inspector-usb-${device.id}").apply { isDaemon = true }.start()
        }

        /** Phone → daemon: one frame in, one WebSocket text message out, in order. */
        private fun pumpFromDevice(ws: WebSocket) {
            try {
                while (!closed.get()) {
                    val length = UsbFraming.payloadLength(channel.readExactly(UsbFraming.HEADER_BYTES).array())
                    val payload = channel.readExactly(length).array().decodeToString()
                    // Waiting on each send keeps frames ordered and applies backpressure to the
                    // phone rather than queueing without bound here.
                    ws.sendText(payload, true).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                }
            } catch (_: Exception) {
                // End of stream, a cable pulled, a malformed header: all end this link the same way.
            } finally {
                close()
            }
        }

        // Daemon → phone. The listener is called serially, so the buffer needs no lock; the
        // channel write does, only because close() may race it.
        override fun onOpen(webSocket: WebSocket) {
            webSocket.request(1)
        }

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            text.append(data)
            if (last) {
                val frame = UsbFraming.encode(text.toString())
                text.setLength(0)
                try {
                    synchronized(channel) { channel.writeFully(ByteBuffer.wrap(frame)) }
                } catch (_: Exception) {
                    close()
                    return null
                }
            }
            webSocket.request(1)
            return null
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
            close()
            return null
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            close()
        }

        fun close() {
            if (!closed.compareAndSet(false, true)) return
            runCatching { channel.close() }
            // abort, not sendClose: the daemon treats an abrupt close exactly like a clean one,
            // and waiting on a close handshake with a phone already gone buys nothing.
            socket?.abort()
            links.remove(device.id, this)
            if (socket != null) log("inspector: usb — ${device.serial} disconnected")
        }
    }

    companion object {
        const val DEFAULT_POLL_MILLIS = 2_000L
        private const val CONNECT_TIMEOUT_SECONDS = 5L
        private const val SEND_TIMEOUT_SECONDS = 30L

        /**
         * Starts a bridge when this machine has usbmuxd, and says nothing when it does not — a Mac
         * without Xcode still has usbmuxd, and a Linux machine without it has no iPhone to bridge.
         */
        fun startIfAvailable(daemonPort: Int, devicePort: Int = daemonPort, usbmux: Usbmux = Usbmux()): UsbBridge? {
            if (!usbmux.available()) return null
            println("inspector: usb bridge on — a USB-attached iPhone is dialled on port $devicePort")
            return UsbBridge(usbmux, daemonPort, devicePort).start()
        }
    }
}

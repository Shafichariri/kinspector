package dev.inspector.daemon.usb

import java.io.EOFException
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SocketChannel
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.exists

/** One device usbmuxd knows about. The same phone appears once per route — `USB` and `Network`. */
data class UsbDevice(val id: Int, val serial: String, val connectionType: String) {
    val isUsb: Boolean get() = connectionType == "USB"
}

class UsbmuxException(message: String) : IOException(message)

/**
 * A client for usbmuxd, the macOS service that multiplexes TCP over the Lightning/USB-C cable to
 * an iPhone. It is what Xcode uses, it runs on every Mac, and it needs nothing installed.
 *
 * Two operations, which are all the bridge needs: list what is attached, and open a TCP connection
 * to a port **on the phone**. Only that direction exists — which is why the app listens and the
 * bridge dials, the reverse of every other platform.
 *
 * The protocol is a 16-byte little-endian header — total length, version `1`, message type `8`
 * (plist), a tag echoed in the reply — then an XML plist. After a successful `Connect` the same
 * socket stops being a usbmuxd conversation and becomes the raw TCP stream to the device.
 * Verified against a real iPhone before this was written, not from documentation: Apple publishes
 * none, and the shape here is what the service answered.
 */
class Usbmux(private val socketPath: Path = DEFAULT_SOCKET) {

    /** False on a machine with no usbmuxd — Linux without it installed, say. */
    fun available(): Boolean = socketPath.exists()

    fun listDevices(): List<UsbDevice> = open().use { channel ->
        request(channel, mapOf("MessageType" to "ListDevices"))
        val reply = readMessage(channel)
        val list = reply["DeviceList"] as? List<*>
            ?: throw UsbmuxException("ListDevices reply had no DeviceList: ${reply.keys}")
        list.mapNotNull { entry ->
            val device = entry as? Map<*, *> ?: return@mapNotNull null
            val properties = device["Properties"] as? Map<*, *> ?: return@mapNotNull null
            UsbDevice(
                id = (device["DeviceID"] as? Long)?.toInt() ?: return@mapNotNull null,
                serial = properties["SerialNumber"] as? String ?: "unknown",
                connectionType = properties["ConnectionType"] as? String ?: "unknown",
            )
        }
    }

    /**
     * A TCP stream to [port] on the phone, or null when nothing on the phone is listening there —
     * the ordinary answer while the app is not running, so it is not an error. Anything else usbmuxd
     * refuses with is thrown: an unplugged device, an unrecognised request.
     */
    fun connect(deviceId: Int, port: Int): SocketChannel? {
        val channel = open()
        try {
            request(
                channel,
                mapOf(
                    "MessageType" to "Connect",
                    "DeviceID" to deviceId,
                    // Network byte order, then read by usbmuxd as a little-endian integer — so
                    // the port goes in byte-swapped. 8099 is sent as 41759.
                    "PortNumber" to swapBytes(port),
                ),
            )
            val reply = readMessage(channel)
            return when (val result = (reply["Number"] as? Long)?.toInt()) {
                RESULT_OK -> channel
                RESULT_CONNECTION_REFUSED -> {
                    channel.close()
                    null
                }
                else -> throw UsbmuxException("usbmuxd refused Connect to device $deviceId port $port with result $result")
            }
        } catch (e: Throwable) {
            channel.close()
            throw e
        }
    }

    private fun open(): SocketChannel =
        SocketChannel.open(StandardProtocolFamily.UNIX).apply {
            try {
                connect(UnixDomainSocketAddress.of(socketPath))
            } catch (e: IOException) {
                close()
                throw UsbmuxException("cannot reach usbmuxd at $socketPath: ${e.message}")
            }
        }

    private fun request(channel: SocketChannel, fields: Map<String, Any>) {
        val body = Plist.encode(BASE_FIELDS + fields)
        val header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(HEADER_BYTES + body.size).putInt(VERSION_PLIST).putInt(MESSAGE_PLIST).putInt(TAG)
            .flip()
        channel.writeFully(header)
        channel.writeFully(ByteBuffer.wrap(body))
    }

    /**
     * Reads exactly one message and not a byte more: after a `Connect` reply, whatever follows on
     * this socket belongs to the device, and over-reading would steal the first bytes of it.
     */
    private fun readMessage(channel: SocketChannel): Map<String, Any?> {
        val header = channel.readExactly(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        val total = header.int
        if (total < HEADER_BYTES || total > MAX_MESSAGE_BYTES) {
            throw UsbmuxException("usbmuxd sent a message header claiming $total bytes")
        }
        val body = channel.readExactly(total - HEADER_BYTES)
        @Suppress("UNCHECKED_CAST")
        return Plist.decode(body.array()) as? Map<String, Any?>
            ?: throw UsbmuxException("usbmuxd reply was not a dict")
    }

    companion object {
        val DEFAULT_SOCKET: Path = Path("/var/run/usbmuxd")

        private const val HEADER_BYTES = 16
        private const val VERSION_PLIST = 1
        private const val MESSAGE_PLIST = 8
        private const val TAG = 1
        private const val MAX_MESSAGE_BYTES = 4 * 1024 * 1024

        private const val RESULT_OK = 0
        private const val RESULT_CONNECTION_REFUSED = 3

        private val BASE_FIELDS = mapOf(
            "ClientVersionString" to "inspector",
            "ProgName" to "inspector",
        )

        internal fun swapBytes(port: Int): Int = ((port and 0xFF) shl 8) or ((port shr 8) and 0xFF)
    }
}

internal fun SocketChannel.writeFully(buffer: ByteBuffer) {
    while (buffer.hasRemaining()) write(buffer)
}

internal fun SocketChannel.readExactly(count: Int): ByteBuffer {
    val buffer = ByteBuffer.allocate(count)
    while (buffer.hasRemaining()) {
        if (read(buffer) < 0) throw EOFException("peer closed after ${buffer.position()} of $count bytes")
    }
    return buffer.flip()
}

package dev.inspector.daemon.usb

import dev.inspector.daemon.DaemonConfig
import dev.inspector.daemon.InspectorDaemon
import dev.inspector.daemon.SessionRepository
import dev.inspector.daemon.clientInfo
import dev.inspector.daemon.marker
import dev.inspector.daemon.txn
import dev.inspector.model.Hello
import dev.inspector.model.HelloAck
import dev.inspector.model.InspectorJson
import dev.inspector.model.MarkerMsg
import dev.inspector.model.Platforms
import dev.inspector.model.Txn
import dev.inspector.model.UsbFraming
import dev.inspector.model.WireMsg
import java.net.ServerSocket
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The host's half of the USB route: a fake usbmuxd in front of the real bridge and the real
 * daemon. The fake answers `ListDevices` with a reply captured from a real Mac with a real iPhone
 * attached — identifiers zeroed — and after a `Connect` it hands the socket to the test, which then
 * plays the app. The phone's half is `UsbListenerTransportTest` in `:inspector-stream`; the two
 * meet only on hardware.
 */
class UsbBridgeTest {

    private lateinit var tmp: Path
    private lateinit var config: DaemonConfig
    private lateinit var daemon: InspectorDaemon
    private lateinit var mux: FakeUsbmuxd
    private val log = CopyOnWriteArrayList<String>()

    @BeforeTest
    fun setUp() {
        // Short on purpose: a Unix socket path is capped near 104 bytes on macOS.
        tmp = Files.createTempDirectory("usb")
        config = DaemonConfig(port = ServerSocket(0).use { it.localPort }, dataDir = tmp)
        daemon = InspectorDaemon(config).also { it.start(wait = false) }
        mux = FakeUsbmuxd(tmp.resolve("mux"))
    }

    @AfterTest
    fun tearDown() {
        mux.close()
        daemon.stop()
        Files.walk(tmp).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    private fun bridge() = UsbBridge(Usbmux(mux.path), daemonPort = config.port, log = { log += it })

    @Test
    fun `a phone's frames reach the archive and the daemon's replies reach the phone`() {
        mux.appListening = true
        val bridge = bridge()
        bridge.pollOnce()

        val app = assertNotNull(mux.appSide.poll(5, TimeUnit.SECONDS), "the bridge never dialled the app")
        app.writeMsg(Hello(clientInfo().copy(platform = Platforms.IOS_DEVICE)))
        val ack = assertIs<HelloAck>(app.readMsg(), "the daemon's reply must come back down the cable")

        app.writeMsg(Txn(txn("t1"), resBody = "{\"over\":\"usb\"}"))
        app.writeMsg(MarkerMsg(marker("plugged in")))

        val repo = SessionRepository(config)
        val dir = awaitNotNull { repo.resolve(ack.sessionId) }
        awaitTrue { repo.readMarkers(dir).isNotEmpty() && repo.readTransactions(dir).isNotEmpty() }
        assertEquals("plugged in", repo.readMarkers(dir).single().label)
        assertEquals("{\"over\":\"usb\"}", repo.readBody(dir, "t1", "res")?.decodeToString())
        assertEquals(Platforms.IOS_DEVICE, repo.readMeta(dir)?.platform)

        // The cable comes out: the session ends like any other disconnect, and the bridge forgets
        // the link so the next poll can dial the phone again.
        app.close()
        awaitTrue { repo.readMeta(dir)?.endedAt != null }
        awaitTrue { bridge.linkedDeviceIds.isEmpty() }

        bridge.pollOnce()
        assertNotNull(mux.appSide.poll(5, TimeUnit.SECONDS), "the bridge should dial again once the link is gone")
        bridge.stop()
    }

    @Test
    fun `a phone with no app listening is dialled every poll and reported once`() {
        val bridge = bridge()
        repeat(3) { bridge.pollOnce() }

        assertEquals(3, mux.connects.size, "each poll must try again: the app may have started")
        assertTrue(bridge.linkedDeviceIds.isEmpty())
        assertEquals(
            1,
            log.count { "attached" in it },
            "a refused dial is the ordinary state and must not produce a line every two seconds: $log",
        )
    }

    @Test
    fun `only the USB route is dialled and on the daemon's own port`() {
        bridge().pollOnce()
        // The captured reply lists the same phone twice: 139 over USB, 138 over the network.
        assertEquals(listOf(139 to config.port), mux.connects.toList())
    }

    @Test
    fun `a phone already linked is not dialled a second time`() {
        mux.appListening = true
        val bridge = bridge()
        bridge.pollOnce()
        assertNotNull(mux.appSide.poll(5, TimeUnit.SECONDS))
        bridge.pollOnce()
        assertEquals(1, mux.connects.size)
        bridge.stop()
    }

    @Test
    fun `no usbmuxd is reported once rather than every poll`() {
        val bridge = UsbBridge(Usbmux(tmp.resolve("absent")), daemonPort = config.port, log = { log += it })
        assertFalse(Usbmux(tmp.resolve("absent")).available())
        repeat(3) { bridge.pollOnce() }
        assertEquals(1, log.size, "$log")
        assertTrue("cannot reach usbmuxd" in log.single())
    }

    @Test
    fun `the port goes to usbmuxd byte-swapped`() {
        // What the spike's Python client sent as socket.htons(8099) against a real iPhone.
        assertEquals(41759, Usbmux.swapBytes(8099))
        assertEquals(8099, Usbmux.swapBytes(Usbmux.swapBytes(8099)))
    }

    @Test
    fun `a real ListDevices reply parses, multi-line data and all`() {
        val devices = Plist.decode(fixture()) as Map<*, *>
        val list = devices["DeviceList"] as List<*>
        assertEquals(2, list.size)
        val network = ((list[1] as Map<*, *>)["Properties"] as Map<*, *>)
        assertEquals(152, (network["NetworkAddress"] as ByteArray).size)
    }

    @Test
    fun `an encoded request escapes what XML would misread`() {
        val decoded = Plist.decode(Plist.encode(mapOf("ProgName" to "a<b&c", "Port" to 41759))) as Map<*, *>
        assertEquals("a<b&c", decoded["ProgName"])
        assertEquals(41759L, decoded["Port"])
    }

    // --- helpers --------------------------------------------------------------------------------

    private fun SocketChannel.writeMsg(msg: WireMsg) =
        writeFully(ByteBuffer.wrap(UsbFraming.encode(InspectorJson.encodeToString<WireMsg>(msg))))

    private fun SocketChannel.readMsg(): WireMsg {
        val length = UsbFraming.payloadLength(readExactly(UsbFraming.HEADER_BYTES).array())
        return InspectorJson.decodeFromString<WireMsg>(readExactly(length).array().decodeToString())
    }

    private fun <T : Any> awaitNotNull(block: () -> T?): T {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            block()?.let { return it }
            Thread.sleep(25)
        }
        error("timed out")
    }

    private fun awaitTrue(block: () -> Boolean) = awaitNotNull { block().takeIf { it } }

    companion object {
        fun fixture(): ByteArray =
            UsbBridgeTest::class.java.getResourceAsStream("/usbmux/list-devices.plist")!!.readBytes()
    }

    /**
     * Enough usbmuxd to drive the bridge: `ListDevices` answers with the captured reply, and
     * `Connect` either refuses — no app listening — or succeeds and gives the socket to the test.
     */
    private class FakeUsbmuxd(val path: Path) : AutoCloseable {
        @Volatile var appListening = false
        val appSide = LinkedBlockingQueue<SocketChannel>()
        /** (deviceId, port) per Connect, the port already un-swapped. */
        val connects = CopyOnWriteArrayList<Pair<Int, Int>>()

        private val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
            .bind(UnixDomainSocketAddress.of(path))

        init {
            thread(isDaemon = true) {
                while (server.isOpen) {
                    val client = runCatching { server.accept() }.getOrNull() ?: break
                    thread(isDaemon = true) { runCatching { serve(client) } }
                }
            }
        }

        private fun serve(client: SocketChannel) {
            val header = client.readExactly(16).order(ByteOrder.LITTLE_ENDIAN)
            val total = header.int
            val request = Plist.decode(client.readExactly(total - 16).array()) as Map<*, *>
            when (request["MessageType"]) {
                "ListDevices" -> {
                    client.reply(fixture())
                    client.close()
                }
                "Connect" -> {
                    val port = Usbmux.swapBytes((request["PortNumber"] as Long).toInt())
                    connects += (request["DeviceID"] as Long).toInt() to port
                    if (appListening) {
                        client.reply(Plist.encode(mapOf("MessageType" to "Result", "Number" to 0)))
                        appSide.put(client)
                    } else {
                        client.reply(Plist.encode(mapOf("MessageType" to "Result", "Number" to 3)))
                        client.close()
                    }
                }
                else -> client.close()
            }
        }

        private fun SocketChannel.reply(body: ByteArray) {
            val header = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(16 + body.size).putInt(1).putInt(8).putInt(1).flip()
            writeFully(header)
            writeFully(ByteBuffer.wrap(body))
        }

        override fun close() {
            server.close()
            appSide.forEach { it.close() }
            Files.deleteIfExists(path)
        }
    }
}

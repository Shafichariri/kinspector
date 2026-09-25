package dev.inspector.stream

import dev.inspector.model.ClientInfo
import dev.inspector.model.Hello
import dev.inspector.model.HelloAck
import dev.inspector.model.InspectorJson
import dev.inspector.model.Marker
import dev.inspector.model.MarkerMsg
import dev.inspector.model.MarkerSource
import dev.inspector.model.Platforms
import dev.inspector.model.SignRequest
import dev.inspector.model.SignResponse
import dev.inspector.model.UsbFraming
import dev.inspector.model.WireMsg
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.DataInputStream
import java.net.ConnectException
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * The phone's half of the USB route, driven on the JVM with this test playing the host's bridge.
 *
 * On a real iPhone [listensForUsb] picks [UsbListenerTransport] by itself; on the JVM it never
 * does, so the transport is assigned directly. Everything past that choice — the sink's handshake,
 * its queue, its replies to the host — is the same code the phone runs.
 */
class UsbListenerTransportTest {

    private val port = ServerSocket(0).use { it.localPort }
    private val sink = StreamSink(CLIENT, port = port).apply { transport = UsbListenerTransport(port) }

    @AfterTest
    fun tearDown() = sink.stop()

    @Test
    fun `the sink waits for the host and then speaks framed wire messages both ways`() = runBlocking {
        sink.start()

        dial().use { bridge ->
            val hello = assertIs<Hello>(bridge.readMsg())
            assertEquals(null, hello.resumeSessionId)
            bridge.writeMsg(HelloAck(sessionId = "s-1"))
            withTimeout(5_000) { sink.state.first { it == StreamState.Connected } }

            // App to host.
            sink.onMarker(Marker(ts = "2026-09-24T00:00:00Z", mono = 1, label = "over usb", source = MarkerSource.APP))
            assertEquals("over usb", assertIs<MarkerMsg>(bridge.readMsg()).marker.label)

            // Host to app, and back: a request the sink must answer on the same link.
            bridge.writeMsg(SignRequest(requestId = "r-1", method = "GET", url = "https://example.test/"))
            val reply = assertIs<SignResponse>(bridge.readMsg())
            assertEquals("r-1", reply.requestId)
            assertNotNull(reply.error, "no signer is registered, and the sink must say so")
        }

        // The cable came out. The sink notices, and the next connection resumes the same session —
        // the listener survives the link, so the host can dial the same port again.
        withTimeout(5_000) { sink.state.first { it != StreamState.Connected } }
        dial().use { bridge ->
            assertEquals("s-1", assertIs<Hello>(bridge.readMsg()).resumeSessionId)
        }
        Unit
    }

    @Test
    fun `a peer that is not speaking the protocol loses the link and the listener survives`() = runBlocking {
        sink.start()

        dial().use { stranger ->
            assertIs<Hello>(stranger.readMsg())
            // One byte over the limit, and deliberately not the `GET ` a stray HTTP client would
            // send. That reads as 1.2 GB, which the test JVM cannot allocate: the resulting
            // OutOfMemoryError is caught by the sink's read loop and closes the link anyway, so
            // the test passed with the length check deleted — proven by deleting it. 64 MiB + 1
            // *can* be allocated here, so without the check the sink sits waiting for bytes that
            // never come, and the read below times out. On a phone either size is the crash.
            val overLimit = UsbFraming.MAX_FRAME_BYTES + 1
            stranger.getOutputStream().apply {
                write(byteArrayOf((overLimit ushr 24).toByte(), (overLimit ushr 16).toByte(), (overLimit ushr 8).toByte(), overLimit.toByte()))
                flush()
            }
            stranger.soTimeout = 5_000
            assertEquals(-1, stranger.getInputStream().read(), "the sink should have closed the link")
        }

        dial().use { bridge -> assertIs<Hello>(bridge.readMsg()) }
        Unit
    }

    /** Dials the sink's listener, retrying while it binds — it binds on the sink's own coroutine. */
    private fun dial(): Socket {
        val deadline = System.currentTimeMillis() + 5_000
        while (true) {
            try {
                return Socket("127.0.0.1", port).apply { soTimeout = 5_000 }
            } catch (e: ConnectException) {
                if (System.currentTimeMillis() > deadline) throw e
                Thread.sleep(20)
            }
        }
    }

    private fun Socket.readMsg(): WireMsg {
        val input = DataInputStream(getInputStream())
        val length = UsbFraming.checkLength(input.readInt())
        val payload = ByteArray(length).also { input.readFully(it) }
        return InspectorJson.decodeFromString<WireMsg>(payload.decodeToString())
    }

    private fun Socket.writeMsg(msg: WireMsg) {
        getOutputStream().apply {
            write(UsbFraming.encode(InspectorJson.encodeToString<WireMsg>(msg)))
            flush()
        }
    }

    private companion object {
        val CLIENT = ClientInfo(
            appId = "usb.test",
            appVersion = "1",
            platform = Platforms.IOS_DEVICE,
            device = "test",
            osVersion = "0",
            buildType = "debug",
        )
    }
}

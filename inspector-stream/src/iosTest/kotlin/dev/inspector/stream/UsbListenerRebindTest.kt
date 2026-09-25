package dev.inspector.stream

import dev.inspector.model.UsbFraming
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.awaitClosed
import io.ktor.network.sockets.openReadChannel
import io.ktor.utils.io.readFully
import io.ktor.utils.io.readInt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A relaunched app must be able to listen again at once, even though the killed one's connection
 * is still in TIME_WAIT on the same port.
 *
 * Found on a real iPhone: kill the app mid-session, relaunch it, and the new process failed to
 * bind with `EADDRINUSE` for about half a minute. The fix is `reuseAddress` on the listener.
 *
 * **Why this is an iOS test and not a JVM one.** The JVM version was written first and passed with
 * the fix deleted — the JDK turns `SO_REUSEADDR` on for server sockets by default, so it cannot
 * see the bug. Kotlin/Native's sockets are plain POSIX with no such default, and this runs against
 * the same Darwin networking the phone does, so here the failure reproduces.
 */
class UsbListenerRebindTest {

    @Test
    fun `a relaunched app listens again at once while the killed one's link lingers`() = runBlocking {
        val selector = SelectorManager(Dispatchers.IO)
        val port = freePort(selector)

        // The first app: listening, linked, then killed with the link still open — so its side
        // closes first and the connection goes to TIME_WAIT on the listening port.
        val first = UsbListenerTransport(port)
        val linked = CompletableDeferred<Unit>()
        val firstSession = launch {
            first.session { link ->
                link.send("first")
                linked.complete(Unit)
                link.receive()
            }
        }
        val bridge = dial(selector, port)
        assertEquals("first", bridge.readFrame())
        linked.await()
        firstSession.cancel()
        firstSession.join()
        first.close()
        // Not a sleep: the first version slept 100 ms, which was enough on a laptop and not on a
        // CI runner, where the listener was still open at the rebind and failed it for a reason
        // that has nothing to do with TIME_WAIT. A real relaunch cannot race this way — the dead
        // process's sockets are closed by the kernel — so the test waits for the same state.
        first.awaitClosed()

        // The relaunch. Without reuseAddress this bind throws for as long as TIME_WAIT lasts.
        val second = UsbListenerTransport(port)
        val secondSession = launch { second.session { it.send("second") } }
        try {
            dial(selector, port).use { assertEquals("second", it.readFrame()) }
        } finally {
            secondSession.cancel()
            second.close()
            bridge.close()
            selector.close()
        }
    }

    /**
     * A port nothing holds. Awaits the close for the reason above: `use` returns before Ktor has
     * released the socket on Native, and on a CI runner the first listener then lost the race for
     * its own port — the failure this test first reported was the helper's, not the code's.
     */
    private suspend fun freePort(selector: SelectorManager): Int {
        val probe = aSocket(selector).tcp().bind("127.0.0.1", 0)
        val port = (probe.localAddress as InetSocketAddress).port
        probe.close()
        probe.awaitClosed()
        return port
    }

    /** Dials until the listener is up, or fails after five seconds — far less than TIME_WAIT. */
    private suspend fun dial(selector: SelectorManager, port: Int): Socket = withTimeout(5_000) {
        while (true) {
            try {
                return@withTimeout aSocket(selector).tcp().connect("127.0.0.1", port)
            } catch (_: Exception) {
                delay(50)
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    private suspend fun Socket.readFrame(): String {
        val input = openReadChannel()
        val length = UsbFraming.checkLength(input.readInt())
        return ByteArray(length).also { input.readFully(it, 0, length) }.decodeToString()
    }
}

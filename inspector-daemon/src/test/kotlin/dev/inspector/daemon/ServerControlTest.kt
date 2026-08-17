package dev.inspector.daemon

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The stop/restart endpoints the web UI drives.
 *
 * Every test injects a fake [ServerControl]. The real one ends the JVM, which here is the test
 * runner — the whole reason that seam exists.
 */
class ServerControlTest {

    /** Records what the daemon asked for instead of doing it. */
    private class FakeControl(override val canRestart: Boolean = true) : ServerControl {
        val spawned = AtomicInteger()
        val exited = AtomicInteger()
        val finished = CountDownLatch(1)

        override fun spawnReplacement() {
            spawned.incrementAndGet()
        }

        override fun exit(): Nothing {
            exited.incrementAndGet()
            finished.countDown()
            // The real one never returns, and the daemon's shutdown thread is written on that
            // assumption. Ending the thread the same way keeps the fake honest without taking
            // the test JVM with it.
            throw ShutdownReached()
        }
    }

    private class ShutdownReached : Throwable("fake exit", null, false, false)

    private lateinit var tmp: Path
    private lateinit var config: DaemonConfig
    private lateinit var daemon: InspectorDaemon
    private lateinit var http: HttpClient

    private fun start(control: ServerControl) = runBlocking {
        tmp = Files.createTempDirectory("inspector-control")
        config = DaemonConfig(port = ServerSocket(0).use { it.localPort }, dataDir = tmp)
        Files.createDirectories(config.sessionsDir)
        daemon = InspectorDaemon(config, control)
        daemon.start(wait = false)
        http = HttpClient(CIO)
        withTimeoutOrNull(10_000) {
            while (runCatching { http.get(url("/api/server")).status.value }.getOrNull() != 200) delay(50)
        }
        Unit
    }

    private fun url(path: String) = "http://127.0.0.1:${config.port}$path"

    @BeforeTest
    fun setUp() {
        // Each test starts its own daemon, so that the control it injects is the one under test.
    }

    @AfterTest
    fun tearDown() {
        runCatching { http.close() }
        runCatching { daemon.stop() }
        runCatching { tmp.toFile().deleteRecursively() }
    }

    @Test
    fun `status reports the port and whether this process can relaunch itself`() = runBlocking {
        start(FakeControl(canRestart = true))
        val body = http.get(url("/api/server")).bodyAsText()
        assertContains(body, "\"port\":${config.port}")
        assertContains(body, "\"canRestart\":true")
    }

    @Test
    fun `status reports canRestart false when the process cannot report its argv`() = runBlocking {
        start(FakeControl(canRestart = false))
        assertContains(http.get(url("/api/server")).bodyAsText(), "\"canRestart\":false")
    }

    @Test
    fun `stop exits without spawning a replacement`() = runBlocking {
        val control = FakeControl()
        start(control)

        val response = http.post(url("/api/server/stop")) { header("X-Inspector-Control", "1") }
        assertEquals(200, response.status.value)
        assertContains(response.bodyAsText(), "\"action\":\"stop\"")

        assertTrue(control.finished.await(10, TimeUnit.SECONDS), "daemon never shut down")
        assertEquals(1, control.exited.get())
        assertEquals(0, control.spawned.get(), "stop must not relaunch the daemon")
    }

    @Test
    fun `restart spawns a replacement before exiting`() = runBlocking {
        val control = FakeControl()
        start(control)

        val response = http.post(url("/api/server/restart")) { header("X-Inspector-Control", "1") }
        assertEquals(200, response.status.value)
        assertContains(response.bodyAsText(), "\"action\":\"restart\"")

        assertTrue(control.finished.await(10, TimeUnit.SECONDS), "daemon never shut down")
        assertEquals(1, control.spawned.get())
        assertEquals(1, control.exited.get())
    }

    @Test
    fun `restart is refused when this process cannot relaunch itself`() = runBlocking {
        val control = FakeControl(canRestart = false)
        start(control)

        val response = http.post(url("/api/server/restart")) { header("X-Inspector-Control", "1") }
        assertEquals(501, response.status.value)
        assertContains(response.bodyAsText(), "cannot relaunch")
        // Refusing must leave the daemon running: losing it entirely is the worse outcome.
        assertFalse(control.finished.await(1, TimeUnit.SECONDS), "a refused restart shut the daemon down")
        assertEquals(200, http.get(url("/api/server")).status.value)
    }

    @Test
    fun `control endpoints reject a request without the control header`() = runBlocking {
        val control = FakeControl()
        start(control)

        for (action in listOf("stop", "restart")) {
            val response = http.post(url("/api/server/$action"))
            assertEquals(403, response.status.value, "POST /api/server/$action")
            assertContains(response.bodyAsText(), "X-Inspector-Control")
        }
        // A cross-origin page cannot set that header without a preflight this daemon never
        // answers, so this is what keeps a stray tab from killing the daemon.
        assertFalse(control.finished.await(1, TimeUnit.SECONDS), "a rejected request still shut the daemon down")
        assertEquals(0, control.exited.get())
    }
}

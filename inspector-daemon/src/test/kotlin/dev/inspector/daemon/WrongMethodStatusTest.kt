package dev.inspector.daemon

import dev.inspector.daemon.archive.DaemonConfig
import dev.inspector.daemon.archive.SessionRepository
import dev.inspector.daemon.http.InspectorDaemon
import dev.inspector.daemon.service.KillOutcome
import dev.inspector.daemon.service.LiveApps
import dev.inspector.daemon.service.McpControl
import dev.inspector.daemon.service.McpInfo
import dev.inspector.daemon.service.McpProbeResult
import dev.inspector.daemon.service.PeerDaemon
import dev.inspector.daemon.service.PeerRegistry
import dev.inspector.daemon.service.ReplayTransport
import dev.inspector.daemon.service.Replayer
import dev.inspector.daemon.service.ServerControl
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.request
import io.ktor.http.HttpMethod
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.fail

/**
 * A method the daemon does not serve on a path it does serve answers 405, not 404.
 *
 * The four paths below each have control routes under them, and the first control gate moved those
 * into a subtree of their own. Ktor reports the deepest failure it met while resolving, so a wrong
 * method on the open GET above them came back 404 from the gated subtree's path failure: a
 * different status for every request this test sends, with every other guard here green.
 * `/api/recording` has nothing gated under it and is the control row: it was 405 throughout.
 *
 * Every dependency with a side effect is an inert fake; none of these requests should reach one.
 */
class WrongMethodStatusTest {

    private class InertControl : ServerControl {
        override val canRestart = false
        override fun spawnReplacement() = Unit
        override fun exit(): Nothing = throw IllegalStateException("inert fake: exit reached")
    }

    private class InertPeers : PeerRegistry {
        override fun list(): List<PeerDaemon> = emptyList()
        override fun kill(pid: Long, force: Boolean): KillOutcome = KillOutcome.Refused("inert fake")
    }

    private class InertMcp : McpControl {
        override fun info() = McpInfo(command = "inert", args = emptyList(), dataDir = "inert")
        override fun probe(timeoutMs: Long) = McpProbeResult(ok = false, error = "inert fake")
    }

    private class InertTransport : ReplayTransport {
        override fun send(method: String, url: String, headers: Map<String, String>, body: ByteArray?) =
            ReplayTransport.Response(status = 599, headers = emptyMap(), body = "", bodyTruncated = false)
    }

    private lateinit var tmp: Path
    private lateinit var config: DaemonConfig
    private lateinit var daemon: InspectorDaemon
    private lateinit var http: HttpClient

    @BeforeTest
    fun setUp() = runBlocking {
        tmp = Files.createTempDirectory("inspector-wrong-method")
        config = DaemonConfig(port = ServerSocket(0).use { it.localPort }, dataDir = tmp)
        Files.createDirectories(config.sessionsDir)
        val liveApps = LiveApps()
        daemon = InspectorDaemon(
            config,
            control = InertControl(),
            peers = InertPeers(),
            liveApps = liveApps,
            replayer = Replayer(SessionRepository(config), liveApps, InertTransport()),
            mcp = InertMcp(),
        )
        daemon.start(wait = false)
        http = HttpClient(CIO)
        withTimeoutOrNull(10_000) {
            while (runCatching { http.get(url("/api/server")).status.value }.getOrNull() != 200) delay(50)
        }
        Unit
    }

    @AfterTest
    fun tearDown() {
        runCatching { http.close() }
        runCatching { daemon.stop() }
        runCatching { tmp.toFile().deleteRecursively() }
    }

    private fun url(path: String) = "http://127.0.0.1:${config.port}$path"

    @Test
    fun `a wrong method on a served path is 405 whether or not a control route sits below it`() = runBlocking {
        val wrong = mutableListOf<String>()
        for (path in listOf("/api/sessions", "/api/server", "/api/peers", "/api/mcp", "/api/recording")) {
            for (method in listOf(HttpMethod.Put, HttpMethod.Post, HttpMethod.Delete)) {
                val status = runCatching { http.request(url(path)) { this.method = method }.status.value }.getOrNull()
                if (status != 405) wrong += "${method.value} $path → ${status ?: "no response"}"
            }
        }
        if (wrong.isNotEmpty()) fail("expected 405 for a method the path does not serve:\n  " + wrong.joinToString("\n  "))
    }

    /**
     * The three routes `item:web-foundation-shell` added, each beside a route on the same or a
     * neighbouring path. `PUT /api/config` is gated and `GET /api/config` is open on one path, which
     * is the shape the first gate got wrong; `POST /api/sessions/delete` sits beside
     * `DELETE /api/sessions/{id}`, so a GET on it must answer what a GET on its sibling
     * `/api/sessions/clear` always has.
     */
    @Test
    fun `the bulk and config routes answer a wrong method as their neighbours do`() = runBlocking {
        val wrong = mutableListOf<String>()
        fun expect(expected: Int?, method: HttpMethod, path: String, status: Int?) {
            if (status != expected) wrong += "${method.value} $path → ${status ?: "no response"}, expected $expected"
        }
        suspend fun status(method: HttpMethod, path: String) =
            runCatching { http.request(url(path)) { this.method = method }.status.value }.getOrNull()

        for (method in listOf(HttpMethod.Post, HttpMethod.Delete, HttpMethod.Patch)) {
            expect(405, method, "/api/config", status(method, "/api/config"))
        }
        for (method in listOf(HttpMethod.Get, HttpMethod.Put, HttpMethod.Delete)) {
            expect(405, method, "/api/peers/kill", status(method, "/api/peers/kill"))
        }
        val clear = status(HttpMethod.Get, "/api/sessions/clear")
        expect(clear, HttpMethod.Get, "/api/sessions/delete", status(HttpMethod.Get, "/api/sessions/delete"))
        // The control route itself is reached, and refused by the gate rather than by routing.
        expect(403, HttpMethod.Put, "/api/config", status(HttpMethod.Put, "/api/config"))

        if (wrong.isNotEmpty()) fail("wrong status beside the new routes:\n  " + wrong.joinToString("\n  "))
    }
}

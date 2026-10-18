package dev.inspector.daemon

import dev.inspector.daemon.archive.DaemonConfig
import dev.inspector.daemon.archive.SessionRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.server.routing.HttpHeaderRouteSelector
import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.getAllRoutes
import io.ktor.server.routing.path
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
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every route that changes something refuses without `X-Inspector-Control: 1`.
 *
 * The routes come from the server's own route tree, not from a list kept here. A list would
 * guard exactly the routes somebody remembered to add to it, and a new control route is the one
 * nobody has remembered yet; walking what Ktor actually registered finds it by existing.
 *
 * Every side effect a control route can reach is an inert fake. A broken guard has to show up as
 * a wrong status, never as damage: the real [ServerControl] ends the JVM, which here is the test
 * runner, and the real peer registry kills processes.
 */
class ControlHeaderCoverageTest {

    /**
     * Not GET, so a guard is owed, and deliberately left open. From `docs/decisions/daemon-and-mcp.md`:
     *
     * "Read-only endpoints are deliberately left open — `HttpMarkerPoster` and the MCP tools post
     * markers without it, and a marker is not a weapon."
     *
     * A new entry here widens what any web page can do to the daemon, so it is a roadmap
     * decision rather than an edit to make a test pass.
     */
    private val allowlist = setOf(HttpMethod.Post to "/api/sessions/{id}/markers")

    private class RecordingControl : ServerControl {
        override val canRestart = true
        val spawned = AtomicInteger()
        val exited = CountDownLatch(1)

        override fun spawnReplacement() {
            spawned.incrementAndGet()
        }

        override fun exit(): Nothing {
            exited.countDown()
            // The real exit never returns and the shutdown thread relies on that; ending the
            // thread keeps the fake honest without ending the test JVM.
            throw ExitReached()
        }
    }

    private class ExitReached : Throwable("fake exit", null, false, false)

    private class RecordingPeers : PeerRegistry {
        val kills = AtomicInteger()
        override fun list(): List<PeerDaemon> = emptyList()
        override fun kill(pid: Long, force: Boolean): KillOutcome {
            kills.incrementAndGet()
            return KillOutcome.Refused("inert fake")
        }
    }

    private class RecordingMcp : McpControl {
        val probes = AtomicInteger()
        override fun info() = McpInfo(command = "inert", args = emptyList(), dataDir = "inert")
        override fun probe(timeoutMs: Long): McpProbeResult {
            probes.incrementAndGet()
            return McpProbeResult(ok = false, error = "inert fake")
        }
    }

    private class RecordingTransport : ReplayTransport {
        val sends = AtomicInteger()
        override fun send(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: ByteArray?,
        ): ReplayTransport.Response {
            sends.incrementAndGet()
            return ReplayTransport.Response(status = 599, headers = emptyMap(), body = "", bodyTruncated = false)
        }
    }

    /** One registered endpoint. A null [method] matches any method, so it owes a guard too. */
    private data class Endpoint(val method: HttpMethod?, val template: String, val webSocket: Boolean) {
        val label get() = "${method?.value ?: "ANY"} $template"
    }

    private lateinit var tmp: Path
    private lateinit var config: DaemonConfig
    private lateinit var daemon: InspectorDaemon
    private lateinit var http: HttpClient
    private val control = RecordingControl()
    private val peers = RecordingPeers()
    private val mcp = RecordingMcp()
    private val transport = RecordingTransport()

    @BeforeTest
    fun setUp() = runBlocking {
        tmp = Files.createTempDirectory("inspector-control-coverage")
        config = DaemonConfig(port = ServerSocket(0).use { it.localPort }, dataDir = tmp)
        Files.createDirectories(config.sessionsDir)
        val liveApps = LiveApps()
        daemon = InspectorDaemon(
            config,
            control = control,
            peers = peers,
            liveApps = liveApps,
            replayer = Replayer(SessionRepository(config), liveApps, transport),
            mcp = mcp,
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
        try {
            assertNothingWasReached()
        } finally {
            runCatching { http.close() }
            runCatching { daemon.stop() }
            runCatching { tmp.toFile().deleteRecursively() }
        }
    }

    private fun url(path: String) = "http://127.0.0.1:${config.port}$path"

    /** A null root reads as nothing registered, which the scan then refuses to call a pass. */
    private fun endpoints(): List<Endpoint> {
        val root = daemon.routingRoot() ?: return emptyList()
        return root.getAllRoutes().map { node ->
            val lineage = node.lineage().filterIsInstance<RoutingNode>().map { it.selector }.toList()
            // A webSocket route's handler sits a few levels below its GET node, under the
            // Connection and Upgrade header selectors, so the method is the first one up.
            val method = lineage.filterIsInstance<HttpMethodRouteSelector>().firstOrNull()?.method
            val webSocket = lineage.filterIsInstance<HttpHeaderRouteSelector>()
                .any { it.name.equals("Upgrade", ignoreCase = true) }
            Endpoint(method, node.path, webSocket)
        }.distinct().sortedBy { it.label }
    }

    private fun concrete(template: String) = template.replace(Regex("""\{[^}]*\}"""), "phase-c-probe")

    /** Status and body, or a description of why there was none. A dead daemon is a miss too. */
    private suspend fun send(method: HttpMethod, template: String, controlValue: String?): Pair<Int?, String> =
        try {
            val response = http.request(url(concrete(template))) {
                this.method = method
                if (controlValue != null) header("X-Inspector-Control", controlValue)
            }
            response.status.value to response.bodyAsText()
        } catch (e: Exception) {
            null to "no response: ${e::class.simpleName}: ${e.message}"
        }

    @Test
    fun `every non-GET route refuses without the control header`() = runBlocking {
        val all = endpoints()
        val probed = all.filter { it.method != HttpMethod.Get && (it.method to it.template) !in allowlist }

        println("ControlHeaderCoverageTest probed ${probed.size} route(s):")
        probed.forEach { println("  ${it.label}") }
        println("exempt as WebSocket handshakes (GET, no header possible):")
        all.filter { it.webSocket }.forEach { println("  ${it.label}") }
        println("allowlisted: ${allowlist.joinToString { "${it.first.value} ${it.second}" }}")

        // A guard that scans nothing must fail, not pass.
        assertTrue(probed.isNotEmpty(), "the route scan found no non-GET routes; it checked nothing")
        // A partial scan must not pass either. Stop is the route whose loss matters most, and it
        // is registered in a nested builder, so its absence would mean the walk stopped short.
        assertTrue(
            probed.any { it.method == HttpMethod.Post && it.template == "/api/server/stop" },
            "the route scan missed POST /api/server/stop; it is not walking the whole tree",
        )

        val misses = mutableListOf<String>()
        for (endpoint in probed) {
            // A route with no method selector answers any method; POST is what a page can send.
            val method = endpoint.method ?: HttpMethod.Post
            for (value in listOf(null, "0")) {
                val (status, body) = send(method, endpoint.template, value)
                val how = if (value == null) "no header" else "X-Inspector-Control: $value"
                if (status != 403) {
                    misses += "${endpoint.label} → ${status ?: body} ($how)"
                } else if (!body.contains("X-Inspector-Control")) {
                    // A 403 from something other than the guard is not the guard.
                    misses += "${endpoint.label} → 403 without naming the header ($how): $body"
                }
            }
        }
        if (misses.isNotEmpty()) {
            fail("control routes answered without the header:\n  " + misses.joinToString("\n  "))
        }
    }

    @Test
    fun `every constructor dependency is faked here`() {
        // `control` and `peers` default to real implementations that end the JVM and kill
        // processes, so a dependency added to the constructor would run for real in the probe
        // unless this file fakes it. Counted on the widest non-synthetic constructor, which is
        // the Kotlin primary; the synthetic one carries the default-argument mask.
        val widest = InspectorDaemon::class.java.declaredConstructors
            .filterNot { it.isSynthetic }
            .maxOf { it.parameterCount }
        assertEquals(
            FAKED_CONSTRUCTOR_PARAMETERS,
            widest,
            "InspectorDaemon's constructor now takes $widest parameters, not " +
                "$FAKED_CONSTRUCTOR_PARAMETERS: new dependency: fake it here if it has side effects",
        )
    }

    @Test
    fun `the allowlist names registered routes and they answer without the header`() = runBlocking {
        val registered = endpoints().map { it.method to it.template }.toSet()
        for ((method, template) in allowlist) {
            assertTrue((method to template) in registered, "allowlisted $method $template is not a registered route")
            val (status, body) = send(method, template, controlValue = null)
            assertNotEquals(null, status, "${method.value} $template gave $body")
            assertNotEquals(403, status, "allowlisted ${method.value} $template refused without the header")
        }
    }

    /**
     * Runs after every test, including a failed one, so a broken guard reports its damage too.
     * Every side effect reached is named in one failure, not just the first.
     */
    private fun assertNothingWasReached() {
        // Shutdown runs on its own thread after the response flushes, then stops the engine, so
        // an exit from a broken guard can land a second or more after the probe that caused it.
        val exited = control.exited.await(2, TimeUnit.SECONDS)
        // Two broken lifecycle routes start two shutdown threads, and the first exit says nothing
        // about whether the other has spawned yet. Let it land, so the report names both.
        if (exited) Thread.sleep(1_500)
        val reached = buildList {
            if (exited) add("ServerControl.exit")
            if (control.spawned.get() > 0) add("ServerControl.spawnReplacement ×${control.spawned.get()}")
            if (peers.kills.get() > 0) add("PeerRegistry.kill ×${peers.kills.get()}")
            if (mcp.probes.get() > 0) add("McpControl.probe ×${mcp.probes.get()}")
            if (transport.sends.get() > 0) add("the replay transport ×${transport.sends.get()}")
        }
        if (reached.isNotEmpty()) fail("probes reached side effects: ${reached.joinToString(", ")}")
    }

    private companion object {
        /** config, control, peers, liveApps, replayer, mcp — every one set explicitly in [setUp]. */
        const val FAKED_CONSTRUCTOR_PARAMETERS = 6
    }
}

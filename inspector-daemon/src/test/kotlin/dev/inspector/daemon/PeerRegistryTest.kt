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
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Peer discovery and the kill endpoint.
 *
 * The argv tests are the important half. Identifying sibling processes by argv token rather than
 * by a substring of the command line is the whole reason this feature is safer than the `pkill`
 * it replaces, and both traps it avoids are real ones that were hit by hand.
 */
class PeerRegistryTest {

    // --- argv parsing ----------------------------------------------------------------------

    /** The real command line of a running `inspector mcp`, trimmed to its shape. */
    private fun launchArgs(vararg tail: String) = listOf(
        "-classpath",
        "/x/lib/inspector-daemon-0.1.0-SNAPSHOT.jar:/x/lib/ktor-server-cio-jvm-3.5.0.jar",
        "dev.inspector.daemon.MainKt",
    ) + tail

    @Test
    fun `the subcommand is read from the token after the main class`() {
        assertEquals("serve", PeerArgv.roleOf(launchArgs("serve")))
        assertEquals("mcp", PeerArgv.roleOf(launchArgs("mcp")))
        assertEquals("sessions", PeerArgv.roleOf(launchArgs("sessions")))
    }

    @Test
    fun `a process that is not an inspector launch is not a peer`() {
        assertNull(PeerArgv.roleOf(listOf("-jar", "something-else.jar")))
        assertNull(PeerArgv.roleOf(emptyList()))
    }

    /**
     * `pkill -f inspector` also matches the editor's MCP connection, and `pkill -f "inspector.*serve"`
     * matches it too, because the classpath contains `ktor-server-cio-*.jar` and "server" contains
     * "serve". Token equality is what makes those two traps unreachable, so it is asserted directly.
     */
    @Test
    fun `a serve-shaped substring in the classpath does not make an mcp process a server`() {
        val mcp = launchArgs("mcp")
        assertTrue(mcp.any { it.contains("ktor-server-cio") }, "test needs the trap in its input")
        assertEquals("mcp", PeerArgv.roleOf(mcp), "the classpath must not decide the role")
        assertNull(PeerArgv.portOf(PeerArgv.roleOf(mcp)!!, mcp), "mcp speaks over stdio and holds no port")
    }

    @Test
    fun `an explicit port flag is read, and only for roles that listen`() {
        val serve = launchArgs("serve", "--port", "9123", "--data", "/tmp/somewhere")
        assertEquals("9123", PeerArgv.flag(serve, "port"))
        assertEquals("/tmp/somewhere", PeerArgv.flag(serve, "data"))
        assertEquals(9123, PeerArgv.portOf("serve", serve))
    }

    @Test
    fun `a flag with no value does not swallow the next flag`() {
        val args = launchArgs("serve", "--data", "--port", "9123")
        assertNull(PeerArgv.flag(args, "data"), "a valueless --data must read as absent")
        assertEquals("9123", PeerArgv.flag(args, "port"))
    }

    @Test
    fun `a serve peer with no port flag resolves the same default the peer itself would`() {
        assertEquals(DaemonConfig.DEFAULT_PORT, PeerArgv.portOf("serve", launchArgs("serve")))
    }

    // --- the endpoints ---------------------------------------------------------------------

    private class FakeRegistry(private val peers: List<PeerDaemon>) : PeerRegistry {
        var killed: Pair<Long, Boolean>? = null
        var outcome: KillOutcome = KillOutcome.Signalled

        override fun list() = peers
        override fun kill(pid: Long, force: Boolean): KillOutcome {
            killed = pid to force
            return outcome
        }
    }

    private lateinit var tmp: Path
    private lateinit var config: DaemonConfig
    private lateinit var daemon: InspectorDaemon
    private lateinit var http: HttpClient

    private fun start(registry: PeerRegistry) = runBlocking {
        tmp = Files.createTempDirectory("inspector-peers")
        config = DaemonConfig(port = ServerSocket(0).use { it.localPort }, dataDir = tmp)
        Files.createDirectories(config.sessionsDir)
        daemon = InspectorDaemon(config, peers = registry)
        daemon.start(wait = false)
        http = HttpClient(CIO)
        withTimeoutOrNull(10_000) {
            while (runCatching { http.get(url("/api/server")).status.value }.getOrNull() != 200) delay(50)
        }
        Unit
    }

    private fun url(path: String) = "http://127.0.0.1:${config.port}$path"

    @AfterTest
    fun tearDown() {
        runCatching { http.close() }
        runCatching { daemon.stop() }
        runCatching { tmp.toFile().deleteRecursively() }
    }

    @Test
    fun `the peer list is serialized, including a path that needs escaping`() = runBlocking {
        start(
            FakeRegistry(
                listOf(
                    PeerDaemon(101, "serve", 8099, """/tmp/od"d\path""", 1_700_000_000_000, self = true),
                    PeerDaemon(202, "mcp", null, null, null, self = false),
                )
            )
        )
        val body = http.get(url("/api/peers")).bodyAsText()
        assertTrue(body.startsWith("["), "must be a JSON array")

        // Round-tripped rather than string-matched: the point is that a path containing a quote
        // and a backslash survives intact, and asserting that against the raw text turns into an
        // escaping puzzle that tests the test rather than the code.
        val decoded = InspectorDaemonJson.decodeFromString(
            ListSerializer(PeerDaemon.serializer()),
            body,
        )
        assertEquals(2, decoded.size)
        assertEquals(101L, decoded[0].pid)
        assertEquals("""/tmp/od"d\path""", decoded[0].dataDir, "the path must survive escaping")
        assertTrue(decoded[0].self)
        assertEquals("mcp", decoded[1].role)
        assertNull(decoded[1].port, "an mcp peer holds no port")
    }

    @Test
    fun `killing requires the control header`() = runBlocking {
        val registry = FakeRegistry(emptyList())
        start(registry)
        val response = http.post(url("/api/peers/404/kill"))
        assertEquals(403, response.status.value)
        assertNull(registry.killed, "an unheadered request must not reach the registry")
    }

    @Test
    fun `a headered kill reaches the registry and reports success`() = runBlocking {
        val registry = FakeRegistry(emptyList())
        start(registry)
        val response = http.post(url("/api/peers/777/kill")) {
            header("X-Inspector-Control", "1")
        }
        assertEquals(200, response.status.value)
        assertEquals(777L to false, registry.killed)
    }

    @Test
    fun `force is passed through`() = runBlocking {
        val registry = FakeRegistry(emptyList())
        start(registry)
        http.post(url("/api/peers/777/kill?force=true")) { header("X-Inspector-Control", "1") }
        assertEquals(777L to true, registry.killed)
    }

    @Test
    fun `a refusal is a conflict carrying the reason, not a silent success`() = runBlocking {
        val registry = FakeRegistry(emptyList())
        registry.outcome = KillOutcome.Refused("pid 5 belongs to someone-else, not me")
        start(registry)
        val response = http.post(url("/api/peers/5/kill")) { header("X-Inspector-Control", "1") }
        assertEquals(409, response.status.value)
        assertContains(response.bodyAsText(), "belongs to someone-else")
    }

    @Test
    fun `a dead pid reports not found`() = runBlocking {
        val registry = FakeRegistry(emptyList())
        registry.outcome = KillOutcome.NoSuchProcess
        start(registry)
        assertEquals(404, http.post(url("/api/peers/5/kill")) { header("X-Inspector-Control", "1") }.status.value)
    }

    @Test
    fun `a non-numeric pid is rejected before the registry sees it`() = runBlocking {
        val registry = FakeRegistry(emptyList())
        start(registry)
        val response = http.post(url("/api/peers/notapid/kill")) { header("X-Inspector-Control", "1") }
        assertEquals(400, response.status.value)
        assertNull(registry.killed)
    }

    // --- the real registry, read-only ------------------------------------------------------

    /**
     * The real registry must always find at least this JVM's own... except it will not: the test
     * runner is a Gradle worker, not an `inspector` launch. So the honest assertion is that it
     * runs, returns a well-formed list, and never reports a non-inspector process as a peer.
     */
    @Test
    fun `the real registry enumerates without claiming unrelated processes`() {
        val listed = ProcessHandlePeerRegistry().list()
        assertTrue(listed.all { it.role.isNotBlank() }, "every peer must carry a role")
        assertTrue(listed.none { it.role == "mcp" && it.port != null }, "mcp peers hold no port")
    }

    /** Refusing to kill itself matters: the clean path is `/api/server/stop`. */
    @Test
    fun `the real registry refuses to kill this process`() {
        val outcome = ProcessHandlePeerRegistry().kill(ProcessHandle.current().pid(), force = false)
        assertTrue(outcome is KillOutcome.Refused, "self-kill must be refused, was $outcome")
        assertContains((outcome as KillOutcome.Refused).reason, "Stop")
    }
}

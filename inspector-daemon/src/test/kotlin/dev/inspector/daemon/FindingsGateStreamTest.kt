package dev.inspector.daemon

import dev.inspector.daemon.archive.DaemonConfig
import dev.inspector.daemon.archive.SessionRepository
import dev.inspector.daemon.http.InspectorDaemon
import dev.inspector.stream.StreamSink
import dev.inspector.stream.StreamState
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The CI recipe in `docs/DAEMON.md` §7, in code: a test streams its calls through the real
 * [StreamSink] into a daemon it started, waits until the daemon holds them, checks nothing was
 * dropped, stops the sink, and runs `inspector findings --fail-on high`. And the hazard the recipe
 * warns of: a sink stopped before its rows landed leaves a session with no calls, which the gate
 * refuses with 2 rather than passing.
 */
class FindingsGateStreamTest {

    private lateinit var tmp: Path
    private lateinit var config: DaemonConfig
    private lateinit var repo: SessionRepository
    private var daemon: InspectorDaemon? = null
    private var sink: StreamSink? = null

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("inspector-findings-gate-stream")
        config = DaemonConfig(port = ServerSocket(0).use { it.localPort }, dataDir = tmp)
        repo = SessionRepository(config)
        daemon = InspectorDaemon(config).also { it.start(wait = false) }
    }

    @AfterTest
    fun tearDown() {
        sink?.stop()
        daemon?.stop()
        tmp.toFile().deleteRecursively()
    }

    /** A sink connected to the daemon, with the session its hello opened already on disk. */
    private fun connectedSink(): StreamSink = runBlocking {
        val sink = StreamSink(client = clientInfo(), host = "127.0.0.1", port = config.port).also { it.start(); sink = it }
        withTimeoutOrNull(10_000) {
            while (sink.state.value != StreamState.Connected || repo.sessionDirs().isEmpty()) delay(20)
        } ?: error("sink never connected (was ${sink.state.value})")
        sink
    }

    private fun StreamSink.send(call: Captured) = onTransaction(call.row, call.body, null)

    /** What the recipe's test does before `stop()`: wait for the rows, then check none was dropped. */
    private fun StreamSink.drainThenStop(rows: Int) = runBlocking {
        val dir = repo.sessionDirs().single()
        withTimeoutOrNull(10_000) { while (repo.readTransactions(dir).size < rows) delay(30) }
            ?: error("expected $rows rows, saw ${repo.readTransactions(dir).size}")
        assertEquals(0L, dropped.value)
        stop()
    }

    private fun gate(): Pair<Int, String> {
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val process = ProcessBuilder(
            java, "-cp", System.getProperty("java.class.path"), "dev.inspector.daemon.MainKt",
            "findings", "--data", tmp.toString(), "--session", "latest", "--fail-on", "high",
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        val stderr = process.errorStream.bufferedReader().readText()
        return process.waitFor() to stderr
    }

    @Test
    fun `a double submit streamed into the daemon fails the gate`() {
        val sink = connectedSink()
        sink.send(mutation("s0000001", 1_000, "c-dup-a"))
        sink.send(mutation("s0000002", 2_200, "c-dup-b"))
        sink.drainThenStop(2)
        val (exit, stderr) = gate()
        assertEquals(1, exit, stderr)
        assertTrue("payment.duplicate-mutation" in stderr, stderr)
    }

    @Test
    fun `a single submit passes it`() {
        val sink = connectedSink()
        sink.send(mutation("s0000001", 1_000, "c-once"))
        sink.drainThenStop(1)
        val (exit, stderr) = gate()
        assertEquals(0, exit, stderr)
    }

    @Test
    fun `a sink stopped before its rows landed leaves a session the gate cannot judge`() {
        val sink = connectedSink()
        // Stopped first, so the rows are lost for certain rather than by a race: what a test that
        // never waited for delivery hands the gate at worst. Without the empty-session rule this reads 0.
        sink.stop()
        sink.send(mutation("s0000001", 1_000, "c-dup-a"))
        sink.send(mutation("s0000002", 2_200, "c-dup-b"))
        assertEquals(0, repo.readTransactions(repo.sessionDirs().single()).size)
        val (exit, stderr) = gate()
        assertEquals(2, exit, stderr)
        assertTrue("holds no calls" in stderr, stderr)
    }
}

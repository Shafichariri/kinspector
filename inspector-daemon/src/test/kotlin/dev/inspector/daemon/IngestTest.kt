package dev.inspector.daemon

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
 * Device → daemon round trip, driving the real [StreamSink] against the real daemon rather than
 * a stand-in, because the parts most likely to break — the handshake, resume, and surviving the
 * daemon going away — only exist at the seam between them.
 */
class IngestTest {

    private lateinit var tmp: Path
    private lateinit var config: DaemonConfig
    private lateinit var repo: SessionRepository
    private var daemon: InspectorDaemon? = null
    private var sink: StreamSink? = null

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("inspector-ingest")
        config = DaemonConfig(port = freePort(), dataDir = tmp)
        repo = SessionRepository(config)
    }

    @AfterTest
    fun tearDown() {
        sink?.stop()
        daemon?.stop()
        Files.walk(tmp).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun startDaemon(): InspectorDaemon =
        InspectorDaemon(config).also { it.start(wait = false); daemon = it }

    private fun startSink(): StreamSink =
        StreamSink(client = clientInfo(), host = "127.0.0.1", port = config.port)
            .also { it.start(); sink = it }

    private suspend fun awaitState(sink: StreamSink, state: StreamState, timeoutMs: Long = 10_000) {
        withTimeoutOrNull(timeoutMs) {
            while (sink.state.value != state) delay(20)
        } ?: error("sink never reached $state (was ${sink.state.value})")
    }

    private suspend fun awaitSessions(count: Int, timeoutMs: Long = 10_000): List<Path> {
        val found = withTimeoutOrNull(timeoutMs) {
            while (repo.sessionDirs().size < count) delay(30)
            repo.sessionDirs()
        }
        return found ?: repo.sessionDirs()
    }

    private suspend fun awaitTransactions(dir: Path, count: Int, timeoutMs: Long = 10_000) {
        withTimeoutOrNull(timeoutMs) {
            while (repo.readTransactions(dir).size < count) delay(30)
        } ?: error("expected $count transactions, saw ${repo.readTransactions(dir).size}")
    }

    @Test
    fun transactions_stream_from_the_sink_to_a_session_folder_on_disk() = runBlocking {
        startDaemon()
        val sink = startSink()
        awaitState(sink, StreamState.Connected)

        sink.onTransaction(txn("aaaa1111", path = "/v2/users/me"), null, """{"ok":true}""".toByteArray())
        sink.onTransaction(txn("bbbb2222", path = "/v2/orders", status = 500), null, null)
        sink.onMarker(marker("tapped checkout"))

        val dir = awaitSessions(1).single()
        awaitTransactions(dir, 2)

        val stored = repo.readTransactions(dir)
        assertEquals(setOf("aaaa1111", "bbbb2222"), stored.map { it.id }.toSet())

        // Bodies ride inline on the wire and the daemon writes them out, filling in the ref.
        val withBody = stored.first { it.id == "aaaa1111" }
        assertEquals("bodies/aaaa1111.res", withBody.resBodyRef)
        assertEquals("""{"ok":true}""", repo.readBody(dir, "aaaa1111", "res")!!.decodeToString())

        withTimeoutOrNull(5_000) { while (repo.readMarkers(dir).isEmpty()) delay(30) }
        assertEquals(listOf("tapped checkout"), repo.readMarkers(dir).map { it.label })
    }

    @Test
    fun the_latest_link_points_at_the_running_session() = runBlocking {
        startDaemon()
        val sink = startSink()
        awaitState(sink, StreamState.Connected)
        sink.onTransaction(txn("aaaa1111"), null, null)

        val dir = awaitSessions(1).single()
        awaitTransactions(dir, 1)

        assertEquals(dir.toRealPath(), repo.resolve("latest"))
    }

    @Test
    fun binary_bodies_survive_the_round_trip_as_base64() = runBlocking {
        startDaemon()
        val sink = startSink()
        awaitState(sink, StreamState.Connected)

        // Deliberately not valid UTF-8, so the sink must switch to base64 rather than mangle it.
        val binary = ByteArray(256) { it.toByte() }
        sink.onTransaction(txn("cccc3333"), null, binary)

        val dir = awaitSessions(1).single()
        awaitTransactions(dir, 1)

        val restored = repo.readBody(dir, "cccc3333", "res")
        assertTrue(binary.contentEquals(restored), "binary body must round trip byte for byte")
    }

    @Test
    fun the_app_is_unaffected_when_the_daemon_goes_away_mid_session() = runBlocking {
        startDaemon()
        val sink = startSink()
        awaitState(sink, StreamState.Connected)
        sink.onTransaction(txn("aaaa1111"), null, null)
        val dir = awaitSessions(1).single()
        awaitTransactions(dir, 1)

        daemon?.stop()
        daemon = null
        awaitState(sink, StreamState.Disconnected)

        // The whole efficiency contract in one assertion: submitting with no daemon must not
        // block, throw, or otherwise reach back into the app.
        repeat(100) { sink.onTransaction(txn("dead%04d".format(it)), null, null) }

        assertEquals(1, repo.readTransactions(dir).size, "nothing more should have been written")
    }

    @Test
    fun reconnecting_within_the_grace_period_resumes_the_same_session_folder() = runBlocking {
        startDaemon()
        val sink = startSink()
        awaitState(sink, StreamState.Connected)
        sink.onTransaction(txn("aaaa1111"), null, null)
        val firstDir = awaitSessions(1).single()
        awaitTransactions(firstDir, 1)

        daemon?.stop()
        awaitState(sink, StreamState.Disconnected)

        startDaemon()
        awaitState(sink, StreamState.Connected)
        sink.onTransaction(txn("bbbb2222"), null, null)
        awaitTransactions(firstDir, 2)

        assertEquals(1, repo.sessionDirs().size, "a resume must not create a second folder")
        assertEquals(
            setOf("aaaa1111", "bbbb2222"),
            repo.readTransactions(firstDir).map { it.id }.toSet(),
        )
    }

    @Test
    fun a_fresh_client_after_the_grace_period_starts_a_new_session() = runBlocking {
        startDaemon()
        val first = startSink()
        awaitState(first, StreamState.Connected)
        first.onTransaction(txn("aaaa1111"), null, null)
        awaitTransactions(awaitSessions(1).single(), 1)
        first.stop()

        // A new sink has no resumeSessionId, which is what an app relaunch looks like.
        val second = StreamSink(clientInfo(), host = "127.0.0.1", port = config.port).also {
            it.start(); sink = it
        }
        awaitState(second, StreamState.Connected)
        second.onTransaction(txn("bbbb2222"), null, null)

        val dirs = awaitSessions(2)
        assertEquals(2, dirs.size, "a relaunch must get its own session folder")
    }
}

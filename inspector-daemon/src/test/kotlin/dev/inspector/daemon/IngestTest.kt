package dev.inspector.daemon

import dev.inspector.stream.ReplaySigner
import dev.inspector.stream.StreamSink
import dev.inspector.stream.StreamState
import kotlinx.coroutines.async
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

    private fun startSink(signer: ReplaySigner? = null): StreamSink =
        StreamSink(client = clientInfo(), host = "127.0.0.1", port = config.port, signer = signer)
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

/**
 * The re-signing round trip, over a real WebSocket.
 *
 * Deliberately not a fake connection. The whole feature rests on `/ingest` being genuinely
 * bidirectional — the app's receive loop existed only to notice a dead daemon and threw every
 * frame away — so a test that stubs the socket would prove nothing about the thing that changed.
 */
class SignRoundTripTest {

    private lateinit var tmp: Path
    private lateinit var config: DaemonConfig
    private var daemon: InspectorDaemon? = null
    private var sink: StreamSink? = null

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("inspector-sign")
        config = DaemonConfig(port = ServerSocket(0).use { it.localPort }, dataDir = tmp)
    }

    @AfterTest
    fun tearDown() {
        sink?.stop()
        daemon?.stop()
        Files.walk(tmp).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    private suspend fun attach(signer: ReplaySigner?): LiveApps.Connection {
        val started = InspectorDaemon(config).also { it.start(wait = false); daemon = it }
        sink = StreamSink(clientInfo(), "127.0.0.1", config.port, signer = signer).also { it.start() }
        return withTimeoutOrNull(10_000) {
            var found: LiveApps.Connection? = null
            while (found == null) {
                found = started.liveApps.sole()
                if (found == null) delay(20)
            }
            found
        } ?: error("the app never registered with the daemon")
    }

    @Test
    fun `the daemon asks the running app for headers and gets them back over the socket`() = runBlocking {
        val seen = mutableListOf<Pair<String, String>>()
        val connection = attach { method, url ->
            seen += method to url
            mapOf("X-Device-Timestamp" to "1787", "X-Device-Nonce" to "fresh", "X-Device-Signature" to "sig")
        }

        val headers = connection.requestHeaders("POST", "https://api.example.com/v2/orders")

        assertEquals(listOf("POST" to "https://api.example.com/v2/orders"), seen, "the app must be told what it is signing")
        assertEquals("1787", headers["X-Device-Timestamp"])
        assertEquals("fresh", headers["X-Device-Nonce"])
        assertEquals("sig", headers["X-Device-Signature"])
    }

    /** Without a signer the app must answer, not go quiet and leave the host on its timeout. */
    @Test
    fun `an app with no signer answers with a reason rather than nothing`() = runBlocking {
        val connection = attach(signer = null)
        val failure = runCatching { connection.requestHeaders("GET", "https://api.example.com/x", timeoutMs = 5_000) }
        val message = failure.exceptionOrNull()?.message ?: error("expected a refusal, got ${failure.getOrNull()}")
        // Specifically the app's own wording, not the host's timeout text — which also mentions
        // ReplaySigner, and so would let this pass even if the app never answered at all.
        assertTrue(
            "on this StreamSink" in message,
            "the app must answer for itself rather than the host timing out: $message",
        )
        assertTrue("did not answer" !in message, "this must not be a timeout: $message")
    }

    /** A signer that throws is a reported failure, never a silently unsigned request. */
    @Test
    fun `a throwing signer is reported to the host`() = runBlocking {
        val connection = attach { _, _ -> error("keystore unavailable") }
        val failure = runCatching { connection.requestHeaders("GET", "https://api.example.com/x", timeoutMs = 5_000) }
        val message = failure.exceptionOrNull()?.message ?: error("expected a refusal")
        assertTrue("keystore unavailable" in message, "the cause must survive: $message")
    }

    /** Concurrent requests must not cross-talk; correlation is by requestId, not by arrival order. */
    @Test
    fun `two overlapping requests each get their own answer`() = runBlocking {
        val connection = attach { method, url ->
            // Answer the slower one first, so a queue-order implementation would swap them.
            if (method == "GET") delay(300)
            mapOf("X-Echo" to "$method $url")
        }

        val first = async { connection.requestHeaders("GET", "https://api.example.com/slow") }
        val second = async { connection.requestHeaders("POST", "https://api.example.com/fast") }

        assertEquals("GET https://api.example.com/slow", first.await()["X-Echo"])
        assertEquals("POST https://api.example.com/fast", second.await()["X-Echo"])
    }
}

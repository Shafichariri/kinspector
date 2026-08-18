package dev.inspector.daemon

import dev.inspector.model.BodyOmission
import dev.inspector.model.SignRequest
import dev.inspector.model.SignResponse
import dev.inspector.model.WireMsg
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Replay, and the re-signing round trip it depends on.
 *
 * The refusals matter as much as the happy path. Every one of them could be papered over — send a
 * truncated body, send an empty one, send `***` as a bearer token — and each would fail at the
 * server looking like an application bug rather than a replay that was never valid.
 */
class ReplayTest {

    private lateinit var tmp: Path
    private lateinit var config: DaemonConfig
    private lateinit var repository: SessionRepository
    private lateinit var sessionId: String

    /** Records what was sent and returns a canned response. */
    private class FakeTransport(
        var status: Int = 200,
        var body: String = """{"ok":true}""",
    ) : ReplayTransport {
        val sent = ConcurrentLinkedQueue<Sent>()
        var thrown: Exception? = null

        data class Sent(
            val method: String,
            val url: String,
            val headers: Map<String, String>,
            val body: String?,
        )

        override fun send(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: ByteArray?,
        ): ReplayTransport.Response {
            sent += Sent(method, url, headers, body?.decodeToString())
            thrown?.let { throw it }
            return ReplayTransport.Response(status, emptyMap(), body = this.body, bodyTruncated = false)
        }

        val last: Sent get() = sent.last()
    }

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("inspector-replay")
        config = DaemonConfig(port = 8099, dataDir = tmp)
        Files.createDirectories(config.sessionsDir)

        sessionId = "2026-08-18T09-00-00_app_dev_debug"
        val dir = config.sessionsDir.resolve(sessionId)
        val meta = clientInfo(appId = "com.example.bank").toSessionMeta(sessionId, "2026-08-18T09:00:00.000Z")
        val writer = SessionWriter(dir, meta)

        writer.append(
            txn(
                "plain001", path = "/v2/orders", mono = 100, status = 200, method = "POST",
            ).copy(
                reqHeaders = mapOf(
                    "Content-Type" to listOf("application/json"),
                    "X-Device-Timestamp" to listOf("1000000"),
                    "X-Device-Nonce" to listOf("stalenonce"),
                    // Must never be copied onto a new connection.
                    "Host" to listOf("api.example.com"),
                    "Content-Length" to listOf("14"),
                    "Accept-Encoding" to listOf("gzip"),
                ),
            ),
            """{"cart":"c-1"}""".toByteArray(),
            null,
        )
        writer.append(
            txn("trunc002", path = "/v2/upload", mono = 200, method = "POST")
                .copy(reqBodyTruncated = true, reqBytes = 900_000),
            "prefix".toByteArray(),
            null,
        )
        writer.append(
            txn("stream003", path = "/v2/stream", mono = 300, method = "POST")
                .copy(reqBodyOmitted = BodyOmission.STREAMING, reqBytes = 5_000),
            null, null,
        )
        writer.append(
            txn("redact004", path = "/v2/login", mono = 400, method = "POST")
                .copy(redacted = listOf("req.header.authorization")),
            null, null,
        )
        writer.close()
        SessionWriter.updateLatestLink(config.dataDir, dir)
        repository = SessionRepository(config)
    }

    @AfterTest
    fun tearDown() {
        Files.walk(tmp).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    /** A live app that answers sign requests with fresh values, like a device would. */
    private fun CoroutineScopeHolder.attachApp(
        liveApps: LiveApps,
        answer: (SignRequest) -> SignResponse,
    ): LiveApps.Connection {
        lateinit var connection: LiveApps.Connection
        connection = liveApps.register(sessionId) { outbound ->
            if (outbound is SignRequest) {
                scope.launch { liveApps.complete(sessionId, answer(outbound)) }
            }
        }
        return connection
    }

    private class CoroutineScopeHolder(val scope: kotlinx.coroutines.CoroutineScope)

    // --- refusals ----------------------------------------------------------------------------

    @Test
    fun `a truncated request body is refused rather than sent as a prefix`() = runBlocking {
        val result = Replayer(repository, LiveApps(), FakeTransport())
            .replay(ReplayRequest(txnId = "trunc002", session = sessionId, resign = false))
        assertFalse(result.ok)
        assertContains(result.error!!, "truncated")
        assertContains(result.error, "corrupt body")
    }

    @Test
    fun `a streamed request body is refused, because nothing was captured`() = runBlocking {
        val result = Replayer(repository, LiveApps(), FakeTransport())
            .replay(ReplayRequest(txnId = "stream003", session = sessionId, resign = false))
        assertFalse(result.ok)
        assertContains(result.error!!, "streamed")
    }

    @Test
    fun `a redacted capture is refused rather than sending the masks`() = runBlocking {
        val result = Replayer(repository, LiveApps(), FakeTransport())
            .replay(ReplayRequest(txnId = "redact004", session = sessionId, resign = false))
        assertFalse(result.ok)
        assertContains(result.error!!, "redacted")
    }

    /** Supplying the body by hand removes the reason the capture was unusable. */
    @Test
    fun `editing the body lifts the truncation refusal`() = runBlocking {
        val transport = FakeTransport()
        val result = Replayer(repository, LiveApps(), transport).replay(
            ReplayRequest(txnId = "trunc002", session = sessionId, resign = false, body = "the whole thing"),
        )
        assertTrue(result.ok, "expected a send, got: ${result.error}")
        assertEquals("the whole thing", transport.last.body)
    }

    // --- header hygiene ----------------------------------------------------------------------

    @Test
    fun `connection-level headers are not copied onto the replay`() = runBlocking {
        val transport = FakeTransport()
        Replayer(repository, LiveApps(), transport)
            .replay(ReplayRequest(txnId = "plain001", session = sessionId, resign = false))

        val names = transport.last.headers.keys.map { it.lowercase() }
        for (banned in listOf("host", "content-length", "accept-encoding")) {
            assertFalse(banned in names, "$banned must not be replayed; sent: $names")
        }
        assertTrue("content-type" in names, "real headers must survive; sent: $names")
    }

    // --- re-signing --------------------------------------------------------------------------

    @Test
    fun `the app regenerates per-request headers and they replace the captured ones`() = runTest {
        val liveApps = LiveApps()
        val holder = CoroutineScopeHolder(this)
        holder.attachApp(liveApps) { request ->
            SignResponse(
                requestId = request.requestId,
                headers = mapOf(
                    "X-Device-Timestamp" to "9999999",
                    "X-Device-Nonce" to "freshnonce",
                    "X-Device-Signature" to "sig-for-${request.method}-${request.url.substringAfter("://")}",
                ),
            )
        }

        val transport = FakeTransport()
        val result = Replayer(repository, liveApps, transport)
            .replay(ReplayRequest(txnId = "plain001", session = sessionId, resign = true))

        assertTrue(result.ok, "expected a send, got: ${result.error}")
        assertEquals("9999999", transport.last.headers["X-Device-Timestamp"], "stale timestamp was replayed")
        assertEquals("freshnonce", transport.last.headers["X-Device-Nonce"], "stale nonce was replayed")
        assertEquals(
            listOf("X-Device-Nonce", "X-Device-Signature", "X-Device-Timestamp"),
            result.resignedHeaders,
        )
    }

    /**
     * The ordering that would otherwise pass every test that did not edit a path: edits must be
     * applied *before* the app is asked to sign, or the signature covers a request never sent.
     */
    @Test
    fun `an edited path is what gets signed`() = runTest {
        val liveApps = LiveApps()
        val holder = CoroutineScopeHolder(this)
        holder.attachApp(liveApps) { request ->
            SignResponse(request.requestId, headers = mapOf("X-Signed-Url" to request.url, "X-Signed-Method" to request.method))
        }

        val transport = FakeTransport()
        Replayer(repository, liveApps, transport).replay(
            ReplayRequest(
                txnId = "plain001",
                session = sessionId,
                url = "https://api.example.com/v2/orders/EDITED",
                method = "put",
                resign = true,
            ),
        )

        assertEquals("https://api.example.com/v2/orders/EDITED", transport.last.headers["X-Signed-Url"])
        assertEquals("PUT", transport.last.headers["X-Signed-Method"], "the method must be signed uppercased")
    }

    @Test
    fun `an app that cannot sign is reported, not sent unsigned`() = runTest {
        val liveApps = LiveApps()
        val holder = CoroutineScopeHolder(this)
        holder.attachApp(liveApps) { request ->
            SignResponse(request.requestId, error = "no ReplaySigner is registered")
        }

        val transport = FakeTransport()
        val result = Replayer(repository, liveApps, transport)
            .replay(ReplayRequest(txnId = "plain001", session = sessionId, resign = true))

        assertFalse(result.ok)
        assertContains(result.error!!, "no ReplaySigner is registered")
        assertTrue(transport.sent.isEmpty(), "nothing may go out when signing failed")
    }

    @Test
    fun `re-signing with no app attached refuses instead of sending stale headers`() = runBlocking {
        val transport = FakeTransport()
        val result = Replayer(repository, LiveApps(), transport)
            .replay(ReplayRequest(txnId = "plain001", session = sessionId, resign = true))

        assertFalse(result.ok)
        assertContains(result.error!!, "no app is attached")
        assertTrue(transport.sent.isEmpty())
    }

    // --- diagnosis ---------------------------------------------------------------------------

    @Test
    fun `an expired token is named as such, not blamed on signing`() {
        val diagnosis = diagnose(401, """{"error":"invalid_grant","message":"token expired"}""", resigned = true)
        assertContains(diagnosis!!, "expired")
        assertContains(diagnosis, "not a signing failure")
    }

    @Test
    fun `a signature rejection after re-signing points at the device id`() {
        val diagnosis = diagnose(401, """{"error":"invalid signature"}""", resigned = true)
        assertContains(diagnosis!!, "device id")
    }

    @Test
    fun `a signature rejection without re-signing says so`() {
        val diagnosis = diagnose(401, """{"error":"nonce already used"}""", resigned = false)
        assertContains(diagnosis!!, "not regenerated")
    }

    @Test
    fun `a successful replay is not diagnosed`() {
        assertEquals(null, diagnose(200, """{"ok":true}""", resigned = true))
    }
}

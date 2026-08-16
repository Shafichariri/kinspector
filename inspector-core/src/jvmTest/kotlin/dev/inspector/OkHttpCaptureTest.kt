package dev.inspector

import dev.inspector.model.BodyOmission
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Capture for traffic that never touches Ktor — the gap that made Auth0 calls invisible.
 *
 * The first and most important test is the same one the Ktor path has: **the app must receive
 * byte-identical bodies whether or not the interceptor is installed.** Everything else is
 * secondary to not corrupting the thing being observed.
 */
class OkHttpCaptureTest {

    private lateinit var server: TestServer

    /**
     * Every client built by a test, so teardown can dismantle it.
     *
     * An `OkHttpClient` owns a dispatcher thread pool and a connection pool that outlive the test
     * that made it. Leaving them running leaked enough threads and idle sockets into the shared
     * test JVM to make *other* test classes fail intermittently — a 1 MB Ktor response arriving
     * empty, a redirect chain losing a hop. The bug was here, in the cleanup, not there.
     */
    private val clients = mutableListOf<OkHttpClient>()

    @BeforeTest
    fun setUp() {
        server = TestServer()
        server.start()
    }

    @AfterTest
    fun tearDown() {
        for (client in clients) {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
        clients.clear()
        server.stop()
    }

    private fun track(client: OkHttpClient): OkHttpClient = client.also { clients += it }

    private fun plainClient(): OkHttpClient = track(
        OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
    )

    private fun inspectedClient(
        sink: CollectingSink,
        config: InspectorConfig = InspectorConfig(),
    ): OkHttpClient {
        val recorder = Recorder(config)
        recorder.addSink(sink)
        return track(
            OkHttpClient.Builder()
                .readTimeout(30, TimeUnit.SECONDS)
                .addInterceptor(dev.inspector.internal.InspectorOkHttpInterceptor(recorder))
                .build()
        )
    }

    private fun OkHttpClient.getBytes(path: String): ByteArray =
        newCall(Request.Builder().url(server.url(path)).build()).execute().use { it.body!!.bytes() }

    @Test
    fun app_receives_byte_identical_bodies_with_and_without_the_interceptor() {
        // The shapes most likely to break a tee: chunked-and-large, gzip, plain, binary, empty.
        val paths = listOf("/json", "/gzip", "/binary", "/large?kb=4096", "/empty")
        val sink = CollectingSink()
        val inspected = inspectedClient(sink)
        val plain = plainClient()

        for (path in paths) {
            val expected = plain.getBytes(path)
            val actual = inspected.getBytes(path)
            assertContentEquals(expected, actual, "body differed for $path")
        }
    }

    @Test
    fun a_call_is_recorded_with_its_url_status_and_body() = runBlocking {
        val sink = CollectingSink()
        inspectedClient(sink).getBytes("/json")

        val txn = sink.awaitTransactions(1).single()
        assertEquals("GET", txn.method)
        assertEquals("/json", txn.path)
        assertEquals(200, txn.status)
        assertEquals("127.0.0.1", txn.host)
        assertTrue((txn.ms ?: -1) >= 0)
        assertEquals(
            """{"id":42,"name":"example","nested":{"ok":true},"list":[1,2,3]}""",
            sink.responseBodies.single()?.decodeToString(),
        )
    }

    @Test
    fun a_request_body_is_captured_without_being_consumed() = runBlocking {
        val sink = CollectingSink()
        val client = inspectedClient(sink)
        val payload = """{"language":"ar"}"""

        val echoed = client.newCall(
            Request.Builder()
                .url(server.url("/echo"))
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()
        ).execute().use { it.body!!.string() }

        // The server saw the whole body, so capture did not eat it.
        assertEquals(payload, echoed)

        val txn = sink.awaitTransactions(1).single()
        assertEquals(payload, sink.requestBodies.single()?.decodeToString())
        assertEquals(17, txn.reqBytes)
        assertEquals("application/json", txn.reqContentType?.substringBefore(';')?.trim())
        assertNull(txn.reqBodyOmitted)
    }

    @Test
    fun a_non_allowlisted_response_is_counted_and_says_why_it_was_not_captured() = runBlocking {
        val sink = CollectingSink()
        inspectedClient(sink).getBytes("/binary")

        val txn = sink.awaitTransactions(1).single()
        assertEquals(BodyOmission.CONTENT_TYPE, txn.resBodyOmitted)
        assertEquals(2048, txn.resBytes, "the size must still be counted")
        assertNull(sink.responseBodies.single())
    }

    @Test
    fun capture_all_bodies_reaches_okhttp_too() = runBlocking {
        val sink = CollectingSink()
        inspectedClient(sink, InspectorConfig(captureAllBodies = true)).getBytes("/binary")

        sink.awaitTransactions(1)
        val body = assertNotNull(sink.responseBodies.single())
        assertContentEquals(ByteArray(2048) { (it % 256).toByte() }, body)
    }

    @Test
    fun a_body_the_caller_abandons_still_produces_a_row() = runBlocking {
        val sink = CollectingSink()
        val client = inspectedClient(sink)

        // Closing without reading is a normal thing to do, and the call still happened.
        client.newCall(Request.Builder().url(server.url("/large?kb=2048")).build())
            .execute()
            .close()

        val txn = sink.awaitTransactions(1).single()
        assertEquals(200, txn.status)
    }

    @Test
    fun a_transport_failure_is_recorded_and_rethrown_unchanged() = runBlocking {
        val sink = CollectingSink()
        val recorder = Recorder(InspectorConfig()).apply { addSink(sink) }
        val client = track(
            OkHttpClient.Builder()
                .addInterceptor(dev.inspector.internal.InspectorOkHttpInterceptor(recorder))
                .build()
        )

        // Nothing is listening on this port.
        val dead = "http://127.0.0.1:${server.port + 1}/nope"
        assertFailsWith<IOException> {
            client.newCall(Request.Builder().url(dead).build()).execute()
        }

        val txn = sink.awaitTransactions(1).single()
        assertNotNull(txn.error, "a failed call must record why")
        assertNull(txn.status)
    }

    @Test
    fun a_one_shot_request_body_is_reported_rather_than_consumed() = runBlocking {
        val sink = CollectingSink()
        val client = inspectedClient(sink)

        // A one-shot body can be written exactly once; reading it to inspect it would take it
        // away from the request.
        val oneShot = object : RequestBody() {
            override fun contentType() = "application/json".toMediaType()
            override fun isOneShot() = true
            override fun contentLength() = 14L
            override fun writeTo(sink: BufferedSink) {
                sink.writeUtf8("""{"once":true}""" + "\n")
            }
        }

        val echoed = client.newCall(
            Request.Builder().url(server.url("/echo")).post(oneShot).build()
        ).execute().use { it.body!!.string() }

        assertTrue(echoed.contains("once"), "the server must still receive the body")

        val txn = sink.awaitTransactions(1).single()
        assertEquals(BodyOmission.STREAMING, txn.reqBodyOmitted)
        assertNull(sink.requestBodies.single())
    }

    @Test
    fun redaction_applies_on_this_path_exactly_as_it_does_on_the_ktor_path() = runBlocking {
        val sink = CollectingSink()
        val client = inspectedClient(sink, InspectorConfig(redaction = Redaction.On()))

        client.newCall(
            Request.Builder()
                .url(server.url("/secret?token=abc123"))
                .header("Authorization", "Bearer tok_live_xyz")
                .build()
        ).execute().use { it.body!!.bytes() }

        val txn = sink.awaitTransactions(1).single()
        assertEquals(listOf(Redaction.PLACEHOLDER), txn.reqHeaders["Authorization"])
        assertEquals("token=${Redaction.PLACEHOLDER}", txn.query)
        val body = assertNotNull(sink.responseBodies.single()).decodeToString()
        assertTrue(body.contains(Redaction.PLACEHOLDER), "response body keys must be redacted too")
        assertTrue(body.contains("visible"), "non-matching keys must survive")
    }

    @Test
    fun credentials_are_verbatim_by_default() = runBlocking {
        val sink = CollectingSink()
        val client = inspectedClient(sink)

        client.newCall(
            Request.Builder()
                .url(server.url("/secret"))
                .header("Authorization", "Bearer tok_live_xyz")
                .build()
        ).execute().use { it.body!!.bytes() }

        val txn = sink.awaitTransactions(1).single()
        // The whole point of redaction defaulting off: when the bug is the auth header, show it.
        assertEquals(listOf("Bearer tok_live_xyz"), txn.reqHeaders["Authorization"])
        assertTrue(assertNotNull(sink.responseBodies.single()).decodeToString().contains("tok_live_abc123"))
    }
}

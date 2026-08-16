package dev.inspector

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestRetry
import dev.inspector.internal.installInspector
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The defining contract of the capture layer: **one row per network attempt**, not per logical
 * call. Redirects and retries each get their own transaction, sharing a `callId` and carrying an
 * incrementing `attempt`.
 *
 * This test was written before the plugin existed, and it — not any particular Ktor API — is what
 * defines correctness. Ktor's plugin surface has drifted across 2.x/3.x; whichever hook makes
 * these assertions pass on the pinned version is the right hook.
 */
class PerAttemptTest {

    private lateinit var server: TestServer

    @BeforeTest
    fun setUp() {
        server = TestServer()
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.stop()
    }

    private fun clientWith(sink: CollectingSink, block: io.ktor.client.HttpClientConfig<*>.() -> Unit = {}): HttpClient {
        val recorder = Recorder(InspectorConfig())
        recorder.addSink(sink)
        return HttpClient(CIO) {
            block()
            installInspector(recorder)
        }
    }

    @Test
    fun redirect_produces_one_row_per_attempt() = runBlocking {
        val sink = CollectingSink()
        val client = clientWith(sink)

        val body = client.get(server.url("/redirect")).bodyAsText()
        assertTrue(body.contains("\"name\":\"example\""), "app must still receive the final body")

        val txns = sink.awaitTransactions(2).let { sink.awaitSettled() }

        assertEquals(
            2, txns.size,
            "a 302 followed by a 200 must produce two rows, not one:\n${txns.describe()}"
        )
        assertEquals(listOf(1, 2), txns.map { it.attempt }, "attempts must increment:\n${txns.describe()}")
        assertEquals(1, txns.map { it.callId }.distinct().size, "attempts must share one callId")
        assertEquals(listOf("/redirect", "/json"), txns.map { it.path })
        assertEquals(listOf(302, 200), txns.map { it.status })

        client.close()
    }

    @Test
    fun redirect_chain_produces_a_row_for_every_hop() = runBlocking {
        val sink = CollectingSink()
        val client = clientWith(sink)

        client.get(server.url("/redirect-twice")).bodyAsText()
        val txns = sink.awaitTransactions(3).let { sink.awaitSettled() }

        assertEquals(3, txns.size, "two redirects then a 200 is three attempts:\n${txns.describe()}")
        assertEquals(listOf(1, 2, 3), txns.map { it.attempt })
        assertEquals(listOf("/redirect-twice", "/redirect", "/json"), txns.map { it.path })

        client.close()
    }

    @Test
    fun retry_produces_one_row_per_attempt() = runBlocking {
        val sink = CollectingSink()
        val client = clientWith(sink) {
            install(HttpRequestRetry) {
                retryOnServerErrors(maxRetries = 3)
                constantDelay(millis = 1, randomizationMs = 0)
            }
        }

        val response = client.get(server.url("/flaky?key=retrytest&failures=2"))
        assertEquals(200, response.status.value, "the retry must eventually succeed")

        val txns = sink.awaitTransactions(3).let { sink.awaitSettled() }

        assertEquals(
            3, txns.size,
            "two failures then a success is three attempts:\n${txns.describe()}"
        )
        assertEquals(listOf(1, 2, 3), txns.map { it.attempt })
        assertEquals(1, txns.map { it.callId }.distinct().size, "retries must share one callId")
        assertEquals(listOf(500, 500, 200), txns.map { it.status })

        client.close()
    }

    @Test
    fun independent_calls_get_independent_callIds() = runBlocking {
        val sink = CollectingSink()
        val client = clientWith(sink)

        client.get(server.url("/json")).bodyAsText()
        client.get(server.url("/json")).bodyAsText()

        val txns = sink.awaitTransactions(2).let { sink.awaitSettled() }
        assertEquals(2, txns.size)
        assertEquals(2, txns.map { it.callId }.distinct().size, "separate calls must not share a callId")
        assertTrue(txns.all { it.attempt == 1 })

        client.close()
    }

    @Test
    fun transport_failure_produces_a_row_with_null_status_and_an_error() = runBlocking {
        val sink = CollectingSink()
        val client = clientWith(sink)

        // Nothing is listening on this port; the connect must fail.
        val deadPort = java.net.ServerSocket(0).use { it.localPort }
        runCatching { client.get("http://127.0.0.1:$deadPort/nope").bodyAsText() }

        val txns = sink.awaitTransactions(1).let { sink.awaitSettled() }
        assertEquals(1, txns.size, "a failed connect must still be recorded:\n${txns.describe()}")
        val txn = txns.single()
        assertEquals(null, txn.status, "a transport failure has no status")
        assertTrue(txn.error != null, "the exception must be recorded, was null")
        assertTrue(txn.isError, "a transport failure must count as an error")
        assertTrue((txn.ms ?: -1) >= 0, "duration up to the failure must be recorded")

        client.close()
    }

    @Test
    fun basic_metadata_is_captured() = runBlocking {
        val sink = CollectingSink()
        val client = clientWith(sink)

        client.get(server.url("/status/404?page=2")).bodyAsText()
        val txn = sink.awaitTransactions(1).let { sink.awaitSettled() }.single()

        assertEquals("GET", txn.method)
        assertEquals("http", txn.scheme)
        assertEquals("127.0.0.1", txn.host)
        assertEquals("/status/404", txn.path)
        assertEquals("page=2", txn.query)
        assertEquals(404, txn.status)
        assertTrue((txn.ms ?: -1) >= 0)
        assertTrue(txn.id.length == 8, "id should be 8 hex chars, was '${txn.id}'")
        assertTrue(txn.resHeaders.isNotEmpty(), "response headers must be captured")

        client.close()
    }
}

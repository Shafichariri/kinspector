package dev.inspector

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drives the public `Inspector` facade exactly as a consuming app does, and asserts against the
 * `StateFlow`s the Compose UI reads.
 *
 * The unit tests elsewhere use an isolated `Recorder`; this one deliberately does not, because
 * the global wiring — one recorder, stable flows, install-inside-HttpClient — is itself a thing
 * that can break.
 */
class EndToEndTest {

    private lateinit var server: TestServer
    private lateinit var client: HttpClient

    @BeforeTest
    fun setUp() {
        server = TestServer()
        server.start()
        Inspector.init(InspectorConfig())
        Inspector.clear()
        client = HttpClient(CIO) {
            install(HttpRequestRetry) {
                retryOnServerErrors(maxRetries = 3)
                constantDelay(millis = 1, randomizationMs = 0)
            }
            Inspector.install(this)
        }
    }

    @AfterTest
    fun tearDown() {
        client.close()
        server.stop()
        Inspector.clear()
    }

    private suspend fun awaitAtLeast(n: Int, timeoutMs: Long = 10_000) {
        withTimeoutOrNull(timeoutMs) {
            while (Inspector.transactions.value.size < n) delay(10)
        } ?: error("expected >= $n transactions, saw ${Inspector.transactions.value.size}")
        delay(300) // let stragglers land so "exactly N" assertions are not racing
    }

    @Test
    fun the_flows_the_ui_reads_are_populated_by_real_traffic() = runBlocking {
        client.get(server.url("/json")).bodyAsText()
        awaitAtLeast(1)

        val txn = Inspector.transactions.value.first()
        assertEquals("/json", txn.path)
        assertEquals(200, txn.status)
        assertEquals(txn.id, Inspector.latest.value?.id, "the pill's source must track the newest row")

        val body = Inspector.responseBody(txn)
        assertTrue(body != null && body.isNotEmpty(), "the detail view needs the captured body")
        assertTrue(body!!.decodeToString().contains("example"))
    }

    @Test
    fun transactions_are_newest_first_for_the_list() = runBlocking {
        client.get(server.url("/json")).bodyAsText()
        awaitAtLeast(1)
        client.get(server.url("/status/404")).bodyAsText()
        awaitAtLeast(2)

        val paths = Inspector.transactions.value.map { it.path }
        assertEquals("/status/404", paths.first(), "newest must sort first; got $paths")
    }

    @Test
    fun markers_land_in_the_marker_flow_for_the_since_filter() = runBlocking {
        Inspector.mark("tapped checkout")
        withTimeoutOrNull(5_000) {
            while (Inspector.markers.value.isEmpty()) delay(10)
        }
        assertEquals("tapped checkout", Inspector.markers.value.single().label)
    }

    @Test
    fun a_retry_chain_is_visible_as_sibling_attempts() = runBlocking {
        client.get(server.url("/flaky?key=e2e&failures=2")).bodyAsText()
        awaitAtLeast(3)

        val chain = Inspector.transactions.value
            .filter { it.path == "/flaky" }
            .sortedBy { it.attempt }

        assertEquals(listOf(1, 2, 3), chain.map { it.attempt }, "the detail view's attempt chain")
        assertEquals(1, chain.map { it.callId }.distinct().size)
    }

    @Test
    fun a_burst_never_blocks_the_caller_and_never_drops_below_the_queue_bound() = runBlocking {
        val started = System.currentTimeMillis()
        repeat(50) { client.get(server.url("/json")).bodyAsBytes() }
        val elapsed = System.currentTimeMillis() - started

        awaitAtLeast(50)

        assertEquals(50, Inspector.transactions.value.size, "all 50 rows should be retained at 8 MB")
        assertTrue(
            elapsed < 30_000,
            "50 sequential calls took ${elapsed}ms — capture must not be adding meaningful latency"
        )
    }

    @Test
    fun clear_empties_everything_the_ui_shows() = runBlocking {
        client.get(server.url("/json")).bodyAsText()
        awaitAtLeast(1)

        Inspector.clear()

        assertTrue(Inspector.transactions.value.isEmpty())
        assertEquals(null, Inspector.latest.value)
        assertTrue(Inspector.markers.value.isEmpty())
    }
}

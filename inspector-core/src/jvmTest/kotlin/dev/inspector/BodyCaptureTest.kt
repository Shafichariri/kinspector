package dev.inspector

import dev.inspector.internal.installInspector
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The load-bearing guarantee of the whole project: **installing the inspector must not change a
 * single byte the app receives.** A debugger that corrupts the thing it observes is worse than
 * no debugger, and body channels are one-shot, so a naive read would silently eat the response.
 */
class BodyCaptureTest {

    private lateinit var server: TestServer

    @BeforeTest
    fun setUp() {
        server = TestServer()
        server.start()
    }

    @AfterTest
    fun tearDown() = server.stop()

    private fun plainClient() = HttpClient(CIO)

    private fun inspectedClient(sink: CollectingSink, config: InspectorConfig = InspectorConfig()): HttpClient {
        val recorder = Recorder(config)
        recorder.addSink(sink)
        return HttpClient(CIO) { installInspector(recorder) }
    }

    @Test
    fun app_receives_byte_identical_bodies_with_and_without_the_plugin() = runBlocking {
        // 10 MB streamed, gzip, plain json, empty, and binary — the shapes most likely to break.
        val paths = listOf("/large?kb=10240", "/gzip", "/json", "/empty", "/binary")

        for (path in paths) {
            val expected = plainClient().use { it.get(server.url(path)).bodyAsBytes() }

            val sink = CollectingSink()
            val actual = inspectedClient(sink).use { it.get(server.url(path)).bodyAsBytes() }

            assertEquals(
                expected.size, actual.size,
                "$path: body size changed when the inspector was installed"
            )
            assertContentEquals(
                expected, actual,
                "$path: body bytes changed when the inspector was installed"
            )
        }
    }

    @Test
    fun large_body_is_truncated_at_the_cap_but_true_size_is_still_recorded() = runBlocking {
        val sink = CollectingSink()
        val cap = 64 * 1024
        val client = inspectedClient(sink, InspectorConfig(bodyCaptureMaxBytes = cap))

        val received = client.get(server.url("/large?kb=1024")).bodyAsBytes()
        assertEquals(1024 * 1024, received.size, "app must still get the whole megabyte")

        val txn = sink.awaitTransactions(1).let { sink.awaitSettled() }.single()
        assertTrue(txn.resBodyTruncated, "a 1 MB body against a 64 KB cap must be flagged truncated")
        assertEquals(
            1024L * 1024, txn.resBytes,
            "resBytes must be the true size, counted past the cap rather than stopping at it"
        )
        val captured = sink.responseBodies.first()
        assertEquals(cap, captured?.size, "captured body must stop exactly at the cap")

        client.close()
    }

    @Test
    fun small_body_is_captured_whole_and_not_flagged() = runBlocking {
        val sink = CollectingSink()
        val client = inspectedClient(sink)

        val text = client.get(server.url("/json")).bodyAsText()
        val txn = sink.awaitTransactions(1).let { sink.awaitSettled() }.single()

        assertFalse(txn.resBodyTruncated)
        assertEquals(text.encodeToByteArray().size.toLong(), txn.resBytes)
        assertContentEquals(text.encodeToByteArray(), sink.responseBodies.first())

        client.close()
    }

    @Test
    fun non_allowlisted_content_types_are_counted_but_never_buffered() = runBlocking {
        val sink = CollectingSink()
        val client = inspectedClient(sink)

        client.get(server.url("/binary")).bodyAsBytes()
        val txn = sink.awaitTransactions(1).let { sink.awaitSettled() }.single()

        assertEquals(2048L, txn.resBytes, "byte count must still be accurate for binary")
        assertEquals(
            null, sink.responseBodies.first(),
            "octet-stream must not be buffered — counting is the whole point of the allowlist"
        )

        client.close()
    }

    @Test
    fun empty_body_is_handled_without_a_phantom_capture() = runBlocking {
        val sink = CollectingSink()
        val client = inspectedClient(sink)

        client.get(server.url("/empty")).bodyAsBytes()
        val txn = sink.awaitTransactions(1).let { sink.awaitSettled() }.single()

        assertEquals(204, txn.status)
        assertEquals(0L, txn.resBytes)
        assertFalse(txn.resBodyTruncated)

        client.close()
    }

    @Test
    fun request_body_is_captured_and_echoed_intact() = runBlocking {
        val sink = CollectingSink()
        val client = inspectedClient(sink)

        val payload = """{"hello":"world","n":7}"""
        val echoed = client.post(server.url("/echo")) {
            contentType(ContentType.Application.Json)
            setBody(payload)
        }.bodyAsText()

        assertEquals(payload, echoed, "the server must receive exactly what the app sent")

        val txn = sink.awaitTransactions(1).let { sink.awaitSettled() }.single()
        assertEquals("POST", txn.method)
        assertEquals(payload.encodeToByteArray().size.toLong(), txn.reqBytes)
        assertContentEquals(payload.encodeToByteArray(), sink.requestBodies.first())

        client.close()
    }

    @Test
    fun a_body_the_app_never_reads_still_produces_a_row() = runBlocking {
        // Status-only checks are common; the transaction must not depend on the app draining
        // the body, or whole classes of call would silently never appear.
        val sink = CollectingSink()
        val client = inspectedClient(sink)

        val status = client.get(server.url("/json")).status.value
        assertEquals(200, status)

        val txns = sink.awaitTransactions(1, timeoutMs = 5_000).let { sink.awaitSettled() }
        assertEquals(1, txns.size, "a row must appear even though the body was never consumed")

        client.close()
    }
}

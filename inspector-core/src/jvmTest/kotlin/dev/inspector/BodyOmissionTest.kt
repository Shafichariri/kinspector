package dev.inspector

import dev.inspector.internal.installInspector
import dev.inspector.model.BodyOmission
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * What the record says about bodies it did not keep, and which bodies it keeps at all.
 *
 * A viewer has no way to work out why a body is missing, so the capture layer has to say. When
 * it did not, the UI filled the gap with a guess — telling users that captured, stored JSON was
 * "outside the capture allowlist" — which is a worse failure than saying nothing, because it
 * sends people to debug a problem they do not have.
 */
class BodyOmissionTest {

    private lateinit var server: TestServer

    @BeforeTest
    fun setUp() {
        server = TestServer()
        server.start()
    }

    @AfterTest
    fun tearDown() = server.stop()

    private fun inspectedClient(sink: CollectingSink, config: InspectorConfig = InspectorConfig()): HttpClient {
        val recorder = Recorder(config)
        recorder.addSink(sink)
        return HttpClient(CIO) { installInspector(recorder) }
    }

    @Test
    fun a_captured_body_records_no_omission_reason() = runBlocking {
        val sink = CollectingSink()
        inspectedClient(sink).use { it.get(server.url("/json")).bodyAsBytes() }

        val txn = sink.awaitTransactions(1).single()
        assertNotNull(sink.responseBodies.single())
        assertNull(txn.resBodyOmitted, "a captured body must not claim it was omitted")
    }

    @Test
    fun a_body_dropped_for_its_content_type_says_so_and_names_the_type() = runBlocking {
        val sink = CollectingSink()
        inspectedClient(sink).use { it.get(server.url("/binary")).bodyAsBytes() }

        val txn = sink.awaitTransactions(1).single()
        assertEquals(BodyOmission.CONTENT_TYPE, txn.resBodyOmitted)
        // The reason is only actionable alongside the type that triggered it.
        assertEquals("application/octet-stream", txn.resContentType)
        assertEquals(2048, txn.resBytes, "the size must still be counted")
    }

    @Test
    fun structured_syntax_suffix_types_are_captured_as_the_json_they_are() = runBlocking {
        val sink = CollectingSink()
        inspectedClient(sink).use { it.get(server.url("/vendor-json")).bodyAsBytes() }

        val txn = sink.awaitTransactions(1).single()
        assertNull(txn.resBodyOmitted, "application/vnd.api+json is JSON and must be captured")
        assertEquals(
            """{"data":{"type":"user","id":"1"}}""",
            sink.responseBodies.single()?.decodeToString(),
        )
    }

    @Test
    fun capture_all_bodies_overrides_the_allowlist() = runBlocking {
        val sink = CollectingSink()
        val config = InspectorConfig(captureAllBodies = true)
        inspectedClient(sink, config).use { it.get(server.url("/binary")).bodyAsBytes() }

        val txn = sink.awaitTransactions(1).single()
        assertNull(txn.resBodyOmitted)
        val body = assertNotNull(sink.responseBodies.single())
        assertEquals(2048, body.size)
        // Binary must survive intact; the wire layer base64s it rather than mangling it.
        assertEquals(ByteArray(2048) { (it % 256).toByte() }.toList(), body.toList())
    }

    @Test
    fun a_request_content_type_is_recorded_even_though_ktor_keeps_it_off_the_headers() = runBlocking {
        val sink = CollectingSink()
        inspectedClient(sink).use { client ->
            client.post(server.url("/echo")) {
                contentType(ContentType.Application.Json)
                setBody("""{"language":"ar"}""")
            }.bodyAsBytes()
        }

        val txn = sink.awaitTransactions(1).single()
        // Was null for every request with a body, which also silently disabled body redaction.
        assertEquals("application/json", txn.reqContentType?.substringBefore(';')?.trim())
        assertEquals(17, txn.reqBytes)
        assertNull(txn.reqBodyOmitted)
        assertEquals("""{"language":"ar"}""", sink.requestBodies.single()?.decodeToString())
    }

    @Test
    fun redaction_reaches_request_bodies_now_that_the_content_type_is_known() = runBlocking {
        val sink = CollectingSink()
        val config = InspectorConfig(redaction = Redaction.On())
        inspectedClient(sink, config).use { client ->
            client.post(server.url("/echo")) {
                contentType(ContentType.Application.Json)
                setBody("""{"password":"hunter2","safe":"visible"}""")
            }.bodyAsBytes()
        }

        val txn = sink.awaitTransactions(1).single()
        val sent = assertNotNull(sink.requestBodies.single()).decodeToString()
        assertEquals("""{"password":"${Redaction.PLACEHOLDER}","safe":"visible"}""", sent)
        assertEquals(listOf("body:req:$.password"), txn.redacted.filter { it.startsWith("body:req") })
    }
}

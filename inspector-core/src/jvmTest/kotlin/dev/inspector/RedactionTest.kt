package dev.inspector

import dev.inspector.internal.installInspector
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Redaction is a setting, and it is **off by default**.
 *
 * Both directions are tested deliberately. A test suite that only proves redaction redacts would
 * pass just as happily if redaction were forced on — which is the opposite of what this tool is
 * for. Hiding the auth header is useless when the bug *is* the auth header.
 */
class RedactionTest {

    private lateinit var server: TestServer

    @BeforeTest
    fun setUp() {
        server = TestServer()
        server.start()
    }

    @AfterTest
    fun tearDown() = server.stop()

    private fun client(sink: CollectingSink, redaction: Redaction): HttpClient {
        val recorder = Recorder(InspectorConfig(redaction = redaction))
        recorder.addSink(sink)
        return HttpClient(CIO) { installInspector(recorder) }
    }

    @Test
    fun by_default_everything_is_captured_verbatim() = runBlocking {
        val sink = CollectingSink()
        val c = client(sink, Redaction.Off)

        c.get(server.url("/secret?token=qwerty&page=2")) {
            header("Authorization", "Bearer super-secret")
        }.bodyAsText()

        val txn = sink.awaitTransactions(1).let { sink.awaitSettled() }.single()

        assertEquals(
            listOf("Bearer super-secret"), txn.reqHeaders["Authorization"],
            "default config must not touch the authorization header"
        )
        assertEquals("token=qwerty&page=2", txn.query, "default config must not touch the query")
        assertTrue(txn.redacted.isEmpty(), "nothing was redacted, so the list must be empty")

        val body = sink.responseBodies.first()!!.decodeToString()
        assertContains(body, "tok_live_abc123")
        assertContains(body, "hunter2")

        c.close()
    }

    @Test
    fun redaction_on_strips_headers_query_and_json_body_keys() = runBlocking {
        val sink = CollectingSink()
        val c = client(sink, Redaction.On())

        c.get(server.url("/secret?token=qwerty&page=2")) {
            header("Authorization", "Bearer super-secret")
        }.bodyAsText()

        val txn = sink.awaitTransactions(1).let { sink.awaitSettled() }.single()

        assertEquals(listOf(Redaction.PLACEHOLDER), txn.reqHeaders["Authorization"])
        assertEquals("token=${Redaction.PLACEHOLDER}&page=2", txn.query, "only matching keys change")

        val body = sink.responseBodies.first()!!.decodeToString()
        assertFalse(body.contains("tok_live_abc123"), "token value must be gone: $body")
        assertFalse(body.contains("hunter2"), "password value must be gone: $body")
        assertTrue(body.contains("visible"), "non-matching keys must survive: $body")

        assertTrue(txn.redacted.contains("header:authorization"), "got ${txn.redacted}")
        assertTrue(txn.redacted.contains("query:token"), "got ${txn.redacted}")
        assertTrue(
            txn.redacted.any { it.startsWith("body:res:") },
            "body redactions must be recorded so consumers can say 'redacted', not 'absent': ${txn.redacted}"
        )

        c.close()
    }

    @Test
    fun the_app_still_receives_the_unredacted_body() = runBlocking {
        // Redaction applies to what the inspector stores, never to what the app is handed.
        val sink = CollectingSink()
        val c = client(sink, Redaction.On())

        val received = c.get(server.url("/secret")).bodyAsText()
        sink.awaitTransactions(1).let { sink.awaitSettled() }

        assertTrue(received.contains("tok_live_abc123"), "redaction must not alter the app's own body")

        c.close()
    }

    @Test
    fun custom_header_list_replaces_the_defaults() = runBlocking {
        val sink = CollectingSink()
        val c = client(sink, Redaction.On(headers = listOf("x-customer-ssn")))

        c.get(server.url("/json")) {
            header("Authorization", "Bearer keep-me")
            header("X-Customer-SSN", "123-45-6789")
        }.bodyAsText()

        val txn = sink.awaitTransactions(1).let { sink.awaitSettled() }.single()

        assertEquals(
            listOf("Bearer keep-me"), txn.reqHeaders["Authorization"],
            "a narrowed list must leave authorization alone"
        )
        assertEquals(listOf(Redaction.PLACEHOLDER), txn.reqHeaders["X-Customer-SSN"])

        c.close()
    }
}

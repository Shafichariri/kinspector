package dev.inspector.daemon.mcp

import dev.inspector.daemon.archive.SessionRepository
import dev.inspector.daemon.archive.TimelineEntry
import dev.inspector.daemon.txn
import dev.inspector.model.timeline as modelTimeline
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `timeline`, `current`, `list_signals`, `get_signal` and `request_signal`, from [SignalTools]. */
class SignalToolsTest : McpToolsFixture() {

    @Test
    fun current_reports_provenance_so_stale_state_is_not_read_as_live() {
        val text = tools.call("current", args("session" to "latest")).text()
        // The pulled cache row says `request`; the pushed screen row says `app`.
        assertContains(text, "\"trigger\": \"request\"")
        assertContains(text, "\"trigger\": \"app\"")
        assertContains(text, "ageMsAtLastActivity")
    }

    @Test
    fun anding_terms_across_row_types_returns_nothing_and_says_why() {
        val text = tools.call(
            "timeline",
            args("session" to "latest", "filter" to "status:500 tag:screen"),
        ).text()
        assertContains(text, "No entries matched")
        assertContains(text, "can never match", message = "the emptiness must be explained, not just returned")
    }

    @Test
    fun or_spans_both_row_types() {
        val text = tools.call(
            "timeline",
            args("session" to "latest", "filter" to "status:502 | tag:state"),
        ).text()
        assertContains(text, "cccc3333")
        assertContains(text, "KycViewModel")
        assertFalse(text.contains("KycSubmit"), "tag:state must not admit a screen row")
    }

    @Test
    fun list_signals_omits_payloads_and_get_signal_supplies_them() {
        val rows = tools.call("list_signals", args("session" to "latest", "filter" to "tag:state")).text()
        assertContains(rows, "KycViewModel")
        assertFalse(rows.contains("isSubmitting"), "payloads are not inlined into a listing")

        val one = tools.call("get_signal", args("session" to "latest", "id" to "s3c")).text()
        assertContains(one, "isSubmitting")
    }

    @Test
    fun request_signal_reports_when_no_app_is_attached() {
        puller.attached = false
        val outcome = tools.call("request_signal", args("tag" to "cache", "name" to "response"))
        assertTrue(outcome is ToolOutcome.Failed)
        assertContains((outcome as ToolOutcome.Failed).message, "no app is attached")
        assertEquals(Triple("latest", "cache", "response"), puller.pulled.single())
    }

    @Test
    fun request_signal_requires_both_tag_and_name() {
        // One request yields exactly one reply; a missing name would make that ambiguous.
        val outcome = tools.call("request_signal", args("tag" to "cache"))
        assertTrue(outcome is ToolOutcome.Failed)
        assertContains((outcome as ToolOutcome.Failed).message, "'name' is required")
    }

    @Test
    fun the_timeline_tool_orders_exactly_as_the_shared_timeline_does() {
        // Against the live model rather than a literal, so a change to the rule moves both sides
        // together and a second ordering in the daemon cannot hide behind an expectation that
        // happened to agree with it.
        val id = tieSession()
        val dir = config.sessionsDir.resolve(id)
        val repository = SessionRepository(config)
        val expected = modelTimeline(
            repository.readTransactions(dir),
            repository.readMarkers(dir),
            repository.readSignals(dir),
        ).map { TimelineEntry.of(it) }

        val actual = Json.decodeFromString(
            ListSerializer(TimelineEntry.serializer()),
            callTool("timeline", """{"session":"$id"}"""),
        )

        assertEquals(expected.size, actual.size, "entry count: $actual")
        expected.indices.forEach { i ->
            assertEquals(expected[i], actual[i], "entry $i differs from the shared timeline()")
        }
    }

    /**
     * Two local servers on one host are two endpoints, so the label carries a port that is not the
     * scheme's default. It used to be `GET 127.0.0.1/json` for both. The query stays out: a label
     * names an endpoint, and the URL is where the evidence is.
     */
    @Test
    fun timeline_labels_keep_a_non_default_port_and_drop_the_default_one() {
        val id = session("2026-08-17T09-00-07_ports_dev_debug") { writer ->
            writer.append(txn("port8080", path = "/json", mono = 100, host = "127.0.0.1")
                .copy(scheme = "http", port = 8080, query = "a=1"), null, null)
            writer.append(txn("port9090", path = "/json", mono = 200, host = "127.0.0.1")
                .copy(scheme = "http", port = 9090), null, null)
            writer.append(txn("port0443", mono = 300).copy(port = 443), null, null)
            writer.append(txn("portnone", mono = 400).copy(port = null), null, null)
        }

        val labels = Json.decodeFromString(
            ListSerializer(TimelineEntry.serializer()),
            callTool("timeline", """{"session":"$id"}"""),
        ).map { it.label }

        assertEquals(
            listOf(
                "GET 127.0.0.1:8080/json",
                "GET 127.0.0.1:9090/json",
                "GET api.example.com/v2/users/me",
                "GET api.example.com/v2/users/me",
            ),
            labels,
        )
    }
}

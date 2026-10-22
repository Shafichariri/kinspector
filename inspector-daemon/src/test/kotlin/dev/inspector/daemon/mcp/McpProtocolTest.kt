package dev.inspector.daemon.mcp

import dev.inspector.daemon.service.daemonVersion
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The JSON-RPC surface in [McpServer] and the dispatch in [McpTools]: the handshake, the
 * listing, argument checking and how a failure comes back. Not about any one tool.
 */
class McpProtocolTest : McpToolsFixture() {

    // --- argument validation -----------------------------------------------------------------

    /**
     * The exact call a consuming app made. `list_sessions` reports the field as `sessionId`, so
     * reaching for `sessionId` here is the natural next step — and it used to be silently dropped,
     * default to `latest`, and answer about a different session entirely.
     */
    @Test
    fun `an unknown argument is refused rather than silently ignored`() {
        val message = callToolExpectingError(
            "session_summary",
            """{"sessionId":"2026-08-16T10-14-02_app_dev_debug"}""",
        )
        assertContains(message, "unknown argument")
        assertContains(message, "sessionId")
        assertContains(message, "Did you mean 'session'?", message = "the hint must name the fix")
    }

    @Test
    fun `the refusal lists what the tool does accept`() {
        val message = callToolExpectingError("list_transactions", """{"nonsense":1}""")
        assertContains(message, "Accepted:")
        for (accepted in listOf("filter", "limit", "offset", "session")) {
            assertContains(message, accepted)
        }
    }

    @Test
    fun `several unknown arguments are all reported`() {
        val message = callToolExpectingError("list_sessions", """{"alpha":1,"beta":2}""")
        assertContains(message, "unknown arguments")
        assertContains(message, "alpha")
        assertContains(message, "beta")
    }

    /** The allowlist comes from the descriptors, so every declared argument must pass. */
    @Test
    fun `every argument a tool declares is accepted`() {
        callTool("list_transactions", """{"session":"latest","filter":"","limit":5,"offset":0}""")
        callTool("get_body", """{"session":"latest","id":"cccc3333","side":"res","maxBytes":128}""")
    }

    @Test
    fun initialize_advertises_tools_and_agrees_a_protocol_version() {
        val frames = exchange(
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{}}}"""
        )
        val result = frames.single().getValue("result").jsonObject
        // Echoed rather than forced upward, so a client pinned to an older revision still works.
        assertEquals("2024-11-05", result.getValue("protocolVersion").jsonPrimitive.content)
        assertTrue(result.getValue("capabilities").jsonObject.containsKey("tools"))
        assertEquals("inspector", result.getValue("serverInfo").jsonObject.getValue("name").jsonPrimitive.content)
    }

    /**
     * `serverInfo.version` is the version it was built as, not a literal.
     *
     * It said `1.0.0` from 1.0.0 onward, 1.1.0 included. Two halves, because each alone passes
     * with the bug: a value passed in must come out unchanged, and by default the server must
     * report what [daemonVersion] reads — `unknown` under test, where there is no jar manifest,
     * and the release number in a built daemon, which the release job's smoke test drives.
     */
    @Test
    fun initialize_reports_the_version_it_was_built_as() {
        val init = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{}}}"""
        fun version(frames: List<JsonObject>) =
            frames.single().getValue("result").jsonObject.getValue("serverInfo").jsonObject.getValue("version").jsonPrimitive.content
        assertEquals("9.9.9-test", version(exchange(init, version = "9.9.9-test")))
        assertEquals(daemonVersion(), version(exchange(init)))
    }

    @Test
    fun notifications_get_no_reply_at_all() {
        // A response to a notification is a protocol violation; some clients treat it as fatal.
        val frames = exchange("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        assertEquals(emptyList(), frames)
    }

    @Test
    fun an_unknown_method_is_a_jsonrpc_error_but_does_not_stop_the_server() {
        val frames = exchange(
            """{"jsonrpc":"2.0","id":1,"method":"nope"}""",
            """{"jsonrpc":"2.0","id":2,"method":"ping"}""",
        )
        assertEquals(2, frames.size)
        assertEquals(-32601, frames[0].getValue("error").jsonObject.getValue("code").jsonPrimitive.int())
        assertTrue(frames[1].containsKey("result"), "the server must survive a bad frame")
    }

    @Test
    fun every_tool_is_listed_with_a_schema() {
        val frames = exchange("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")
        val listed = frames.single().getValue("result").jsonObject.getValue("tools").jsonArray
            .map { it.jsonObject.getValue("name").jsonPrimitive.content }
        assertEquals(
            setOf(
                "list_sessions", "session_summary", "list_transactions",
                "get_transaction", "explain", "get_body", "route_stats", "list_findings", "add_marker",
                "timeline", "current", "list_signals", "get_signal", "request_signal",
                "export_session",
            ),
            listed.toSet(),
        )
    }

    @Test
    fun a_bad_filter_comes_back_as_a_readable_tool_error_not_a_transport_failure() {
        val frames = exchange(
            """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"list_transactions","arguments":{"filter":"bogus:1"}}}"""
        )
        val result = frames.single().getValue("result").jsonObject
        // An agent can read this and correct itself; a JSON-RPC error would just end its turn.
        assertTrue(result.getValue("isError").jsonPrimitive.content.toBoolean())
        assertContains(
            result.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content,
            "Unknown filter key",
        )
    }

    @Test
    fun numeric_arguments_sent_as_strings_are_accepted() {
        // Agents do this constantly; rejecting it would burn a turn on a pointless correction.
        val text = callTool("list_transactions", """{"limit":"2"}""")
        assertContains(text, "\"matched\": 4")
        assertEquals(2, Json.parseToJsonElement(text).jsonObject.getValue("items").jsonArray.size)
    }
}

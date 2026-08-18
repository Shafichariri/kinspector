package dev.inspector.daemon

import dev.inspector.daemon.mcp.McpServer
import dev.inspector.daemon.mcp.McpTools
import dev.inspector.daemon.mcp.MarkerPoster
import dev.inspector.daemon.mcp.ToolOutcome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The MCP surface, driven the way a client drives it: JSON-RPC frames in, frames out.
 *
 * The acceptance bar from the plan is not "the tools exist" but "an agent can answer a real
 * question in at most four calls", so that is what the last test measures.
 */
class McpTest {

    private lateinit var tmp: Path
    private lateinit var config: DaemonConfig
    private lateinit var tools: McpTools

    /** Records what would have been posted, so no daemon has to run. */
    private class RecordingPoster : MarkerPoster {
        val posted = mutableListOf<Pair<String, String>>()
        override fun post(session: String, label: String): ToolOutcome {
            posted += session to label
            return ToolOutcome.Ok("Marker '$label' added to session '$session'.")
        }
    }

    private val poster = RecordingPoster()

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("inspector-mcp")
        config = DaemonConfig(port = 8099, dataDir = tmp)
        Files.createDirectories(config.sessionsDir)

        val dir = config.sessionsDir.resolve("2026-08-16T10-14-02_app_dev_debug")
        val meta = clientInfo(appId = "com.example.checkout")
            .toSessionMeta("2026-08-16T10-14-02_app_dev_debug", "2026-08-16T10:14:02.000Z")
        val writer = SessionWriter(dir, meta)

        // Before the marker.
        writer.append(txn("aaaa1111", path = "/v2/session", mono = 100), null, null)
        writer.append(txn("bbbb2222", path = "/v2/cart", mono = 200, status = 500), null, """{"early":true}""".toByteArray())
        writer.append(marker("tapped checkout", mono = 300))
        // After it — this is the failure the question is about.
        writer.append(
            txn("cccc3333", path = "/v2/orders", mono = 400, status = 502, method = "POST"),
            """{"cart":"c-1"}""".toByteArray(),
            """{"error":"upstream_timeout","retryAfter":30}""".toByteArray(),
        )
        writer.append(txn("dddd4444", path = "/v2/telemetry", mono = 500), null, null)
        writer.close()
        SessionWriter.updateLatestLink(config.dataDir, dir)

        tools = McpTools(config, SessionRepository(config), poster)
    }

    @AfterTest
    fun tearDown() {
        Files.walk(tmp).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    // --- protocol ----------------------------------------------------------------------------

    /** Feeds lines through a real [McpServer] and returns the response frames. */
    private fun exchange(vararg requests: String): List<JsonObject> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        McpServer(
            tools = tools,
            input = requests.joinToString("\n").reader().buffered(),
            output = PrintStream(out, true),
            log = PrintStream(err, true),
        ).run()
        return out.toString().lineSequence()
            .filter { it.isNotBlank() }
            .map { Json.parseToJsonElement(it).jsonObject }
            .toList()
    }

    private fun callTool(name: String, args: String = "{}"): String {
        val frames = exchange(
            """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$name","arguments":$args}}"""
        )
        val result = frames.single().getValue("result").jsonObject
        assertFalse(
            result.getValue("isError").jsonPrimitive.content.toBoolean(),
            "tool $name failed: ${result.getValue("content").jsonArray}",
        )
        return result.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content
    }

    /** Like [callTool] but asserts the call failed, and returns the message. */
    private fun callToolExpectingError(name: String, args: String): String {
        val frames = exchange(
            """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$name","arguments":$args}}"""
        )
        val result = frames.single().getValue("result").jsonObject
        assertTrue(
            result.getValue("isError").jsonPrimitive.content.toBoolean(),
            "tool $name was expected to fail but succeeded",
        )
        return result.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content
    }

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
                "get_transaction", "get_body", "add_marker",
            ),
            listed.toSet(),
        )
    }

    // --- tools -------------------------------------------------------------------------------

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
    fun get_body_explains_an_absent_body_rather_than_implying_there_was_none() {
        val text = callTool("get_body", """{"id":"aaaa1111","side":"req"}""")
        assertContains(text, "no body")
    }

    @Test
    fun get_body_truncates_by_default_and_says_how_to_see_the_rest() {
        val text = callTool("get_body", """{"id":"cccc3333","side":"res","maxBytes":10}""")
        assertContains(text, "truncated")
        assertContains(text, "maxBytes")
    }

    @Test
    fun numeric_arguments_sent_as_strings_are_accepted() {
        // Agents do this constantly; rejecting it would burn a turn on a pointless correction.
        val text = callTool("list_transactions", """{"limit":"2"}""")
        assertContains(text, "\"matched\": 4")
        assertEquals(2, Json.parseToJsonElement(text).jsonObject.getValue("items").jsonArray.size)
    }

    @Test
    fun add_marker_targets_the_live_session_through_the_daemon() {
        callTool("add_marker", """{"label":"agent looked here"}""")
        assertEquals(listOf("latest" to "agent looked here"), poster.posted)
    }

    // --- the acceptance bar --------------------------------------------------------------------

    @Test
    fun an_agent_answers_what_failed_after_the_marker_in_at_most_four_calls() {
        var calls = 0

        // 1. Orient: which markers exist, and did anything fail?
        calls++
        val summary = callTool("session_summary")
        assertContains(summary, "tapped checkout")
        assertContains(summary, "\"5xx\": 2")

        // 2. Narrow to failures after that marker — the shared filter grammar does the work.
        calls++
        val failures = callTool(
            "list_transactions",
            """{"filter":"since:marker(\"tapped checkout\") has:error"}""",
        )
        val items = Json.parseToJsonElement(failures).jsonObject.getValue("items").jsonArray
        assertEquals(1, items.size, "only the post-marker failure should match")
        val failing = items.single().jsonObject
        assertEquals("cccc3333", failing.getValue("id").jsonPrimitive.content)
        assertEquals(502, failing.getValue("status").jsonPrimitive.int())

        // 3. What did the server actually return?
        calls++
        val body = callTool("get_body", """{"id":"cccc3333","side":"res"}""")
        assertContains(body, "upstream_timeout")

        assertTrue(calls <= 4, "the question must be answerable in at most four calls, took $calls")
    }
}

private fun kotlinx.serialization.json.JsonPrimitive.int(): Int = content.toInt()

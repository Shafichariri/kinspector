package dev.inspector.daemon

import dev.inspector.daemon.mcp.McpServer
import dev.inspector.daemon.mcp.McpTools
import dev.inspector.daemon.mcp.MarkerPoster
import dev.inspector.daemon.mcp.SignalPuller
import dev.inspector.daemon.mcp.ToolOutcome
import kotlinx.serialization.json.Json
import dev.inspector.model.SignalTags
import dev.inspector.model.SignalTrigger
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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

    /** Stands in for a live app, so the pull path is exercised without a socket. */
    private class RecordingPuller : SignalPuller {
        var attached = true
        val pulled = mutableListOf<Triple<String, String, String>>()
        override fun pull(session: String, tag: String, name: String): ToolOutcome {
            pulled += Triple(session, tag, name)
            return if (attached) {
                ToolOutcome.Ok("""{"tag":"$tag","name":"$name","trigger":"request"}""")
            } else {
                ToolOutcome.Failed("the pull failed (409): no app is attached for session $session")
            }
        }
    }

    private val puller = RecordingPuller()

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
        writer.append(
            signal(id = "s1a", tag = SignalTags.SCREEN, name = "Cart", mono = 150),
            null,
        )
        writer.append(marker("tapped checkout", mono = 300))
        writer.append(
            signal(id = "s2b", tag = SignalTags.SCREEN, name = "KycSubmit", mono = 320),
            """{"step":"review"}""".toByteArray(),
        )
        writer.append(
            signal(id = "s3c", tag = SignalTags.STATE, name = "KycViewModel", mono = 350),
            """{"isSubmitting":true,"attempts":2}""".toByteArray(),
        )
        writer.append(
            signal(
                id = "s4d",
                tag = SignalTags.CACHE,
                name = "response",
                mono = 360,
                trigger = SignalTrigger.Request,
                requestId = "r-1",
            ),
            """{"entries":0}""".toByteArray(),
        )
        // After it — this is the failure the question is about.
        writer.append(
            txn("cccc3333", path = "/v2/orders", mono = 400, status = 502, method = "POST"),
            """{"cart":"c-1"}""".toByteArray(),
            """{"error":"upstream_timeout","retryAfter":30}""".toByteArray(),
        )
        writer.append(txn("dddd4444", path = "/v2/telemetry", mono = 500), null, null)
        writer.close()
        SessionWriter.updateLatestLink(config.dataDir, dir)

        tools = McpTools(config, SessionRepository(config), poster, puller)
    }

    @AfterTest
    fun tearDown() {
        Files.walk(tmp).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    /** Builds a tool argument object. Values are Strings or Ints. */
    private fun args(vararg pairs: Pair<String, Any>): JsonObject = buildJsonObject {
        pairs.forEach { (key, value) ->
            when (value) {
                is Int -> put(key, value)
                else -> put(key, value.toString())
            }
        }
    }

    /** The text of a successful outcome; fails loudly rather than returning an error string. */
    private fun ToolOutcome.text(): String = when (this) {
        is ToolOutcome.Ok -> text
        is ToolOutcome.Failed -> throw AssertionError("tool failed: $message")
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
                "timeline", "current", "list_signals", "get_signal", "request_signal",
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

    // --- signals -----------------------------------------------------------------------------

    /**
     * The question this whole feature exists to answer, in three calls.
     *
     * > **"Why did the KYC submit fail?"**
     * > timeline(since the marker) → get_signal(the state row) → get_body(the failing call)
     *
     * Four calls would mean something upstream is under-summarising.
     */
    @Test
    fun the_acceptance_question_is_answered_in_three_calls() {
        // 1. Orient: what happened after the tap, across traffic and app state together.
        val timeline = tools.call(
            "timeline",
            args("session" to "latest", "since" to 300),
        ).text()
        assertContains(timeline, "KycSubmit", message = "the screen the user was on must appear")
        assertContains(timeline, "KycViewModel", message = "so must the state holder")
        assertContains(timeline, "cccc3333", message = "and the call that failed")
        assertFalse(timeline.contains("aaaa1111"), "`since` must exclude what came before")

        // 2. What did the app think it was doing?
        val state = tools.call("get_signal", args("session" to "latest", "id" to "s3c")).text()
        assertContains(state, "isSubmitting")
        assertContains(state, "\"attempts\":2")

        // 3. What did the server actually say?
        val body = tools.call(
            "get_body",
            args("session" to "latest", "id" to "cccc3333", "side" to "res"),
        ).text()
        assertContains(body, "upstream_timeout")
    }

    @Test
    fun the_acceptance_question_survives_real_json_rpc_frames() {
        // Proven the same two ways as the original bar: as a unit test, and through the wire.
        val frames = exchange(
            """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"timeline","arguments":{"session":"latest","since":300}}}""",
            """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"get_signal","arguments":{"session":"latest","id":"s3c"}}}""",
            """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"get_body","arguments":{"session":"latest","id":"cccc3333","side":"res"}}}""",
        )
        assertEquals(3, frames.size)
        val texts = frames.map { it.toString() }
        assertContains(texts[0], "KycViewModel")
        assertContains(texts[1], "isSubmitting")
        assertContains(texts[2], "upstream_timeout")
    }

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
    fun session_summary_gains_signal_counts_and_stays_small() {
        val text = tools.call("session_summary", args("session" to "latest")).text()
        assertContains(text, "signalsByTag")
        assertContains(text, "currentScreen")
        assertContains(text, "KycSubmit")
        assertContains(text, "cache/response", message = "a pull proves a provider exists")
        assertTrue(text.length < 4096, "the digest must stay around a kilobyte, was ${text.length}")
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

}

private fun kotlinx.serialization.json.JsonPrimitive.int(): Int = content.toInt()

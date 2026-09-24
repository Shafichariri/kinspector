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
    private fun exchange(vararg requests: String, version: String? = null): List<JsonObject> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val input = requests.joinToString("\n").reader().buffered()
        val server = if (version == null) {
            McpServer(tools = tools, input = input, output = PrintStream(out, true), log = PrintStream(err, true))
        } else {
            McpServer(tools = tools, input = input, output = PrintStream(out, true), log = PrintStream(err, true), version = version)
        }
        server.run()
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
                "get_transaction", "explain", "get_body", "add_marker",
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

    // --- explain -----------------------------------------------------------------------------

    /** A second session, so a test can shape the exact case it is about. */
    private fun session(id: String, build: (SessionWriter) -> Unit): String {
        val dir = config.sessionsDir.resolve(id)
        val writer = SessionWriter(dir, clientInfo().toSessionMeta(id, "2026-08-17T09:00:00.000Z"))
        build(writer)
        writer.close()
        return id
    }

    @Test
    fun explain_joins_the_screen_the_app_was_on_to_the_call() {
        // The join this tool exists for. Traffic and signals are recorded by different mechanisms
        // that share only the device clock, and nothing else puts the two together.
        val text = callTool("explain", """{"id":"cccc3333"}""")
        val explanation = Json.parseToJsonElement(text).jsonObject
        val screen = explanation.getValue("screen").jsonObject

        assertEquals("KycSubmit", screen.getValue("name").jsonPrimitive.content)
        assertEquals(80, screen.getValue("arrivedMsBefore").jsonPrimitive.int())
        assertEquals("s2b", screen.getValue("signalId").jsonPrimitive.content)
    }

    @Test
    fun explain_does_not_attribute_a_screen_the_app_only_reached_later() {
        // "The screen it was on" means the one in force when it started, not the newest in the
        // session. Take the latest arrival without bounding it and every call gets labelled with
        // wherever the user finished up — a confident, wrong answer on every row.
        val id = session("2026-08-17T09-00-03_screens_dev_debug") { writer ->
            writer.append(signal(id = "scr00001", tag = SignalTags.SCREEN, name = "Login", mono = 100), null)
            writer.append(txn("mid00001", path = "/me", mono = 500), null, null)
            writer.append(signal(id = "scr00002", tag = SignalTags.SCREEN, name = "Dashboard", mono = 900), null)
        }

        val text = callTool("explain", """{"session":"$id","id":"mid00001"}""")
        val screen = Json.parseToJsonElement(text).jsonObject.getValue("screen").jsonObject

        assertEquals("Login", screen.getValue("name").jsonPrimitive.content)
        assertEquals(400, screen.getValue("arrivedMsBefore").jsonPrimitive.int())
    }

    @Test
    fun explain_says_so_when_it_cannot_know_the_screen() {
        // An absent field and an unsupported one look identical to an agent, so absence is always
        // explained — and the two reasons for it are different facts about the session.
        val id = session("2026-08-17T09-00-04_early_dev_debug") { writer ->
            writer.append(txn("early001", path = "/boot", mono = 10), null, null)
            writer.append(signal(id = "scr00003", tag = SignalTags.SCREEN, name = "Home", mono = 900), null)
        }

        val text = callTool("explain", """{"session":"$id","id":"early001"}""")
        val explanation = Json.parseToJsonElement(text).jsonObject
        val notes = explanation.getValue("notes").jsonArray.map { it.jsonPrimitive.content }

        assertFalse(explanation.containsKey("screen"))
        assertTrue(
            notes.any { it.contains("before the first screen signal") },
            "absence has to be explained, not left as a missing key: $notes",
        )
    }

    @Test
    fun explain_always_reports_the_fields_an_empty_answer_would_hide() {
        // `encodeDefaults = false` is right for the archive and wrong here: an empty `concurrent`
        // means "nothing ran alongside this", which is a finding, and an omitted one leaves an
        // agent unable to tell that from a daemon that does not report overlap.
        val id = session("2026-08-17T09-00-05_lonely_dev_debug") { writer ->
            writer.append(txn("alone001", path = "/solo", mono = 1_000, ms = 5), null, null)
        }

        val text = callTool("explain", """{"session":"$id","id":"alone001"}""")
        val explanation = Json.parseToJsonElement(text).jsonObject

        for (key in listOf("attempts", "concurrent", "before", "after", "notes", "repeats")) {
            assertTrue(explanation.containsKey(key), "'$key' must be present even when empty")
        }
        assertTrue(explanation.getValue("concurrent").jsonArray.isEmpty())
        assertEquals(1, explanation.getValue("repeats").jsonObject.getValue("total").jsonPrimitive.int())
    }

    @Test
    fun explain_reports_calls_that_were_in_flight_at_the_same_moment() {
        // Not derivable from any other tool: a call is an interval, and every other tool treats
        // it as the instant it began. Overlap is what separates "slow" from "queued behind".
        val id = session("2026-08-17T09-00-00_overlap_dev_debug") { writer ->
            writer.append(txn("over0001", path = "/a", mono = 1_000, ms = 500), null, null)
            writer.append(txn("over0002", path = "/b", mono = 1_200, ms = 100), null, null)
            writer.append(txn("over0003", path = "/c", mono = 5_000, ms = 10), null, null)
        }

        val text = callTool("explain", """{"session":"$id","id":"over0001"}""")
        val concurrent = Json.parseToJsonElement(text).jsonObject.getValue("concurrent").jsonArray

        assertEquals(1, concurrent.size, "only /b overlapped the window /a was open for")
        assertContains(concurrent.single().jsonObject.getValue("label").jsonPrimitive.content, "/b")
    }

    @Test
    fun explain_reports_every_attempt_of_a_retried_call() {
        // A retry read as the only try is a wrong answer, not a missing one.
        val id = session("2026-08-17T09-00-01_retry_dev_debug") { writer ->
            writer.append(txn("try00001", path = "/pay", mono = 100, status = 503, attempt = 1, callId = "one"), null, null)
            writer.append(txn("try00002", path = "/pay", mono = 300, status = 503, attempt = 2, callId = "one"), null, null)
            writer.append(txn("try00003", path = "/pay", mono = 900, status = 200, attempt = 3, callId = "one"), null, null)
        }

        val text = callTool("explain", """{"session":"$id","id":"try00002"}""")
        val explanation = Json.parseToJsonElement(text).jsonObject

        assertEquals(3, explanation.getValue("attempts").jsonArray.size)
        val notes = explanation.getValue("notes").jsonArray.map { it.jsonPrimitive.content }
        assertTrue(
            notes.any { it.contains("attempt 2 of 3") },
            "the chain must be stated, not left to be counted: $notes",
        )
    }

    @Test
    fun explain_splits_context_into_what_led_to_the_call_and_what_followed() {
        // One window would make the reader do the splitting, and "what led to this" and "what it
        // caused" are different questions.
        val text = callTool("explain", """{"id":"cccc3333"}""")
        val explanation = Json.parseToJsonElement(text).jsonObject
        val monoOf = { key: String ->
            explanation.getValue(key).jsonArray.map { it.jsonObject.getValue("mono").jsonPrimitive.int() }
        }

        assertTrue(monoOf("before").all { it < 400 }, "before must be strictly before the call")
        assertTrue(monoOf("after").all { it >= 400 }, "after must not reach back past the call")
        assertTrue(monoOf("before").isNotEmpty() && monoOf("after").isNotEmpty())
        assertFalse(text.contains("\"id\": \"cccc3333\"\n            }"), "the call is not its own context")
    }

    @Test
    fun explain_does_not_inline_headers() {
        // Inlining the whole transaction put every header in the reply — 3 KB of an 11 KB answer
        // on a real call, redundant with get_transaction and a contradiction of why this exists.
        val text = callTool("explain", """{"id":"cccc3333"}""")

        assertFalse(text.contains("reqHeaders"), "headers belong to get_transaction")
        assertFalse(text.contains("resHeaders"))
        assertContains(text, "get_transaction", message = "say where the headers are instead")
        assertContains(text, "\"url\"", message = "the call still has to be identifiable")
    }

    @Test
    fun explain_caps_its_context_so_a_busy_window_still_fits_a_reply() {
        // A busy five seconds can hold hundreds of observations. An answer that does not fit in a
        // reply is no answer, and it is worst on exactly the sessions where the question is hard.
        val id = session("2026-08-17T09-00-02_busy_dev_debug") { writer ->
            repeat(200) { i ->
                writer.append(signal(id = "b%03d".format(i), tag = SignalTags.STATE, name = "Chatty", mono = 1_000L + i), null)
            }
            writer.append(txn("busy0001", path = "/quiet", mono = 1_100), null, null)
        }

        val text = callTool("explain", """{"session":"$id","id":"busy0001"}""")
        val explanation = Json.parseToJsonElement(text).jsonObject

        assertEquals(25, explanation.getValue("before").jsonArray.size)
        assertEquals(25, explanation.getValue("after").jsonArray.size)
        // The ones kept are the ones nearest the call, not the first the file happened to hold.
        val lastBefore = explanation.getValue("before").jsonArray.last().jsonObject
        assertEquals(1_099, lastBefore.getValue("mono").jsonPrimitive.int())
    }

    @Test
    fun explain_names_a_signal_id_rather_than_reporting_it_missing() {
        // Signal ids and transaction ids come from the same generator and look identical, so
        // "no transaction with that id" is the wrong answer about as often as it is the right one.
        val outcome = tools.call("explain", args("id" to "s2b"))

        assertTrue(outcome is ToolOutcome.Failed)
        val message = (outcome as ToolOutcome.Failed).message
        assertContains(message, "is a signal")
        assertContains(message, "get_signal")
    }

    @Test
    fun explain_is_offered_to_agents_as_a_tool() {
        val frames = exchange("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")
        val names = frames.single().getValue("result").jsonObject
            .getValue("tools").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content }

        assertContains(names, "explain")
    }

}

private fun kotlinx.serialization.json.JsonPrimitive.int(): Int = content.toInt()

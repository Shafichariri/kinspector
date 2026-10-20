package dev.inspector.daemon.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The MCP surface, driven the way a client drives it: JSON-RPC frames in, frames out.
 *
 * The acceptance bar from the plan is not "the tools exist" but "an agent can answer a real
 * question in at most four calls", so that is what these tests measure. Each question spans
 * several tool groups, so it lives in none of their files.
 */
class McpAcceptanceTest : McpToolsFixture() {

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
}

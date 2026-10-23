package dev.inspector.daemon.mcp

import dev.inspector.daemon.archive.TimelineEntry
import dev.inspector.daemon.marker
import dev.inspector.daemon.signal
import dev.inspector.daemon.txn
import kotlinx.serialization.json.Json
import dev.inspector.model.SignalTags
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `list_transactions`, `get_transaction`, `get_body` and `explain`, from [TransactionTools] and
 * `ExplainTool.kt`. [ExplainToolTest] holds the explain cases that need no shared session.
 */
class TransactionToolsTest : McpToolsFixture() {

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

    // --- explain -----------------------------------------------------------------------------


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

        // `<=` rather than `<`: the split is by position, so a marker at the call's own instant
        // belongs to `before` — see explain_puts_a_marker_at_the_call_s_instant_before_it.
        assertTrue(monoOf("before").all { it <= 400 }, "before must not reach past the call")
        assertTrue(monoOf("after").all { it >= 400 }, "after must not reach back past the call")
        assertTrue(monoOf("before").isNotEmpty() && monoOf("after").isNotEmpty())
        assertFalse(text.contains("\"id\": \"cccc3333\"\n            }"), "the call is not its own context")
    }

    @Test
    fun explain_puts_a_marker_at_the_call_s_instant_before_it() {
        // The marker was dropped to introduce the call. A split by `mono` reports it as something
        // the call caused, which is the reverse of what both UIs draw for the same session.
        val id = tieSession()
        val explanation = Json.parseToJsonElement(
            callTool("explain", """{"session":"$id","id":"tiec0001"}"""),
        ).jsonObject
        val labels = { key: String ->
            explanation.getValue(key).jsonArray.map {
                it.jsonObject.getValue("kind").jsonPrimitive.content + ":" +
                    it.jsonObject.getValue("label").jsonPrimitive.content
            }
        }

        assertEquals("marker:m", labels("before").last(), "before: ${labels("before")}")
        assertFalse("marker:m" in labels("after"), "after: ${labels("after")}")
    }

    @Test
    fun explain_places_same_millisecond_calls_by_position_and_signals_after_the_call() {
        // What DAEMON.md §8 says about ties. A call recorded first precedes this one in the shared
        // order and so led to it; a signal without `seq` never precedes a call it ties with,
        // whatever the file order, because the rule puts calls ahead of signals at one millisecond.
        val id = session("2026-08-17T09-00-08_sametick_dev_debug") { writer ->
            writer.append(signal(id = "tick0001", tag = SignalTags.STATE, name = "Early", mono = 500), null)
            writer.append(txn("tickb001", path = "/b", mono = 500), null, null)
            writer.append(txn("tickc001", path = "/c", mono = 500), null, null)
        }
        val explanation = Json.parseToJsonElement(
            callTool("explain", """{"session":"$id","id":"tickc001"}"""),
        ).jsonObject
        val ids = { key: String ->
            explanation.getValue(key).jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }
        }

        assertEquals(listOf("tickb001"), ids("before"))
        assertEquals(listOf("tick0001"), ids("after"))
    }

    @Test
    fun explain_still_reports_what_followed_a_call_with_over_2000_entries_before_it() {
        // The timeline tool's clamp keeps the oldest 2000, so applied here it would
        // drop the call and everything after it, and `after` would read as "nothing followed".
        val id = session("2026-08-17T09-00-07_crowded_dev_debug") { writer ->
            repeat(2_100) { i ->
                writer.append(signal(id = "w%04d".format(i), tag = SignalTags.STATE, name = "Chatty", mono = 1_000L + i), null)
            }
            writer.append(txn("late0001", path = "/late", mono = 3_200), null, null)
            writer.append(signal(id = "after001", tag = SignalTags.STATE, name = "Settled", mono = 3_300), null)
        }

        val text = callTool("explain", """{"session":"$id","id":"late0001"}""")
        val after = Json.parseToJsonElement(text).jsonObject.getValue("after").jsonArray

        assertEquals(listOf("after001"), after.map { it.jsonObject.getValue("id").jsonPrimitive.content })
    }

    @Test
    fun the_explain_split_refuses_a_window_that_lost_the_call() {
        // Reachable when retention or a delete removes the session between reading the call and
        // reading its window. Two empty lists would claim nothing happened either side.
        val window = listOf(TimelineEntry.of(marker("m", mono = 100)), TimelineEntry.of(txn("othr0001", mono = 200)))

        assertEquals(null, splitAround(window, "gone0001"))
        assertEquals(listOf("m") to emptyList<TimelineEntry>(), splitAround(window, "othr0001")?.let { (b, a) -> b.map { it.label } to a })
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

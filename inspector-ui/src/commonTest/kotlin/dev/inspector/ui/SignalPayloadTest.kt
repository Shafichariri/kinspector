package dev.inspector.ui

import dev.inspector.model.Signal
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Rendering a signal's payload, and the two shapes that are easy to get confidently wrong.
 *
 * Both are cases where the obvious implementation produces something that *looks* like output —
 * which is why they need a test rather than a careful reading.
 */
class SignalPayloadTest {

    private fun sig(
        data: kotlinx.serialization.json.JsonElement? = null,
        truncated: Boolean = false,
        bytes: Long = 0,
    ) = Signal(
        id = "5c1a0001", ts = "2026-09-18T10:00:00.000Z", mono = 1_000,
        tag = "cache", name = "profile", data = data, dataTruncated = truncated, bytes = bytes,
    )

    @Test
    fun an_object_is_indented_rather_than_shown_as_the_app_spelled_it() {
        val payload = formatSignalPayload(sig(buildJsonObject { put("items", 7); put("live", true) }))!!
        assertTrue(payload.contains("\n"), "should be pretty-printed, got: $payload")
        assertTrue(payload.contains("\"items\""))
        assertTrue(payload.contains("7"))
    }

    @Test
    fun a_truncated_payload_is_unwrapped_before_it_is_formatted() {
        // This is what `Recorder.emit` stores when the encoded payload exceeds the cap: a cut JSON
        // document is not JSON, so the prefix is kept as a JSON *string*.
        val prefix = """{"items":[{"id":"a"},{"id":"b"}"""
        val payload = formatSignalPayload(sig(JsonPrimitive(prefix), truncated = true, bytes = 9_000))!!

        // Encoding the element instead would produce `"{\"items\":[{\"id\":\"a\"}…"` — the whole
        // snapshot as one quoted line with every quote escaped, which is the least readable form
        // of exactly the payload somebody opened the row to read.
        assertFalse(payload.startsWith("\""), "should not be re-quoted, got: $payload")
        assertFalse(payload.contains("\\\""), "should not be escaped, got: $payload")
        // Formatted despite being malformed, because the cut ones are the ones worth reading.
        assertTrue(payload.contains("\n"), "should still be indented, got: $payload")
        assertTrue(payload.contains("\"items\""))
    }

    @Test
    fun a_text_payload_is_the_line_the_app_wrote() {
        // `Inspector.signal(tag, name, text)` wraps a toString() dump as a JSON string. It is not
        // JSON and must not be re-quoted.
        assertEquals("Cart(items=3, total=42.00)", formatSignalPayload(sig(JsonPrimitive("Cart(items=3, total=42.00)"))))
    }

    @Test
    fun a_truncated_payload_that_is_not_a_string_still_renders() {
        // The flag says what capture did; if the flag and the shape disagree, the reader is still
        // owed whatever is actually there rather than a blank pane.
        val payload = formatSignalPayload(sig(buildJsonObject { put("a", 1) }, truncated = true, bytes = 50))
        assertTrue(payload != null && payload.contains("\"a\""), "got: $payload")
    }

    @Test
    fun no_payload_is_null_rather_than_an_empty_string() {
        // The screen distinguishes "this observation is a name and a moment" from "there was a
        // payload and it is gone", and an empty string would collapse the two.
        assertNull(formatSignalPayload(sig(data = null)))
    }
}

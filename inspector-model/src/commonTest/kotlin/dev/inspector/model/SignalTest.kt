package dev.inspector.model

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

class SignalTest {

    @Test
    fun signal_round_trips() {
        val original = signal(
            data = buildJsonObject { put("route", "PortfolioDetail"); put("id", 42) },
            bytes = 128,
        )
        val json = InspectorJson.encodeToString(original)
        assertEquals(original, InspectorJson.decodeFromString<Signal>(json))
    }

    @Test
    fun a_text_payload_is_a_json_string_so_there_is_one_payload_type_on_disk() {
        val original = signal(tag = SignalTags.STATE, data = JsonPrimitive("Loading(retry=2)"))
        val decoded = InspectorJson.decodeFromString<Signal>(InspectorJson.encodeToString(original))
        assertEquals(original, decoded)
        assertIs<JsonPrimitive>(decoded.data)
    }

    @Test
    fun schema_version_is_always_encoded_even_though_defaults_are_not() {
        // `InspectorJson` sets encodeDefaults = false, so without @EncodeDefault(ALWAYS) every
        // archived row would silently lose its version marker and version gating would have
        // nothing to gate on.
        assertContains(InspectorJson.encodeToString(signal()), "\"v\":1")
    }

    @Test
    fun defaults_and_nulls_are_omitted_to_keep_signal_lines_small() {
        val json = InspectorJson.encodeToString(signal())
        assertFalse(json.contains("\"data\""), "null payload must not be encoded: $json")
        assertFalse(json.contains("\"trigger\""), "default trigger must not be encoded: $json")
        assertFalse(json.contains("\"redacted\""), "empty defaults must not be encoded: $json")
    }

    @Test
    fun trigger_survives_the_wire_as_its_serial_name() {
        val pulled = signal(trigger = SignalTrigger.Request, requestId = "r-1")
        val json = InspectorJson.encodeToString(pulled)
        assertContains(json, "\"trigger\":\"request\"")
        assertEquals(SignalTrigger.Request, InspectorJson.decodeFromString<Signal>(json).trigger)
    }

    @Test
    fun an_unknown_tag_decodes_like_any_other() {
        // The tag set is open. A daemon that has never heard of a tag must archive and serve it,
        // not drop it.
        val decoded = InspectorJson.decodeFromString<Signal>(
            InspectorJson.encodeToString(signal(tag = "bluetooth", name = "pairing"))
        )
        assertEquals("bluetooth", decoded.tag)
    }

    @Test
    fun the_payload_travels_beside_the_row_never_on_it() {
        // The `Txn` split: SignalMsg carries the payload, the row carries refs. Carrying it in
        // both places would ship every payload twice.
        val msg: WireMsg = SignalMsg(signal = signal(bytes = 17), data = "{\"open\":true}")
        val decoded = InspectorJson.decodeFromString<WireMsg>(InspectorJson.encodeToString(msg))
        assertIs<SignalMsg>(decoded)
        assertNull(decoded.signal.data, "the row must not carry the payload on the wire")
        assertNull(decoded.signal.dataRef, "dataRef is the daemon's to assign, not the device's")
        assertEquals("{\"open\":true}", decoded.data)
        assertEquals(17, decoded.signal.bytes)
    }

    @Test
    fun pull_frames_round_trip_through_the_sealed_hierarchy() {
        val frames: List<WireMsg> = listOf(
            SignalRequest(requestId = "r-1", tag = SignalTags.CACHE, name = "response"),
            SignalError(
                requestId = "r-1",
                error = "no provider for cache/orders; registered: cache/response, cache/prefs",
            ),
        )
        for (frame in frames) {
            assertEquals(
                frame,
                InspectorJson.decodeFromString<WireMsg>(InspectorJson.encodeToString(frame)),
            )
        }
    }

    @Test
    fun bytes_is_the_true_size_even_when_the_payload_was_truncated() {
        val truncated = signal(data = JsonPrimitive("abc"), dataTruncated = true, bytes = 9_000)
        val json = InspectorJson.encodeToString(truncated)
        val decoded = InspectorJson.decodeFromString<Signal>(json)
        assertEquals(9_000, decoded.bytes)
        assertEquals(true, decoded.dataTruncated)
    }
}

package dev.inspector

import dev.inspector.internal.capturesBody
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `producers/js/fixtures/defaults.json` is [InspectorConfig]'s defaults, and the JavaScript
 * producer embeds its copies from that file's numbers, held there by `defaults.test.js`. So this
 * test is what keeps a default changed here from leaving the JS producer on the old one: the file
 * goes red first, and the JS side follows it.
 *
 * The content-type cases run [capturesBody] under the default config, so the JS predicate written
 * against the same cases agrees with Kotlin's prefix match and its `+json`/`+xml` suffixes, not
 * merely with the list.
 */
class ProducerDefaultsTest {

    private val defaults: JsonObject = Json.parseToJsonElement(
        File(
            System.getProperty("inspector.producerFixtures")
                ?: error("inspector.producerFixtures is not set; run this through Gradle"),
            "defaults.json",
        ).readText(),
    ).jsonObject

    private val config = InspectorConfig()

    @Test
    fun the_file_holds_exactly_the_known_keys() {
        assertEquals(
            setOf("captureContentTypes", "bodyCaptureMaxBytes", "signals", "contentTypeCases"),
            defaults.keys,
        )
    }

    @Test
    fun the_defaults_equal_inspector_config() {
        assertEquals(
            config.captureContentTypes,
            defaults.getValue("captureContentTypes").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(config.bodyCaptureMaxBytes, defaults.getValue("bodyCaptureMaxBytes").jsonPrimitive.int)
        assertEquals(
            config.signals.maxPayloadBytes,
            defaults.getValue("signals").jsonObject.getValue("maxPayloadBytes").jsonPrimitive.int,
        )
    }

    @Test
    fun captures_body_gives_each_case_its_answer() {
        val cases = defaults.getValue("contentTypeCases").jsonArray
        assertTrue(cases.isNotEmpty(), "defaults.json holds no content-type cases")
        val wrong = cases.map { it.jsonObject }.mapNotNull { case ->
            val type = case.getValue("contentType").let { if (it is JsonNull) null else it.jsonPrimitive.content }
            val expected = case.getValue("captures").jsonPrimitive.boolean
            if (config.capturesBody(type) == expected) null else "$type: expected $expected"
        }
        if (wrong.isNotEmpty()) fail("capturesBody disagrees with defaults.json:\n  " + wrong.joinToString("\n  "))
    }
}

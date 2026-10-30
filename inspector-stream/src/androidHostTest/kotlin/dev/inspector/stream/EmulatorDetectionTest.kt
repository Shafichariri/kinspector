package dev.inspector.stream

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Profiles, not invented strings, and shared with the JavaScript producer.
 *
 * The profiles live in `producers/js/fixtures/emulator-profiles.json`, where `emulator.test.js`
 * runs the producer's rule over the same images. Each carries the answer both runners must give,
 * because they cannot give the same one everywhere: React Native exposes no `Build.HARDWARE`, so
 * the JS rule runs on five of these seven fields. The emulator profiles were read off booted images
 * with `adb shell getprop` and transcribed field for field; `Build.HARDWARE`, `Build.BOARD` and the
 * rest are the documented mirrors of the `ro.*` properties. The physical-device ones are
 * representative vendor values, because no phone has been attached, and the file marks each
 * profile's provenance. What they pin is that nothing in the rule set fires on a field a shipping
 * phone actually carries.
 */
class EmulatorDetectionTest {

    private val profiles: List<JsonObject> = Json.parseToJsonElement(
        File(
            System.getProperty("inspector.producerFixtures")
                ?: error("inspector.producerFixtures is not set; run this through Gradle"),
            "emulator-profiles.json",
        ).readText(),
    ).jsonObject.getValue("profiles").jsonArray.map { it.jsonObject }

    @Test
    fun `every profile gets the Kotlin answer the file expects`() {
        assertTrue(profiles.isNotEmpty(), "emulator-profiles.json holds no profiles")
        val wrong = profiles.mapNotNull { profile ->
            val build = profile.getValue("build").jsonObject
            assertEquals(FIELDS, build.keys, "${profile.name}: every Build field the rule reads, and no other")
            fun field(key: String): String? = build.getValue(key).let { if (it is JsonNull) null else it.jsonPrimitive.content }
            val expected = profile.getValue("expect").jsonObject.getValue("kotlin").jsonPrimitive.boolean
            val actual = looksLikeEmulator(
                hardware = field("hardware"),
                board = field("board"),
                device = field("device"),
                product = field("product"),
                brand = field("brand"),
                model = field("model"),
                fingerprint = field("fingerprint"),
            )
            if (actual == expected) null else "${profile.name}: expected $expected, got $actual"
        }
        if (wrong.isNotEmpty()) fail(wrong.joinToString("\n"))
    }

    @Test
    fun `both answers are present in the profiles`() {
        // A file of only emulators, or only phones, could not catch a rule that always answers one way.
        val answers = profiles.map { it.getValue("expect").jsonObject.getValue("kotlin").jsonPrimitive.boolean }.toSet()
        assertEquals(setOf(true, false), answers)
    }

    private val JsonObject.name: String get() = getValue("name").jsonPrimitive.content

    private companion object {
        val FIELDS = setOf("hardware", "board", "device", "product", "brand", "model", "fingerprint")
    }
}

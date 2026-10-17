package dev.inspector.daemon

import java.io.File
import kotlin.test.Test
import kotlin.test.fail
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * `mirror-cases/hopByHop.json` against [HOP_BY_HOP_HEADERS] and [replayableHeaders].
 *
 * The one mirror whose authority is not `:inspector-model`: the web replay editor seeds its header
 * box with `HOP_BY_HOP` filtered out, because a sent header map replaces the captured set and the
 * daemon's own filtering never runs on it. A name in one list and not the other would put a
 * captured `Content-Length` back on an edited request. `scripts/check-web-static.js` runs the same
 * file against `app.js`; `MirrorCasesTest` in the model owns the rest of the directory.
 */
class HopByHopMirrorTest {

    private val dir = File(
        System.getProperty("inspector.mirrorCases")
            ?: error("inspector.mirrorCases is not set; run this through Gradle"),
    )

    @Test
    fun hop_by_hop_cases_hold_for_replay() {
        val root = Json.parseToJsonElement(File(dir, "hopByHop.json").readText()).jsonObject
        if (root.keys != setOf("rule", "cases")) fail("hopByHop.json: keys ${root.keys}, expected [rule, cases]")
        if ((root["rule"] as? JsonPrimitive)?.content != "hopByHop") fail("hopByHop.json does not hold rule 'hopByHop'")
        val cases = root.getValue("cases").jsonArray
        if (cases.isEmpty()) fail("hopByHop: zero cases")

        val failures = cases.mapNotNull { element ->
            val case = element.jsonObject
            val name = (case["name"] as? JsonPrimitive)?.content ?: "(unnamed)"
            val extra = case.keys - setOf("name", "from", "input", "expect")
            val input = case["input"]?.jsonObject
            if (extra.isNotEmpty()) return@mapNotNull "$name: unknown case keys $extra"
            if (input == null || "expect" !in case) return@mapNotNull "$name: needs input and expect"
            if ((input.keys - setOf("headers")).isNotEmpty()) return@mapNotNull "$name: unknown input keys ${input.keys}"
            val actual = project(input)
            if (actual == case.getValue("expect")) null
            else "$name\n    expected ${case.getValue("expect")}\n    actual   $actual"
        }
        if (failures.isNotEmpty()) fail("hopByHop: ${failures.size} of ${cases.size} cases failed\n  " + failures.joinToString("\n  "))
    }

    /** No headers asks for the set itself, sorted; headers ask for what the editor would seed. */
    private fun project(input: JsonObject): JsonElement {
        val headers = input["headers"] ?: return buildJsonObject {
            put("names", JsonArray(HOP_BY_HOP_HEADERS.sorted().map(::JsonPrimitive)))
        }
        val captured: Map<String, List<String>> = Json.decodeFromJsonElement(headers)
        return buildJsonObject {
            put("lines", JsonArray(replayableHeaders(captured).map { (k, v) -> JsonPrimitive("$k: $v") }))
        }
    }
}

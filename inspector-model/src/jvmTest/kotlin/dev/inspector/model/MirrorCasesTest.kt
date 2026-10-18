package dev.inspector.model

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * The Kotlin half of the shared mirror cases in `mirror-cases/` at the repository root.
 *
 * The web UI mirrors six rules from this module, and `scripts/check-web-static.js` runs the same
 * files against the functions it lifts out of the real web UI by their `@mirror-begin <name>`
 * anchors, whichever file holds each block. A case is therefore one fact asserted of both
 * surfaces, and one added on either side is run on the other. The commonTest suites stay as they
 * are — they are what runs on iOS and Android — and this one runs on the JVM only, because it reads
 * files from the repository.
 *
 * Rows are decoded through the real serializer with unknown keys **refused**. The archive's own
 * reader ignores unknown keys so a newer device can talk to an older daemon; a test fixture has no
 * such excuse, and a misspelt `"calId"` that silently fell back to the default would make a case
 * assert something other than what it says. Case and input keys are checked for the same reason.
 *
 * Each side keeps its own small projection — entry tokens, run ids, groups — and the projections
 * hold no rules. The authority is the model function each case calls.
 */
class MirrorCasesTest {

    private val dir: File = File(
        System.getProperty("inspector.mirrorCases")
            ?: error("inspector.mirrorCases is not set; run this through Gradle"),
    )
    private val strict = Json(InspectorJson) { ignoreUnknownKeys = false }
    private val defaults: JsonObject by lazy { read(File(dir, "_defaults.json")).jsonObject }

    @Test
    fun every_case_file_names_a_known_rule_and_holds_cases() {
        val files = caseFiles()
        assertTrue(files.isNotEmpty(), "no case files in $dir")
        val problems = files.mapNotNull { file ->
            val root = read(file).jsonObject
            val rule = (root["rule"] as? JsonPrimitive)?.content
            when {
                root.keys != setOf("rule", "cases") -> "${file.name}: keys ${root.keys}, expected [rule, cases]"
                rule !in RULES_HERE && rule !in RULES_ELSEWHERE -> "${file.name}: unknown rule '$rule'"
                root.getValue("cases").jsonArray.isEmpty() -> "${file.name}: no cases"
                file.nameWithoutExtension != rule -> "${file.name}: holds rule '$rule'; name the file after it"
                else -> null
            }
        }
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
        val missing = (RULES_HERE + RULES_ELSEWHERE) - files.map { it.nameWithoutExtension }.toSet()
        if (missing.isNotEmpty()) fail("no case file for ${missing.sorted()}")
    }

    @Test
    fun timeline_cases() = runRule("timeline", setOf("transactions", "markers", "signals")) { input ->
        tokens(timeline(txns(input), markers(input), signals(input)))
    }

    @Test
    fun visits_cases() = runRule(
        "visits",
        setOf("screens", "transactions", "markers", "signals", "sessionStart", "indexAt"),
    ) { input ->
        val result = visits(
            screens = signals(input, "screens"),
            transactions = txns(input),
            markers = markers(input),
            signals = signals(input),
            sessionStart = (input["sessionStart"] as? JsonPrimitive)?.long,
        )
        buildJsonObject {
            put(
                "visits",
                buildJsonArray {
                    for (visit in result) {
                        add(
                            buildJsonObject {
                                put("screen", visit.screen?.name)
                                put("entries", tokens(visit.entries))
                            },
                        )
                    }
                },
            )
            input["indexAt"]?.let { at ->
                put("indexAt", JsonArray(at.jsonArray.map { JsonPrimitive(visitIndexAt(result, it.jsonPrimitive.long)) }))
            }
        }
    }

    @Test
    fun pathScope_cases() = runRule("pathScope", setOf("transactions")) { input ->
        val scope = pathScope(txns(input)) ?: return@runRule JsonNull
        buildJsonObject {
            put("host", scope.host)
            put("prefix", scope.prefix)
            put("covered", scope.covered)
            put("label", scope.label)
        }
    }

    @Test
    fun endpointShortcuts_cases() = runRule("endpointShortcuts", setOf("transactions", "limit")) { input ->
        val limit = input.getValue("limit").jsonPrimitive.int
        JsonArray(
            endpointShortcuts(txns(input), limit).map {
                JsonArray(listOf(JsonPrimitive(it.segment), JsonPrimitive(it.count)))
            },
        )
    }

    @Test
    fun timelineRuns_cases() = runRule("timelineRuns", setOf("entries")) { input ->
        val entries = input.getValue("entries").jsonArray.map { wrapped ->
            val (kind, row) = wrapped.jsonObject.entries.single()
            when (kind) {
                "T" -> TimelineEntry.Call(decode(row, "txn"))
                "M" -> TimelineEntry.Mark(decode(row, "marker"))
                "S" -> TimelineEntry.Observation(decode(row, "signal"))
                else -> error("unknown entry kind '$kind'; expected T, M or S")
            }
        }
        JsonArray(
            timelineRuns(entries).map { run ->
                buildJsonObject {
                    put("key", run.key)
                    put("entries", tokens(run.entries))
                    if (run.key != null) put("id", run.id)
                }
            },
        )
    }

    @Test
    fun duplicates_cases() = runRule("duplicates", setOf("transactions", "windowMs")) { input ->
        // Required, as in the JS runner: neither harness may carry its own copy of the default.
        val window = (input["windowMs"] as? JsonPrimitive)?.long ?: error("duplicates cases must give windowMs")
        JsonArray(
            duplicateGroups(txns(input), window).map { group ->
                buildJsonObject {
                    put("ids", JsonArray(group.ids.map(::JsonPrimitive)))
                    put("spanMs", group.spanMs)
                    put("callCount", group.callCount)
                }
            },
        )
    }

    // --- harness -------------------------------------------------------------------------------

    private fun runRule(rule: String, inputKeys: Set<String>, project: (JsonObject) -> JsonElement) {
        val file = File(dir, "$rule.json")
        val cases = read(file).jsonObject.getValue("cases").jsonArray
        if (cases.isEmpty()) fail("$rule: zero cases")
        val failures = cases.mapNotNull { element ->
            val case = element.jsonObject
            val name = (case["name"] as? JsonPrimitive)?.content ?: "(unnamed)"
            val extra = case.keys - CASE_KEYS
            val input = case["input"]?.jsonObject
            when {
                extra.isNotEmpty() -> "$name: unknown case keys $extra"
                input == null || "expect" !in case -> "$name: needs input and expect"
                (input.keys - inputKeys).isNotEmpty() -> "$name: unknown input keys ${input.keys - inputKeys}"
                else -> {
                    val actual = runCatching { project(input) }
                        .getOrElse { return@mapNotNull "$name: threw ${it::class.simpleName}: ${it.message}" }
                    if (actual == case.getValue("expect")) null
                    else "$name\n    expected ${case.getValue("expect")}\n    actual   $actual"
                }
            }
        }
        if (failures.isNotEmpty()) fail("$rule: ${failures.size} of ${cases.size} cases failed\n  " + failures.joinToString("\n  "))
    }

    private fun caseFiles(): List<File> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".json") && !f.name.startsWith("_") }
            ?.sortedBy { it.name }
            .orEmpty()

    private fun read(file: File): JsonElement = Json.parseToJsonElement(file.readText())

    /** A partial row merged over its `_defaults.json` entry, then decoded by the real serializer. */
    private inline fun <reified T> decode(row: JsonElement, kind: String): T =
        strict.decodeFromJsonElement(JsonObject(defaults.getValue(kind).jsonObject + row.jsonObject))

    private fun txns(input: JsonObject): List<NetworkTransaction> =
        input["transactions"]?.jsonArray?.map { decode<NetworkTransaction>(it, "txn") }.orEmpty()

    private fun markers(input: JsonObject): List<Marker> =
        input["markers"]?.jsonArray?.map { decode<Marker>(it, "marker") }.orEmpty()

    private fun signals(input: JsonObject, key: String = "signals"): List<Signal> =
        input[key]?.jsonArray?.map { decode<Signal>(it, "signal") }.orEmpty()

    private fun tokens(entries: List<TimelineEntry>): JsonArray = JsonArray(
        entries.map {
            JsonPrimitive(
                when (it) {
                    is TimelineEntry.Call -> "T:${it.txn.id}"
                    is TimelineEntry.Mark -> "M:${it.marker.label}"
                    is TimelineEntry.Observation -> "S:${it.signal.tag}/${it.signal.name}"
                },
            )
        },
    )

    private companion object {
        val CASE_KEYS = setOf("name", "from", "input", "expect")
        val RULES_HERE = setOf("timeline", "visits", "pathScope", "endpointShortcuts", "timelineRuns", "duplicates")

        /** `HOP_BY_HOP_HEADERS` lives in `Replay.kt`, so `HopByHopMirrorTest` in the daemon runs it. */
        val RULES_ELSEWHERE = setOf("hopByHop")
    }
}

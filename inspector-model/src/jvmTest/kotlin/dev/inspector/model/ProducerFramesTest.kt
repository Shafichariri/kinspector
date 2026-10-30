package dev.inspector.model

import java.io.File
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Every frame in `producers/js/fixtures/frames` is one the daemon accepts, and accepts as meant.
 *
 * The JavaScript producer is a second implementation of the wire format, and `fixtures.test.js`
 * holds it to emit exactly these frames. This is the other half: the daemon reads frames with
 * [InspectorJson], which ignores unknown keys so a newer device can talk to an older daemon, so a
 * producer writing `calId` for `callId` would be archived without the field and without a word —
 * measured live, which is why the decode here is [strict]. The value checks cover what the
 * serializer cannot see at all: a `String` id of any shape, a `v` that defaults when missing, an
 * omission reason or platform it has never heard of, and a negative `mono`.
 *
 * Each check is proven by a mutant of a real fixture frame, refused below. The transaction frames
 * arrive with the XHR capture; until then the omission mutant mutates a [Txn] built here.
 */
class ProducerFramesTest {

    private val dir: File = File(
        System.getProperty("inspector.producerFixtures")
            ?: error("inspector.producerFixtures is not set; run this through Gradle"),
        "frames",
    )
    private val strict = Json(InspectorJson) { ignoreUnknownKeys = false }

    @Test
    fun every_fixture_frame_decodes_strictly_and_holds_its_values() {
        val frames = fixtureFrames()
        val problems = frames.flatMap { (where, frame) -> problems(frame).map { "$where: $it" } }
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }

    @Test
    fun each_mutant_frame_is_refused() {
        val frames = fixtureFrames().map { it.second }
        val signal = frames.first { it.type == "signal" }.jsonObject
        val hello = frames.first { it.type == "hello" }.jsonObject
        val txn = InspectorJson.encodeToJsonElement(WireMsg.serializer(), sampleTxn()).jsonObject
        // The base of every mutant must pass, or a mutant would be refused for the wrong reason.
        for (base in listOf(signal, hello, txn)) assertTrue(problems(base).isEmpty(), "base frame refused: $base")

        val mutants = mapOf(
            "calId, an unknown key" to signal.edit("signal") { it + ("calId" to JsonPrimitive("0000000a")) },
            "a 9-character id" to signal.edit("signal") { it + ("id" to JsonPrimitive("0000000ab")) },
            "an uppercase id" to signal.edit("signal") { it + ("id" to JsonPrimitive("0000000A")) },
            "an unknown omission" to txn.edit("txn") { it + ("resBodyOmitted" to JsonPrimitive("streamed")) },
            "a fractional mono" to signal.edit("signal") { it + ("mono" to JsonPrimitive(1500.5)) },
            "a negative mono" to signal.edit("signal") { it + ("mono" to JsonPrimitive(-1)) },
            "a missing v on a signal" to signal.edit("signal") { it - "v" },
            "a missing v on a client" to hello.edit("client") { it - "v" },
            "a missing v on a txn" to txn.edit("txn") { it - "v" },
            "an unknown platform" to hello.edit("client") { it + ("platform" to JsonPrimitive("android")) },
            "an array frame" to buildJsonArray { add(signal); add(signal) },
        )
        val accepted = mutants.filterValues { problems(it).isEmpty() }.keys
        if (accepted.isNotEmpty()) fail("accepted mutants: $accepted")
    }

    /** Every frame of every fixture, labelled `file[i]`. Fails on no files and on no frames. */
    private fun fixtureFrames(): List<Pair<String, JsonElement>> {
        val files = dir.listFiles { f -> f.name.endsWith(".json") }?.sortedBy { it.name }.orEmpty()
        if (files.isEmpty()) fail("no frame fixtures in $dir")
        val frames = files.flatMap { file ->
            val root = strict.parseToJsonElement(file.readText()).jsonObject
            if (root.keys != setOf("scenario", "frames")) fail("${file.name}: keys ${root.keys}, expected [frames, scenario]")
            val list = root.getValue("frames").jsonArray
            if (list.isEmpty()) fail("${file.name}: no frames")
            list.mapIndexed { i, frame -> "${file.name}[$i]" to frame }
        }
        if (frames.isEmpty()) fail("no frames in $dir")
        return frames
    }

    /** What is wrong with one frame, as the daemon would read it; empty when nothing is. */
    private fun problems(frame: JsonElement): List<String> {
        // An array frame fails here too: the daemon decodes one message per frame, never a batch.
        val msg = try {
            strict.decodeFromJsonElement(WireMsg.serializer(), frame)
        } catch (e: Exception) {
            return listOf("does not decode strictly: ${e.message?.lineSequence()?.first()}")
        }
        val obj = frame.jsonObject
        return buildList {
            fun versioned(key: String) {
                val v = obj[key]?.jsonObject?.get("v")
                if (v == null) add("$key has no v") else if (v != JsonPrimitive(SCHEMA_VERSION)) add("$key.v is $v")
            }
            fun id(value: String) { if (!ID.matches(value)) add("id '$value' is not 8 lowercase hex") }
            fun mono(key: String) {
                val raw = obj[key]?.jsonObject?.get("mono") as? JsonPrimitive
                if (raw == null || raw.isString || !INTEGER.matches(raw.content)) add("$key.mono $raw is not an integer >= 0")
            }
            when (msg) {
                is Hello -> {
                    versioned("client")
                    if (msg.client.platform !in constants(Platforms::class.java)) add("unknown platform '${msg.client.platform}'")
                }
                is SignalMsg -> { versioned("signal"); id(msg.signal.id); mono("signal") }
                is Txn -> {
                    versioned("txn"); id(msg.txn.id); mono("txn")
                    for (reason in listOfNotNull(msg.txn.reqBodyOmitted, msg.txn.resBodyOmitted)) {
                        if (reason !in constants(BodyOmission::class.java)) add("unknown omission '$reason'")
                    }
                }
                else -> Unit
            }
        }
    }

    private fun sampleTxn(): WireMsg = Txn(
        NetworkTransaction(
            id = "0000000c", ts = "2026-10-30T12:00:00.000Z", mono = 1600, method = "POST", scheme = "http",
            host = "127.0.0.1", path = "/upload", status = 200, ms = 12, callId = "0000000d",
            resBodyOmitted = BodyOmission.STREAMING, seq = 7,
        ),
    )

    private val JsonElement.type: String? get() = ((this as? JsonObject)?.get("type") as? JsonPrimitive)?.content

    /** This frame with the object under [key] replaced by [change] of it. */
    private fun JsonObject.edit(key: String, change: (Map<String, JsonElement>) -> Map<String, JsonElement>): JsonObject =
        JsonObject(this + (key to JsonObject(change(getValue(key).jsonObject))))

    private companion object {
        val ID = Regex("^[0-9a-f]{8}$")
        val INTEGER = Regex("^[0-9]+$")

        /** The `const val` strings of a constants object, read by reflection so a new one needs no edit here. */
        fun constants(holder: Class<*>): Set<String> = holder.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .map { it.get(null) as String }
            .toSet()
            .also { check(it.isNotEmpty()) { "no constants found on ${holder.simpleName}" } }
    }
}

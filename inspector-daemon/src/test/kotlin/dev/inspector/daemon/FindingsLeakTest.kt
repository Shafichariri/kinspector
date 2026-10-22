package dev.inspector.daemon

import dev.inspector.daemon.archive.DaemonConfig
import dev.inspector.daemon.archive.FindingSettingsSource
import dev.inspector.daemon.archive.SessionRepository
import dev.inspector.daemon.archive.SessionWriter
import dev.inspector.daemon.http.InspectorDaemon
import dev.inspector.daemon.mcp.McpTools
import dev.inspector.daemon.service.ExportMarkdown
import dev.inspector.daemon.service.FindingsEngine
import dev.inspector.daemon.service.FindingsReport
import dev.inspector.daemon.service.SessionExport
import dev.inspector.model.InspectorJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.lang.reflect.Modifier
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.IdentityHashMap
import kotlin.io.path.readBytes
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The redaction guard for findings: secrets planted in headers, the query, both bodies and an error
 * message, and placeholders left by redaction, reach none of the REST reply, the MCP text, an
 * export's findings, the Markdown section, the HAR's finding notes, or the accumulators' fields. A key
 * may show its last four characters and nothing more of it.
 *
 * It first checks the secrets really are in the archive, so a fixture that stopped planting them
 * fails here instead of passing for want of anything to find.
 */
class FindingsLeakTest {

    private lateinit var tmp: Path
    private lateinit var config: DaemonConfig

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("inspector-findings-leak")
        config = DaemonConfig(port = ServerSocket(0).use { it.localPort }, dataDir = tmp)
        Files.createDirectories(config.sessionsDir)
        writeLeakSession()
    }

    @AfterTest
    fun tearDown() {
        tmp.toFile().deleteRecursively()
    }

    /** What the fixture plants; one function, so a fixture that stops planting is one change. */
    private fun plant(secret: String): String = secret

    private val auth = "Bearer PLANTED-AUTH-0a1b2c3d4e"
    private val key = "k-PLANTED-7a1f-SECRET-9f3a"
    private val otherKey = "k-PLANTED-0002-SECRET-bbbb"
    private val query = "PLANTED-QUERY-77aa"
    private val reqField = "PLANTED-REQ-4111111111111111"
    private val resField = "PLANTED-RES-ffffeeee"
    private val dupField = "PLANTED-DUP-1234"
    private val errorUrl = "https://$FINDINGS_HOST/v1/checkout?token=PLANTED-ERR-55aa"
    private val placeholder = "‹redacted›"

    private val secrets get() = listOf(auth, key, otherKey, query, reqField, resField, dupField, errorUrl)

    private fun writeLeakSession() {
        val dir = config.sessionsDir.resolve(SESSION)
        val writer = SessionWriter(dir, clientInfo().toSessionMeta(SESSION, "2025-12-31T08:00:00.000Z"))
        fun SessionWriter.add(call: Captured, response: String? = null) = append(call.row, call.body, response?.toByteArray())
        fun Captured.planted(key: String? = null, auth: String? = null, query: String? = null) = copy(
            row = row.copy(
                query = query?.let { "token=${plant(it)}" },
                reqHeaders = row.reqHeaders +
                    listOfNotNull(auth?.let { "Authorization" to listOf(plant(it)) }, key?.let { "Idempotency-Key" to listOf(plant(it)) }),
            ),
        )
        // Ambiguous resubmit: a timeout whose message carries a URL with its query, then a resend.
        writer.add(
            mutation("l0000001", 1_000, "c-timeout", path = "/v1/checkout", status = null, ms = 10_000,
                error = "SocketTimeoutException: ${plant(errorUrl)}", body = """{"card":"${plant(reqField)}"}""")
                .planted(key = key, auth = auth, query = query),
            response = """{"session":"${plant(resField)}"}""",
        )
        writer.add(
            mutation("l0000002", 15_000, "c-resend", path = "/v1/checkout", body = """{"card":"${plant(reqField)}","n":2}""")
                .planted(key = otherKey, auth = auth),
            response = """{"session":"${plant(resField)}"}""",
        )
        // Key reused with a changed body: the key's tail is the one thing a finding may show.
        writer.add(mutation("l0000003", 20_000, "c-key-a", path = "/v1/orders", body = """{"qty":1,"note":"${plant(reqField)}"}""").planted(key = key))
        writer.add(mutation("l0000004", 21_000, "c-key-b", path = "/v1/orders", body = """{"qty":2,"note":"${plant(reqField)}"}""").planted(key = key))
        // Duplicate mutation over a body holding a secret: compared, never shown.
        writer.add(mutation("l0000005", 30_000, "c-dup-a", body = """{"to":"${plant(dupField)}"}"""))
        writer.add(mutation("l0000006", 31_000, "c-dup-b", body = """{"to":"${plant(dupField)}"}"""))
        // Redacted at capture: placeholders in a key, a header and a body, retried.
        val redacted = listOf("header:authorization", "header:idempotency-key", "body:req:$.pin")
        for ((id, attempt) in listOf("l0000007" to 1, "l0000008" to 2)) {
            val call = mutation(id, 40_000L + attempt * 300, "c-redacted", path = "/v1/transfers", status = if (attempt == 1) 503 else 201,
                attempt = attempt, body = """{"pin":"$placeholder"}""", redacted = redacted)
            writer.add(call.copy(row = call.row.copy(reqHeaders = call.row.reqHeaders + mapOf("Authorization" to listOf(placeholder), "Idempotency-Key" to listOf(placeholder)))))
        }
        writer.close()
    }

    /** Every surface's findings text, by name. */
    private fun surfaces(): Map<String, String> = runBlocking {
        val daemon = InspectorDaemon(config)
        daemon.start(wait = false)
        val http = HttpClient(CIO)
        try {
            val base = "http://127.0.0.1:${config.port}"
            withTimeoutOrNull(10_000) {
                while (runCatching { http.get("$base/api/server").status.value }.getOrNull() != 200) delay(50)
            }
            val rest = http.get("$base/api/sessions/$SESSION/findings").bodyAsText()
            val har = http.get("$base/api/sessions/$SESSION/har").bodyAsText()
            val notes = Json.parseToJsonElement(har).jsonObject.getValue("log").jsonObject.getValue("entries").jsonArray
                .mapNotNull { it.jsonObject["comment"]?.jsonPrimitive?.content }
                .flatMap { it.split("; ") }
                .filter { it.startsWith("finding ") }
            val repository = SessionRepository(config)
            val dir = repository.resolve(SESSION)!!
            val document = SessionExport.build(dir, repository, config.routeTemplates, engine(), "9.9.9")
            val markdown = ExportMarkdown.render(document)
            mapOf(
                "REST" to rest,
                "MCP" to mcpCallTool(McpTools(config), "list_findings", """{"session":"$SESSION"}"""),
                "export findings" to InspectorJson.encodeToString(FindingsReport.serializer(), document.findings),
                "Markdown section" to markdown.substring(markdown.indexOf("## Findings"), markdown.indexOf("## Timeline")),
                "HAR finding notes" to notes.joinToString("\n"),
                "accumulator fields" to accumulatorStrings().joinToString("\n"),
            )
        } finally {
            http.close()
            daemon.stop()
        }
    }

    private fun engine() = FindingsEngine(FindingSettingsSource(tmp), config.routeTemplates)

    /** Every string reachable from the payment accumulator's fields after a fold and a collect. */
    private fun accumulatorStrings(): List<String> {
        val repository = SessionRepository(config)
        val dir = repository.resolve(SESSION)!!
        val engine = engine()
        val fold = engine.fold(engine.current(), dir) { id, side -> repository.readBody(dir, id, side) }
        repository.readTransactions(dir).forEach(fold::add)
        engine.collect(fold)
        val found = ArrayList<String>()
        val seen = IdentityHashMap<Any, Unit>()
        fun walk(value: Any?, depth: Int) {
            if (value == null || depth > 40 || seen.put(value, Unit) != null) return
            when (value) {
                is String -> found += value
                is ByteArray -> found += String(value)
                is Array<*> -> value.forEach { walk(it, depth + 1) }
                is Iterable<*> -> value.forEach { walk(it, depth + 1) }
                is Map<*, *> -> value.forEach { (k, v) -> walk(k, depth + 1); walk(v, depth + 1) }
                is Pair<*, *> -> { walk(value.first, depth + 1); walk(value.second, depth + 1) }
                else -> {
                    // Into our own types, as the sensitive note's technique does; a JDK type holds none of ours.
                    var type: Class<*>? = value.javaClass
                    while (type != null && type.name.startsWith("dev.inspector.")) {
                        for (field in type.declaredFields) {
                            if (Modifier.isStatic(field.modifiers)) continue
                            field.isAccessible = true
                            walk(field.get(value), depth + 1)
                        }
                        type = type.superclass
                    }
                }
            }
        }
        fold.parts.forEach { (_, part) -> walk(part.second, 0) }
        // The walker must reach what it is meant to search: the rows' ids and a key digest.
        assertTrue("l0000001" in found && found.any { it.length == 64 && it.all { c -> c in "0123456789abcdef" } }, "the walk found nothing to check")
        return found
    }

    @Test
    fun `no planted secret reaches any surface, and a key shows only its last four characters`() {
        val archive = Files.walk(config.sessionsDir.resolve(SESSION)).use { paths ->
            paths.filter { Files.isRegularFile(it) }.toList().joinToString("\n") { String(it.readBytes()) }
        }
        val missing = secrets.filter { it !in archive }
        assertEquals(emptyList(), missing, "the fixture planted nothing to find")

        val surfaces = surfaces()
        // The rules did fire on the planted rows, so there was something for each surface to leak.
        assertTrue(listOf("ambiguous-resubmit", "key-reused-body-changed", "duplicate-mutation", "retry-with-key").all { it in surfaces.getValue("REST") })
        val leaks = surfaces.flatMap { (surface, text) ->
            val windows = listOf(key, otherKey).flatMap { k -> k.windowed(5) }
            (secrets + "PLANTED" + "SECRET" + placeholder + windows)
                .filter { it in text }
                .map { "$surface: $it" }
        }
        assertEquals(emptyList(), leaks.distinct())
        assertTrue("•••• 9f3a" in surfaces.getValue("REST"), "the key's masked tail is the hint")
    }

    private companion object {
        const val SESSION = "leak"
    }
}

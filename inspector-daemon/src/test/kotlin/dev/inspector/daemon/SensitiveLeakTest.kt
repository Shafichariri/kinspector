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
import dev.inspector.model.NetworkTransaction
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
import java.util.Base64
import java.util.IdentityHashMap
import kotlin.io.path.readBytes
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The guard for "the detector must not become the leak": a session with a unique secret planted per
 * rule and location, one of them as a JSON key, and every surface that carries findings searched for
 * each secret and for any six-character window of one: the REST reply and the `list_findings` reply
 * (dismissed findings included), the accumulator's own state, the JSON export's findings (which the
 * HTML export embeds), the Markdown section and every HAR entry comment. A mask may show what its
 * table allows (a card's last four, an IBAN's country, check digits and last four), and no window of
 * six reaches past that.
 *
 * It first checks every secret is in the archive and that every planted rule fired, so a fixture that
 * stopped planting fails here instead of passing for want of anything to find. Shown red with
 * `SensitiveMask.hint` returning the value, with a note carrying the value, and with key masking removed.
 */
class SensitiveLeakTest {

    private lateinit var tmp: Path
    private lateinit var config: DaemonConfig

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("inspector-sensitive-leak")
        config = DaemonConfig(port = ServerSocket(0).use { it.localPort }, dataDir = tmp)
        Files.createDirectories(config.sessionsDir)
        tmp.resolve("config.json").writeText(
            """{"findings":{"rules":{"sensitive.email":{"enabled":true},"sensitive.phone":{"enabled":true}},
                "sensitive":{"valueRules":[{"id":"member","label":"Member number","shape":"AA-######-#"}]}},
               "findingDismissals":[{"ruleId":"sensitive.field.name","route":"POST https://$HOST/v1/profile",
                "location":"reqBody","path":"$.first_name","reason":"names are expected here"}]}""",
        )
        writeSession()
    }

    @AfterTest
    fun tearDown() {
        tmp.toFile().deleteRecursively()
    }

    /** [prefix] with its Luhn check digit, so each planted card is unique and valid. */
    private fun card(prefix: String): String {
        for (check in '0'..'9') {
            val candidate = prefix + check
            val sum = candidate.reversed().mapIndexed { i, c ->
                val d = c - '0'
                if (i % 2 == 1) (d * 2).let { if (it > 9) it - 9 else it } else d
            }.sum()
            if (sum % 10 == 0) return candidate
        }
        error("unreachable")
    }

    private fun jwt(subject: String) = listOf("""{"alg":"HS256"}""", """{"sub":"$subject"}""", "sig-$subject")
        .joinToString(".") { Base64.getUrlEncoder().withoutPadding().encodeToString(it.toByteArray()) }

    private val cardInBody = card("493817265012345")
    private val cardInUrl = card("523987160045218")
    private val cardInText = card("371946285037")
    private val ibanInBody = "GB29NWBK60161331926819"
    private val ibanInQuery = "DE89370400440532013000"
    private val jwtInQuery = jwt("plantedjwtsubject")
    private val jwtAsKey = jwt("plantedkeysubject")
    private val jwtInPath = jwt("plantedpathsubject")
    private val ibanInPath = "NL91ABNA0417164300"
    private val apiKey = "Zr5hQ1wE7uI3oP"
    private val password = "Qx7vR2mK9pL4tZ"
    private val passport = "Hb3nW8qJ5sD1fY"
    private val firstName = "Ty6gN4kM2xC8vB"
    private val bearer = "Lk9jH2gF6dS4aW"
    private val cookie = "Mn3bV7cX1zQ9wE"
    private val email = "zq7k3x@mv9t2r.qa"
    private val phone = "+44 20 7946 0738"
    private val member = "QK-918273-4"

    /** One per rule and location, and one as a JSON key. */
    private val secrets get() = listOf(
        cardInBody, cardInUrl, cardInText, ibanInBody, ibanInQuery, ibanInPath, jwtInQuery, jwtAsKey, jwtInPath, apiKey, password,
        passport, firstName, bearer, cookie, email, phone, member,
    )

    /** What a mask may show of each: the window search allows these and nothing longer. */
    private fun allowed(secret: String): Set<String> = when (secret) {
        cardInBody, cardInUrl, cardInText -> setOf(secret.takeLast(4))
        ibanInBody, ibanInQuery, ibanInPath -> setOf(secret.take(4), secret.takeLast(4))
        else -> emptySet()
    }

    private fun row(
        id: String,
        mono: Long,
        path: String,
        method: String = "POST",
        scheme: String = "https",
        query: String? = null,
        reqType: String? = null,
        reqBytes: Int = 0,
        resType: String? = null,
        resBytes: Int = 0,
        reqHeaders: Map<String, List<String>> = emptyMap(),
    ) = NetworkTransaction(
        id = id, ts = "2026-10-22T08:00:00.000Z", mono = mono, method = method, scheme = scheme, host = HOST, path = path,
        query = query, status = 200, callId = "c-$id", reqBytes = reqBytes.toLong(), resBytes = resBytes.toLong(),
        reqHeaders = reqHeaders, reqContentType = reqType, resContentType = resType,
    )

    private fun writeSession() {
        val writer = SessionWriter(config.sessionsDir.resolve(SESSION), clientInfo().toSessionMeta(SESSION, "2026-10-22T08:00:00.000Z"))
        fun add(row: NetworkTransaction, req: String? = null, res: String? = null) {
            val reqBytes = req?.toByteArray()
            val resBytes = res?.toByteArray()
            writer.append(
                row.copy(reqBytes = reqBytes?.size?.toLong() ?: 0, resBytes = resBytes?.size?.toLong() ?: 0),
                reqBytes, resBytes,
            )
        }
        add(
            row("s0000001", 1_000, "/v1/profile", reqType = "application/json"),
            req = """{"card":"$cardInBody","password":"$password","passport_number":"$passport","first_name":"$firstName",
                      "contact":"$email","tel":"$phone","memberNo":"$member"}""",
        )
        add(row("s0000002", 2_000, "/v1/cards/$cardInUrl", method = "GET"))
        // A JWT holds dots and an IBAN has no lowercase, so the route heuristic leaves both literal.
        add(row("s0000008", 8_000, "/v1/verify/$jwtInPath", method = "GET"))
        add(row("s0000009", 9_000, "/v1/accounts/$ibanInPath/close"))
        add(row("s0000003", 3_000, "/v1/link", method = "GET", query = "t=$jwtInQuery&api_key=$apiKey&acct=$ibanInQuery"))
        add(row("s0000004", 4_000, "/v1/statement", method = "GET", resType = "application/json"), res = """{"iban":"$ibanInBody"}""")
        add(row("s0000005", 5_000, "/v1/sessions", method = "GET", resType = "application/json"), res = """{"sessions":{"$jwtAsKey":{"password":"$password"}}}""")
        add(row("s0000006", 6_000, "/v1/note", reqType = "text/plain"), req = "pay with $cardInText please")
        add(
            row("s0000007", 7_000, "/v1/legacy", method = "GET", scheme = "http",
                reqHeaders = mapOf("Authorization" to listOf("Bearer $bearer"), "Cookie" to listOf("sid=$cookie"))),
        )
        writer.close()
    }

    private fun surfaces(): Map<String, String> = runBlocking {
        val daemon = InspectorDaemon(config)
        daemon.start(wait = false)
        val http = HttpClient(CIO)
        try {
            val base = "http://127.0.0.1:${config.port}"
            withTimeoutOrNull(10_000) {
                while (runCatching { http.get("$base/api/server").status.value }.getOrNull() != 200) delay(50)
            }
            val rest = http.get("$base/api/sessions/$SESSION/findings?includeDismissed=true").bodyAsText()
            val har = http.get("$base/api/sessions/$SESSION/har").bodyAsText()
            val comments = Json.parseToJsonElement(har).jsonObject.getValue("log").jsonObject.getValue("entries").jsonArray
                .mapNotNull { it.jsonObject["comment"]?.jsonPrimitive?.content }
            val repository = SessionRepository(config)
            val dir = repository.resolve(SESSION)!!
            val document = SessionExport.build(dir, repository, config.routeTemplates, engine(), "9.9.9")
            val markdown = ExportMarkdown.render(document)
            val exported = Json.parseToJsonElement(SessionExport.toJson(document)).jsonObject.getValue("findings").toString()
            // The HTML export embeds this same document, so its findings are these.
            mapOf(
                "REST" to rest,
                "MCP" to mcpCallTool(McpTools(config), "list_findings", """{"session":"$SESSION","includeDismissed":true,"limit":200}"""),
                "JSON export findings" to exported,
                "Markdown section" to markdown.substring(markdown.indexOf("## Findings"), markdown.indexOf("## Timeline")),
                "HAR comments" to comments.joinToString("\n"),
                "accumulator" to accumulatorStrings().joinToString("\n"),
            )
        } finally {
            http.close()
            daemon.stop()
        }
    }

    private fun engine() = FindingsEngine(FindingSettingsSource(tmp), config.routeTemplates)

    /** Every string reachable from the sensitive accumulator after a fold and a collect. */
    private fun accumulatorStrings(): List<String> {
        val repository = SessionRepository(config)
        val dir = repository.resolve(SESSION)!!
        val engine = engine()
        val fold = engine.fold(engine.current(), dir) { id, side -> repository.readBody(dir, id, side) }
        repository.readTransactions(dir).forEach(fold::add)
        engine.collect(fold)
        val accumulator = fold.parts.single { it.first.category == "sensitive" }.second.second
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
        walk(accumulator, 0)
        assertTrue("s0000001" in found && found.any { "••••" in it }, "the walk reached the groups and their hints")
        return found
    }

    @Test
    fun `no planted secret, and no six characters of one beyond its mask, reaches any surface`() {
        val archive = Files.walk(config.sessionsDir.resolve(SESSION)).use { paths ->
            paths.filter { Files.isRegularFile(it) }.toList().joinToString("\n") { String(it.readBytes()) }
        }
        assertEquals(emptyList(), secrets.filter { it !in archive }, "the fixture planted nothing to find")

        val surfaces = surfaces()
        val report = InspectorJson.decodeFromString(FindingsReport.serializer(), surfaces.getValue("REST"))
        val fired = report.findings.map { it.ruleId }.toSet()
        val expected = setOf(
            "sensitive.card-number", "sensitive.iban", "sensitive.jwt-in-url", "sensitive.secret-in-url",
            "sensitive.credentials-over-http", "sensitive.email", "sensitive.phone", "sensitive.field.secret",
            "sensitive.field.identity", "sensitive.field.name", "sensitive.custom.member",
        )
        assertEquals(emptySet(), expected - fired, "every planted rule fired")
        assertEquals(1, report.dismissedCount, "a dismissed finding is on every surface too")

        val leaks = surfaces.flatMap { (surface, text) ->
            secrets.flatMap { secret ->
                val windows = secret.windowed(6).filter { window -> allowed(secret).none { window in it } }
                (listOf(secret) + windows).filter { it in text }.map { "$surface: ${it.take(40)}" }
            }
        }
        assertEquals(emptyList(), leaks.distinct())
        assertTrue("•••• ${cardInBody.takeLast(4)}" in surfaces.getValue("REST"), "the card's masked tail is the hint")
        assertTrue("$.sessions.•.password" in surfaces.getValue("REST"), "the key that was a token is a dot")
        assertTrue("GET https://$HOST/v1/verify/•" in surfaces.getValue("REST"), "a route segment that was a token is a dot")
        assertTrue("POST https://$HOST/v1/accounts/•/close" in surfaces.getValue("REST"))
    }

    private companion object {
        const val SESSION = "sensitive-leak"
        const val HOST = "api.example.invalid"
    }
}

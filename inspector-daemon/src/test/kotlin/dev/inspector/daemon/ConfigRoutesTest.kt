package dev.inspector.daemon

import dev.inspector.daemon.archive.DaemonConfig
import dev.inspector.daemon.http.InspectorDaemon
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readBytes
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * `GET` and `PUT /api/config` on a running daemon started the way `serve` starts one, with flags.
 *
 * `ConfigFileTest` holds the merge rules. This holds what the page reads: the reply's shape, that
 * a save changes the file and not the daemon it was sent to, and that the flags overriding the file
 * are named so the page can say why a value will not move.
 */
class ConfigRoutesTest {

    private lateinit var tmp: Path
    private lateinit var config: DaemonConfig
    private lateinit var daemon: InspectorDaemon
    private lateinit var http: HttpClient

    @BeforeTest
    fun setUp() = runBlocking {
        tmp = Files.createTempDirectory("inspector-config-routes")
        // As `inspector serve --port <p> --max-sessions 5` would: two keys come from flags.
        config = DaemonConfig.load(dataDir = tmp, port = ServerSocket(0).use { it.localPort }, maxSessions = 5)
        Files.createDirectories(config.sessionsDir)
        daemon = InspectorDaemon(config)
        daemon.start(wait = false)
        http = HttpClient(CIO)
        withTimeoutOrNull(10_000) {
            while (runCatching { http.get(url("/api/server")).status.value }.getOrNull() != 200) delay(50)
        }
        Unit
    }

    @AfterTest
    fun tearDown() {
        http.close()
        daemon.stop()
        tmp.toFile().deleteRecursively()
    }

    private fun url(path: String) = "http://127.0.0.1:${config.port}$path"
    private val file get() = tmp.resolve("config.json")

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private suspend fun save(body: String, type: ContentType = ContentType.Application.Json): HttpResponse =
        http.put(url("/api/config")) {
            header("X-Inspector-Control", "1")
            setBody(TextContent(body, type))
        }

    /** `effective` without `findings` and `findingDismissals`, which are read live and have their own tests below. */
    private fun startKeys(reply: JsonObject) = JsonObject(reply.getValue("effective").jsonObject - "findings" - "findingDismissals")

    private fun findings(reply: JsonObject) = reply.getValue("effective").jsonObject.getValue("findings").jsonObject

    private fun payment(reply: JsonObject, field: String) =
        findings(reply).getValue("payment").jsonObject.getValue(field).toString()

    private fun effective(maxSessions: Int) = json(
        """{"port":${config.port},"maxSessions":$maxSessions,"maxTotalMb":300,"signalCaps":{"cache":500,"state":500},"routeTemplates":[]}"""
    )

    @Test
    fun `GET answers without the header and names the file, what runs and the flags`() = runBlocking {
        val response = http.get(url("/api/config"))

        assertEquals(200, response.status.value)
        val reply = json(response.bodyAsText())
        assertEquals(file.toString(), reply.getValue("path").toString().trim('"'))
        assertEquals(JsonNull, reply.getValue("file"), "no config.json yet")
        assertEquals(effective(maxSessions = 5), startKeys(reply))
        assertEquals(Json.parseToJsonElement("""["maxSessions","port"]"""), reply.getValue("flags"))
    }

    @Test
    fun `PUT writes the file, reports the flags and leaves what runs unchanged`() = runBlocking {
        val response = save("""{"maxSessions":9,"maxTotalMb":50}""")

        assertEquals(200, response.status.value, response.bodyAsText())
        val reply = json(response.bodyAsText())
        assertEquals(json("""{"maxSessions":9,"maxTotalMb":50}"""), reply.getValue("file"))
        assertEquals(effective(maxSessions = 5), startKeys(reply), "a save applied before a restart")
        assertEquals(Json.parseToJsonElement("""["maxSessions","port"]"""), reply.getValue("flags"))
        // And a later GET reads the file back, still with the daemon's own values beside it.
        assertEquals(reply, json(http.get(url("/api/config")).bodyAsText()))
    }

    @Test
    fun `a malformed file is a 409 and is not touched`() = runBlocking {
        file.writeText("""{"maxSessions":""")
        val before = file.readBytes()

        val response = save("""{"maxSessions":9}""")

        assertEquals(409, response.status.value)
        assertContains(response.bodyAsText(), "could not read")
        assertContentEquals(before, file.readBytes())
        val reply = json(http.get(url("/api/config")).bodyAsText())
        assertEquals(JsonNull, reply.getValue("file"))
        assertContains(reply.getValue("fileError").toString(), "not a JSON object")
    }

    @Test
    fun `an unknown key is a 400 naming it, and a body not declared as JSON is a 415`() = runBlocking {
        val unknown = save("""{"maxsessions":9}""")
        assertEquals(400, unknown.status.value)
        assertContains(unknown.bodyAsText(), "'maxsessions'")

        assertEquals(415, save("""{"maxSessions":9}""", ContentType.Text.Plain).status.value)
        assertEquals(400, save("[]").status.value)
    }

    @Test
    fun `PUT without the control header is refused`() = runBlocking {
        val response = http.put(url("/api/config")) { setBody(TextContent("""{"maxSessions":9}""", ContentType.Application.Json)) }

        assertEquals(403, response.status.value)
        assertEquals(false, file.toFile().exists(), "a refused save wrote the file")
    }

    @Test
    fun `a bad routeTemplates entry is a 400 naming it`() = runBlocking {
        val response = save("""{"routeTemplates":["/articles/{slug}","/a/{bad name}"]}""")

        assertEquals(400, response.status.value)
        assertContains(response.bodyAsText(), "routeTemplates entry '/a/{bad name}'")
        assertEquals(false, file.toFile().exists(), "a refused save wrote the file")
    }

    @Test
    fun `effective routeTemplates is what was loaded at start, not what was just saved`() = runBlocking {
        val saved = json(save("""{"routeTemplates":["/articles/{slug}"]}""").bodyAsText())
        assertEquals(json("""{"routeTemplates":["/articles/{slug}"]}"""), saved.getValue("file"))
        assertEquals(Json.parseToJsonElement("[]"), saved.getValue("effective").jsonObject.getValue("routeTemplates"))

        // A daemon started on that file reports it, with an entry it skipped left out.
        file.writeText("""{"routeTemplates":["/articles/{slug}","nope","/v2/{x}"]}""")
        val restarted = DaemonConfig.load(dataDir = tmp, port = ServerSocket(0).use { it.localPort })
        val second = InspectorDaemon(restarted)
        second.start(wait = false)
        try {
            val reply = withTimeoutOrNull(10_000) {
                var text: String? = null
                while (text == null) {
                    text = runCatching { http.get("http://127.0.0.1:${restarted.port}/api/config").bodyAsText() }.getOrNull()
                    if (text == null) delay(50)
                }
                text
            }
            assertEquals(
                Json.parseToJsonElement("""["/articles/{slug}","/v2/{x}"]"""),
                json(reply!!).getValue("effective").jsonObject.getValue("routeTemplates"),
            )
        } finally {
            second.stop()
        }
    }

    @Test
    fun `effective findings lists every shipped rule and the payment defaults`() = runBlocking {
        val reply = json(http.get(url("/api/config")).bodyAsText())
        val rules = findings(reply).getValue("rules").jsonArray.map { it.jsonObject }
        assertEquals(
            listOf(
                "payment.duplicate-mutation", "payment.key-reused-body-changed", "payment.retry-without-key",
                "payment.ambiguous-resubmit", "payment.key-missing-payment-path", "payment.retry-with-key",
            ),
            rules.map { it.getValue("id").jsonPrimitive.content },
        )
        assertEquals(
            json(
                """{"id":"payment.duplicate-mutation","category":"payment","categoryTitle":"Payment safety",""" +
                    """"title":"Duplicate mutation","enabled":true,"severity":"high","defaultSeverity":"high"}""",
            ),
            rules.first(),
        )
        assertEquals(
            json(
                """{"idempotencyHeaders":["Idempotency-Key","X-Idempotency-Key"],""" +
                    """"paymentPaths":["payment","checkout","charge","transfer","refund","payout"],""" +
                    """"ignorePaths":[],"duplicateWindowMs":3000,"resubmitWindowMs":60000}""",
            ),
            findings(reply).getValue("payment"),
        )
        assertEquals(null, reply["findingsConfigError"])
    }

    @Test
    fun `effective findings follows a save and a hand edit without a restart`() = runBlocking {
        val saved = json(
            save("""{"findings":{"rules":{"payment.retry-with-key":{"enabled":false}},"payment":{"duplicateWindowMs":5000}}}""").bodyAsText(),
        )
        val retryWithKey = findings(saved).getValue("rules").jsonArray.map { it.jsonObject }
            .single { it.getValue("id").jsonPrimitive.content == "payment.retry-with-key" }
        assertEquals("false", retryWithKey.getValue("enabled").toString(), "the save's own reply")
        assertEquals("5000", payment(saved, "duplicateWindowMs"))

        file.writeText("""{"findings":{"rules":{"payment.duplicate-mutation":{"severity":"medium"}}}}""" + "\n")
        val edited = json(http.get(url("/api/config")).bodyAsText())
        val duplicate = findings(edited).getValue("rules").jsonArray.first().jsonObject
        assertEquals("medium", duplicate.getValue("severity").jsonPrimitive.content, "a hand edit, read on the next GET")
        assertEquals("3000", payment(edited, "duplicateWindowMs"))
    }

    @Test
    fun `a hand edit that breaks the findings key keeps the last good settings and says so`() = runBlocking {
        file.writeText("""{"findings":{"payment":{"duplicateWindowMs":5000}}}""")
        assertEquals("5000", payment(json(http.get(url("/api/config")).bodyAsText()), "duplicateWindowMs"))

        file.writeText("""{"findings":{"payment":{"duplicateWindowMs":5000""")
        val reply = json(http.get(url("/api/config")).bodyAsText())
        assertEquals("5000", payment(reply, "duplicateWindowMs"))
        assertContains(reply.getValue("findingsConfigError").jsonPrimitive.content, "not a JSON object")
    }

    @Test
    fun `effective findingDismissals follows a save and a hand edit, bad entries skipped`() = runBlocking {
        val entry = """{"ruleId":"payment.retry-with-key","route":"POST https://api.example.invalid/pay","reason":"expected"}"""
        assertEquals("[]", json(http.get(url("/api/config")).bodyAsText()).getValue("effective").jsonObject.getValue("findingDismissals").toString())
        val saved = json(save("""{"findingDismissals":[$entry]}""").bodyAsText())
        assertEquals(
            """[{"ruleId":"payment.retry-with-key","route":"POST https://api.example.invalid/pay","location":null,"path":null,"reason":"expected"}]""",
            saved.getValue("effective").jsonObject.getValue("findingDismissals").toString(),
            "the save's own reply",
        )

        file.writeText("""{"findingDismissals":[{"ruleId":"r.x","route":"GET /x"},$entry]}""")
        val edited = json(http.get(url("/api/config")).bodyAsText())
        assertEquals(1, edited.getValue("effective").jsonObject.getValue("findingDismissals").jsonArray.size)
        assertContains(edited.getValue("findingsConfigError").jsonPrimitive.content, "findingDismissals entry 1 needs a reason")

        val refused = save("""{"findingDismissals":[{"ruleId":"r.x","route":"GET /x"}]}""")
        assertEquals(400, refused.status.value)
        assertContains(refused.bodyAsText(), "findingDismissals entry 1 needs a reason")
    }

    @Test
    fun `a bad findings value is a 400 in the validator's words`() = runBlocking {
        val response = save("""{"findings":{"rules":{"payment.no-such-rule":{"enabled":false}}}}""")
        assertEquals(400, response.status.value)
        assertContains(response.bodyAsText(), "unknown rule 'payment.no-such-rule'")
        assertEquals(false, file.toFile().exists(), "a refused save wrote the file")
    }
}

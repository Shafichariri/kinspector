package dev.inspector.daemon

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * HAR export.
 *
 * The assertions that matter most here are the ones about what the export does *not* claim. HAR
 * was designed by wire-level tools and asks for several things capture never saw; the failure
 * mode of an exporter is to fill those in plausibly, producing a file that reads as measured.
 */
class HarTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun export(
        transactions: List<dev.inspector.model.NetworkTransaction>,
        bodies: Map<Pair<String, String>, ByteArray> = emptyMap(),
    ): JsonObject = json.parseToJsonElement(
        Har.export(
            meta = clientInfo().toSessionMeta("2026-08-16T10-14-02_app_dev_debug", "2026-08-16T10:14:02.000Z"),
            transactions = transactions,
            bodyOf = { id, side -> bodies[id to side] },
            creatorVersion = "1.2.3",
        )
    ).jsonObject

    private fun JsonObject.entries() = this["log"]!!.jsonObject["entries"]!!.jsonArray
    private fun JsonObject.entry(index: Int) = entries()[index].jsonObject
    private fun JsonObject.str(vararg path: String): String? {
        var node: JsonObject = this
        for (key in path.dropLast(1)) node = node[key]!!.jsonObject
        return node[path.last()]?.jsonPrimitive?.content
    }

    @Test
    fun `it is a HAR 1_2 file naming the daemon that wrote it`() {
        val har = export(listOf(txn("aaaa1111")))
        val log = har["log"]!!.jsonObject
        assertEquals("1.2", log["version"]!!.jsonPrimitive.content)
        assertEquals("Inspector", log.str("creator", "name"))
        assertEquals("1.2.3", log.str("creator", "version"))
        // The session is identified in the log comment, so a file that has left the machine can
        // still be traced back to the app, build and device that produced it.
        assertContains(log["comment"]!!.jsonPrimitive.content, "2026-08-16T10-14-02_app_dev_debug")
        assertContains(log["comment"]!!.jsonPrimitive.content, "com.example.projectx")
    }

    @Test
    fun `entries are chronological by the device clock`() {
        val har = export(
            listOf(
                txn("cccc3333", path = "/third", mono = 300),
                txn("aaaa1111", path = "/first", mono = 100),
                txn("bbbb2222", path = "/second", mono = 200),
            )
        )
        val paths = har.entries().map { it.jsonObject.str("request", "url") }
        assertEquals(listOf("/first", "/second", "/third"), paths.map { it!!.substringAfter("api.example.com") })
    }

    @Test
    fun `timings capture never measured are minus one, not invented`() {
        val entry = export(listOf(txn("aaaa1111", ms = 143))).entry(0)
        val timings = entry["timings"]!!.jsonObject
        // The whole point. A breakdown of send/wait/receive would draw a waterfall in DevTools out
        // of numbers nobody measured, and a waterfall is read as evidence.
        for (phase in listOf("send", "wait", "receive", "blocked", "dns", "connect", "ssl")) {
            assertEquals("-1", timings[phase]!!.jsonPrimitive.content, "$phase should be unknown")
        }
        // The one timing that is real.
        assertEquals("143", entry["time"]!!.jsonPrimitive.content)
    }

    @Test
    fun `headersSize is unknown but bodySize is not`() {
        val entry = export(listOf(txn("aaaa1111", resBytes = 4096))).entry(0)
        assertEquals("-1", entry["response"]!!.jsonObject["headersSize"]!!.jsonPrimitive.content)
        assertEquals("-1", entry["request"]!!.jsonObject["headersSize"]!!.jsonPrimitive.content)
        // reqBytes/resBytes are true sizes, counted even when the body itself was not captured,
        // so reporting them as unknown would throw away something that was measured.
        assertEquals("4096", entry["response"]!!.jsonObject["bodySize"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a body that is not valid UTF-8 is base64, and size stays the byte count`() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0xFF.toByte())
        val har = export(
            listOf(txn("aaaa1111", resBytes = png.size.toLong())),
            bodies = mapOf(("aaaa1111" to "res") to png),
        )
        val content = har.entry(0)["response"]!!.jsonObject["content"]!!.jsonObject
        assertEquals("base64", content["encoding"]!!.jsonPrimitive.content)
        assertTrue(Base64.getDecoder().decode(content["text"]!!.jsonPrimitive.content).contentEquals(png))
        // Not the length of the base64, which is a third longer and would misreport the payload.
        assertEquals("9", content["size"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a text body is emitted as text with no encoding marker`() {
        val har = export(
            listOf(txn("aaaa1111")),
            bodies = mapOf(("aaaa1111" to "res") to """{"ok":true}""".toByteArray()),
        )
        val content = har.entry(0)["response"]!!.jsonObject["content"]!!.jsonObject
        assertNull(content["encoding"])
        assertEquals("""{"ok":true}""", content["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a binary request body is declared absent rather than mangled`() {
        val binary = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00, 0x01)
        val har = export(
            listOf(txn("aaaa1111", method = "POST")),
            bodies = mapOf(("aaaa1111" to "req") to binary),
        )
        val post = har.entry(0)["request"]!!.jsonObject["postData"]!!.jsonObject
        // HAR's postData has no base64 escape hatch, unlike response content. Decoding it anyway
        // would fill the field with replacement characters that read as a captured body.
        assertEquals("", post["text"]!!.jsonPrimitive.content)
        assertContains(post["comment"]!!.jsonPrimitive.content, "no base64")
        assertContains(post["comment"]!!.jsonPrimitive.content, "4 bytes")
    }

    @Test
    fun `a transport failure is status 0, not a success`() {
        val har = export(listOf(txn("aaaa1111", status = null, ms = null, error = "ConnectException: refused")))
        val entry = har.entry(0)
        assertEquals("0", entry["response"]!!.jsonObject["status"]!!.jsonPrimitive.content)
        assertContains(entry["comment"]!!.jsonPrimitive.content, "ConnectException: refused")
        // Nothing completed, so there is no duration to report.
        assertEquals("-1", entry["time"]!!.jsonPrimitive.content)
    }

    @Test
    fun `redaction is disclosed on the entry that was redacted`() {
        val redacted = txn("aaaa1111").copy(redacted = listOf("header:authorization", "query:token"))
        val comment = export(listOf(redacted)).entry(0)["comment"]!!.jsonPrimitive.content
        // Handing somebody a redacted capture as though it were complete is the export lying by
        // omission — they would read a missing credential as one that was never sent.
        assertContains(comment, "redacted at capture: header:authorization, query:token")
    }

    @Test
    fun `an attempt says which call it belongs to`() {
        val retry = txn("bbbb2222", attempt = 2, callId = "call-7")
        val comment = export(listOf(retry)).entry(0)["comment"]!!.jsonPrimitive.content
        assertContains(comment, "attempt 2 of call call-7")
    }

    @Test
    fun `redirectURL comes from the Location header`() {
        val hop = txn("aaaa1111", status = 302).copy(
            resHeaders = mapOf("Location" to listOf("https://api.example.com/v2/users/me")),
        )
        assertEquals(
            "https://api.example.com/v2/users/me",
            export(listOf(hop)).entry(0).str("response", "redirectURL"),
        )
        // Nothing to chain to when there is no Location, and HAR wants the empty string there.
        assertEquals("", export(listOf(txn("bbbb2222"))).entry(0).str("response", "redirectURL"))
    }

    @Test
    fun `query parameters are split, and a valueless one survives`() {
        val withQuery = txn("aaaa1111").copy(query = "page=2&debug&token=%2A%2A%2A")
        val params = export(listOf(withQuery)).entry(0)["request"]!!.jsonObject["queryString"]!!.jsonArray
            .map { it.jsonObject["name"]!!.jsonPrimitive.content to it.jsonObject["value"]!!.jsonPrimitive.content }
        // `?debug` is a parameter. Dropping it because it has no `=` would lose the one that most
        // often changes behaviour.
        assertEquals(listOf("page" to "2", "debug" to "", "token" to "%2A%2A%2A"), params)
    }

    // --- the endpoint --------------------------------------------------------

    private lateinit var tmp: Path
    private lateinit var config: DaemonConfig
    private lateinit var daemon: InspectorDaemon
    private lateinit var http: HttpClient

    @BeforeTest
    fun setUp() {
        runBlocking {
            tmp = Files.createTempDirectory("inspector-har")
            config = DaemonConfig(port = ServerSocket(0).use { it.localPort }, dataDir = tmp)
            Files.createDirectories(config.sessionsDir)
            writeSession(
                config, "2026-08-16T10-14-02_app_dev_debug", "2026-08-16T10:14:02.000Z",
                transactions = listOf(
                    txn("aaaa1111", path = "/v2/users/me", status = 200, mono = 100),
                    txn("bbbb2222", path = "/v2/orders", status = 500, mono = 200),
                ),
            )
            SessionWriter.updateLatestLink(
                config.dataDir,
                config.sessionsDir.resolve("2026-08-16T10-14-02_app_dev_debug"),
            )

            // `wait = true` is the default and never returns — it is what `inspector serve` wants
            // and the opposite of what a test does.
            daemon = InspectorDaemon(config)
            daemon.start(wait = false)
            http = HttpClient(CIO)
            withTimeoutOrNull(10_000) {
                while (runCatching { http.get(url("/api/sessions")).status.value }.getOrNull() != 200) delay(50)
            }
        }
    }

    @AfterTest
    fun tearDown() {
        http.close()
        daemon.stop()
        tmp.toFile().deleteRecursively()
    }

    private fun url(path: String) = "http://127.0.0.1:${config.port}$path"

    @Test
    fun `the endpoint serves a downloadable HAR for the whole session`() = runBlocking {
        val response = http.get(url("/api/sessions/latest/har"))
        assertEquals(200, response.status.value)
        // Without this a browser renders a megabyte of JSON instead of saving a file.
        assertContains(
            response.headers["Content-Disposition"].orEmpty(),
            "attachment; filename=\"2026-08-16T10-14-02_app_dev_debug.har\"",
        )
        val har = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(2, har.entries().size)
    }

    @Test
    fun `the endpoint takes the same filter as the transaction list`() = runBlocking {
        val har = json
            .parseToJsonElement(http.get(url("/api/sessions/latest/har?filter=status:500")).bodyAsText())
            .jsonObject
        assertEquals(1, har.entries().size)
        assertContains(har.entry(0).str("request", "url")!!, "/v2/orders")
    }

    @Test
    fun `a bad filter is refused with the parser's own message, not an empty HAR`() = runBlocking {
        val response = http.get(url("/api/sessions/latest/har?filter=status:"))
        // An export that silently came back empty would read as "nothing matched", which is the
        // wrong conclusion and the expensive one.
        assertEquals(400, response.status.value)
        Unit
    }
}

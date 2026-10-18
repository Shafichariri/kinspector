package dev.inspector.daemon

import dev.inspector.model.BodyOmission
import dev.inspector.model.SignalTags
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The session export, in all three forms.
 *
 * What matters most is what an export must not do: lose rows to a cap nobody mentioned, decode a
 * binary body into replacement characters, re-serialise a body it was meant to carry, guess why a
 * body is absent, or let captured text close the script it is embedded in.
 */
class ExportTest {

    private lateinit var tmp: Path
    private lateinit var config: DaemonConfig
    private lateinit var repository: SessionRepository
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("inspector-export")
        config = DaemonConfig(port = 8099, dataDir = tmp)
        Files.createDirectories(config.sessionsDir)
        repository = SessionRepository(config)
    }

    @AfterTest
    fun tearDown() {
        tmp.toFile().deleteRecursively()
    }

    private fun session(id: String = "2026-08-16T10-14-02_app_dev_debug", build: (SessionWriter) -> Unit): Path {
        val dir = config.sessionsDir.resolve(id)
        val writer = SessionWriter(dir, clientInfo().toSessionMeta(id, "2026-08-16T10:14:02.000Z"))
        build(writer)
        writer.close()
        return dir
    }

    private fun build(dir: Path) =
        SessionExport.build(dir, repository, "9.9.9", Instant.parse("2026-10-10T12:00:00Z"))

    // --- the document --------------------------------------------------------------------------

    @Test
    fun `every row of all three streams is exported in device-clock order`() {
        val dir = session {
            it.append(txn("bbbb2222", mono = 200, status = 500), null, null)
            it.append(txn("aaaa1111", mono = 100), null, null)
            it.append(signal(id = "s1", mono = 150), null)
            it.append(marker("tapped", mono = 120))
        }
        val document = build(dir)
        assertEquals(listOf("aaaa1111", "bbbb2222"), document.transactions.map { it.txn.id })
        assertEquals(listOf("s1"), document.signals.map { it.id })
        assertEquals(listOf("tapped"), document.markers.map { it.label })
        // Recounted from the rows, because meta.json lags a live session by up to 30 seconds.
        assertEquals(2, document.meta!!.txnCount)
        assertEquals(1, document.meta!!.errorCount)
        assertEquals("9.9.9", document.exporter.version)
    }

    @Test
    fun `the timeline is oldest first and a marker at a call's instant precedes it`() {
        val dir = session {
            it.append(txn("aaaa1111", mono = 300), null, null)
            it.append(signal(id = "s1", mono = 100), null)
            it.append(marker("about to call", mono = 300))
        }
        val kinds = build(dir).timeline.map { "${it.kind}@${it.mono}" }
        // A plain stable sort by mono with calls first — what the repository's timeline() did
        // before phase E — would put the marker after the very call it was dropped to introduce.
        assertEquals(listOf("signal@100", "marker@300", "txn@300"), kinds)
    }

    @Test
    fun `a session over every page cap exports every row`() {
        // 1000 is the query cap and 2000 the timeline cap. An export must inherit neither.
        val dir = session { writer ->
            (0 until 2001).forEach { writer.append(txn("%08x".format(it), mono = it.toLong()), null, null) }
        }
        val document = build(dir)
        assertEquals(2001, document.transactions.size)
        assertEquals(2001, document.timeline.size)
    }

    @Test
    fun `a text body is carried verbatim and a binary one as base64`() {
        val binary = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00, 0x89.toByte())
        val dir = session {
            it.append(txn("aaaa1111"), """{"id":1234567890123456789}""".toByteArray(), binary)
        }
        val exported = build(dir).transactions.single()
        assertEquals(ExportedBody(ExportedBody.UTF8, """{"id":1234567890123456789}"""), exported.reqBody)
        assertEquals(ExportedBody.BASE64, exported.resBody!!.encoding)
        assertTrue(binary.contentEquals(Base64.getDecoder().decode(exported.resBody!!.text)))
    }

    @Test
    fun `a signal payload is the captured text and is never parsed`() {
        // A parse would keep the second "k" only. The viewers need what the app sent.
        val payload = """{"k":1,"k":2,"id":1234567890123456789}"""
        val dir = session { it.append(signal(id = "s1"), payload.toByteArray()) }
        val document = build(dir)
        assertEquals(payload, document.signalData.getValue("s1").text)
        assertNull(document.signals.single().data)
    }

    @Test
    fun `a body whose file has gone is absent rather than invented`() {
        val dir = session { it.append(txn("aaaa1111"), null, "gone".toByteArray()) }
        Files.delete(SessionLayout.bodyFile(dir, "aaaa1111", "res"))
        val exported = build(dir).transactions.single()
        assertNull(exported.resBody)
        // The row still says a body was captured, which is how a reader knows it went missing.
        assertNotNull(exported.txn.resBodyRef)
    }

    @Test
    fun `the JSON names its own format although defaults are not encoded`() {
        val dir = session { it.append(txn("aaaa1111"), null, null) }
        val root = json.parseToJsonElement(SessionExport.toJson(build(dir))).jsonObject
        assertEquals(SessionExport.FORMAT, root.getValue("format").jsonPrimitive.content)
        assertEquals("1", root.getValue("formatVersion").jsonPrimitive.content)
        assertEquals(1, root.getValue("transactions").jsonArray.size)
    }

    @Test
    fun `an empty stream is an empty list in the JSON and not a missing key`() {
        val dir = session { it.append(txn("aaaa1111"), null, null) }
        val root = json.parseToJsonElement(SessionExport.toJson(build(dir))).jsonObject
        // A session with no markers is the common case, and every other fixture here has one.
        for (key in listOf("markers", "signals", "current")) {
            assertEquals(0, root[key]?.jsonArray?.size, "$key: ${root[key]}")
        }
        assertEquals(JsonObject(emptyMap()), root["signalData"])
    }

    // --- Markdown ------------------------------------------------------------------------------

    @Test
    fun `calls are numbered sections in time order with both bodies`() {
        val dir = session {
            it.append(txn("bbbb2222", path = "/v2/orders", mono = 200, method = "POST"),
                """{"cart":"c-1"}""".toByteArray(), """{"id":1234567890123456789,"k":1,"k":2}""".toByteArray())
            it.append(txn("aaaa1111", path = "/v2/users/me", mono = 100), null, null)
        }
        val md = ExportMarkdown.render(build(dir))
        val first = md.indexOf("### 1. GET /v2/users/me")
        val second = md.indexOf("### 2. POST /v2/orders")
        assertTrue(first in 0 until second, md)
        assertContains(md, "<a id=\"call-bbbb2222\"></a>")
        assertContains(md, "[#2](#call-bbbb2222)")
        // Re-indented, not re-serialised: every digit, and both copies of the duplicated key.
        assertContains(md, "\"id\": 1234567890123456789")
        assertEquals(2, Regex("\"k\": ").findAll(md).count())
        assertContains(md, "\"cart\": \"c-1\"")
    }

    @Test
    fun `a body containing a fence gets a longer one`() {
        val body = "before\n```\nnot the end\n```\nafter"
        val dir = session { it.append(txn("aaaa1111"), null, body.toByteArray()) }
        val md = ExportMarkdown.render(build(dir))
        assertContains(md, "````\n$body\n````")
    }

    @Test
    fun `redaction is said in the header and on the call`() {
        val dir = session {
            it.append(txn("aaaa1111").copy(redacted = listOf("header:authorization")), null, null)
        }
        val md = ExportMarkdown.render(build(dir))
        assertContains(md, "**Redacted at capture.** 1 of 1 call had values replaced")
        assertContains(md, "- **Redacted at capture:** `header:authorization`")
    }

    @Test
    fun `an absent body says what the row recorded and never guesses`() {
        val dir = session {
            it.append(txn("aaaa1111", resBytes = 2048).copy(resBodyOmitted = BodyOmission.STREAMING), null, null)
            it.append(txn("bbbb2222", mono = 2_000).copy(resBodyOmitted = BodyOmission.DISCARDED), null, null)
            it.append(txn("cccc3333", mono = 3_000), null, null)
        }
        val md = ExportMarkdown.render(build(dir))
        assertContains(md, "it was streamed, so its 2.0 KB were counted and never held")
        assertContains(md, "That does not mean it was empty.")
        assertContains(md, "No body.")
        assertFalse("allowlist" in md, "no row here was refused by content type")
    }

    @Test
    fun `a binary body is pointed at rather than printed`() {
        val dir = session { it.append(txn("aaaa1111", resBytes = 4), null, byteArrayOf(0xFF.toByte(), 1, 2, 3)) }
        val md = ExportMarkdown.render(build(dir))
        assertContains(md, "binary, 4 B — not shown here; the JSON export carries it base64-encoded")
    }

    @Test
    fun `a chatty state holder collapses into one timeline line`() {
        val dir = session { writer ->
            writer.append(txn("aaaa1111", mono = 1), null, null)
            (0 until 5).forEach { writer.append(signal(id = "s$it", tag = SignalTags.STATE, name = "Form", mono = 10L + it), null) }
            writer.append(txn("bbbb2222", mono = 100), null, null)
        }
        val md = ExportMarkdown.render(build(dir))
        assertContains(md, "state/Form ×5 over 4 ms")
    }

    @Test
    fun `a pipe in a path cannot break the timeline table`() {
        val dir = session { it.append(txn("aaaa1111", path = "/a|b"), null, null) }
        assertContains(ExportMarkdown.render(build(dir)), "/a\\|b")
    }

    // --- HTML ----------------------------------------------------------------------------------

    private fun embedded(html: String): JsonObject {
        val start = html.indexOf("window.INSPECTOR_ARCHIVE = ") + "window.INSPECTOR_ARCHIVE = ".length
        val end = html.indexOf(";</script>", start)
        // `<` is a JSON escape, so the embedded text parses as it stands.
        return json.parseToJsonElement(html.substring(start, end)).jsonObject
    }

    @Test
    fun `the file is self-contained and is the real web UI`() {
        val dir = session { it.append(txn("aaaa1111"), null, null) }
        val html = HtmlExport.render(build(dir))
        assertFalse(HtmlExport.SCRIPT_TAG in html, "app.js must be inlined, not linked")
        assertFalse(HtmlExport.STYLE_TAG in html, "style.css must be inlined, not linked")
        assertFalse(Regex("""(src|href)="(https?:)?/""").containsMatchIn(html), "nothing may be fetched")
        assertContains(html, "--m-get")
        assertContains(html, "const ARCHIVE = window.INSPECTOR_ARCHIVE")
        assertContains(html, "<title>2026-08-16T10-14-02_app_dev_debug · Inspector</title>")
        assertEquals("aaaa1111", embedded(html)["transactions"]!!.jsonArray.single()
            .jsonObject["txn"]!!.jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `captured text cannot close the script it is embedded in`() {
        val hostile = "</script><script>alert(1)</script>"
        val dir = session {
            it.append(txn("aaaa1111"), null, hostile.toByteArray())
            it.append(marker("<!-- x -->"))
        }
        val html = HtmlExport.render(build(dir))
        // Exactly the data script's and app.js's own closing tags.
        assertEquals(2, Regex("</script>").findAll(html).count())
        val data = html.substring(html.indexOf("window.INSPECTOR_ARCHIVE"), html.indexOf(";</script>"))
        assertFalse("<" in data, "no raw '<' may reach the embedded data")
        val root = embedded(html)
        assertEquals(hostile, root["transactions"]!!.jsonArray.single().jsonObject["resBody"]!!
            .jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("<!-- x -->", root["markers"]!!.jsonArray.single().jsonObject["label"]!!.jsonPrimitive.content)
    }

    @Test
    fun `it refuses to build a page that would load no code`() {
        val dir = session { }
        val error = assertFailsWith<IllegalStateException> {
            HtmlExport.render(build(dir)) { name ->
                if (name == "index.html") "<html><head>${HtmlExport.STYLE_TAG}</head><body></body></html>" else ""
            }
        }
        assertContains(error.message.orEmpty(), "app.js")
    }

    @Test
    fun `line separators are escaped for the script`() {
        assertEquals("\"a\\u2028b\\u003c\"", HtmlExport.scriptSafe("\"a b<\""))
    }
}

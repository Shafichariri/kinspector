package dev.inspector.daemon

import dev.inspector.daemon.archive.DaemonConfig
import dev.inspector.daemon.http.InspectorDaemon
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
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
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * `GET /api/sessions/{id}/findings` on a running daemon: the reply's shape, the error replies, a
 * session with no findings, and a finding on row 1203, past the transactions route's clamp. That the
 * route answers without the control header is `ReadRoutesOpenTest`'s, by registration.
 */
class FindingsRouteTest {

    private lateinit var tmp: Path
    private lateinit var config: DaemonConfig
    private lateinit var daemon: InspectorDaemon
    private lateinit var http: HttpClient

    @BeforeTest
    fun setUp() = runBlocking {
        tmp = Files.createTempDirectory("inspector-findings-route")
        writeProbeFixture(tmp)
        config = DaemonConfig(port = ServerSocket(0).use { it.localPort }, dataDir = tmp)
        writeFindingsSession(
            config, "payments",
            listOf(
                mutation("b0000001", 1_000, "c-dup-a"),
                mutation("b0000002", 2_200, "c-dup-b"),
                mutation("b0000003", 4_000, "c-retry", path = "/v1/transfers", status = 503),
                mutation("b0000004", 4_300, "c-retry", path = "/v1/transfers", attempt = 2),
            ),
        )
        writeFindingsSession(
            config, "long",
            listOf(mutation("cfffffff", 500, "c-first")) + (0 until 1202).map { getCall("c" + it.toString(16).padStart(7, '0'), 1_000L + it * 10) },
        )
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

    private suspend fun findings(session: String, vararg query: Pair<String, String>): HttpResponse =
        http.get(url("/api/sessions/$session/findings")) { query.forEach { (k, v) -> parameter(k, v) } }

    private suspend fun ok(session: String, vararg query: Pair<String, String>): JsonObject {
        val response = findings(session, *query)
        assertEquals(200, response.status.value, response.bodyAsText())
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    private fun JsonObject.int(key: String) = getValue(key).jsonPrimitive.content.toInt()
    private fun JsonObject.list(key: String) = getValue(key).jsonArray.map { it.jsonObject }

    @Test
    fun `the report has the documented shape`() = runBlocking {
        val report = ok("payments")
        assertEquals(
            listOf("sessionId", "ruleVersion", "total", "findingCount", "dismissedCount", "high", "medium", "info", "rules", "findings", "coverage"),
            report.keys.toList(),
            "absent nulls: no configError, no category",
        )
        assertEquals("payments", report.getValue("sessionId").jsonPrimitive.content)
        assertEquals(listOf(4, 5, 2, 3, 0), listOf("total", "findingCount", "high", "medium", "info").map { report.int(it) })
        assertEquals(
            listOf(
                "payment.duplicate-mutation", "payment.key-reused-body-changed", "payment.retry-without-key",
                "payment.ambiguous-resubmit", "payment.key-missing-payment-path", "payment.retry-with-key",
            ),
            report.list("rules").map { it.getValue("id").jsonPrimitive.content },
        )
        assertEquals(
            listOf("id", "category", "categoryTitle", "title", "summary", "severity", "defaultSeverity", "enabled", "count"),
            report.list("rules").first().keys.toList(),
        )
        val duplicate = report.list("findings").first()
        assertEquals(
            listOf("id", "ruleId", "category", "severity", "route", "location", "message", "txnIds", "callIds", "txnCount", "evidence"),
            duplicate.keys.toList(),
        )
        assertEquals("POST https://$FINDINGS_HOST/v1/payments", duplicate.getValue("route").jsonPrimitive.content)
        assertEquals(listOf("b0000001", "b0000002"), duplicate.getValue("txnIds").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("c-dup-a", "c-dup-b"), duplicate.getValue("callIds").jsonArray.map { it.jsonPrimitive.content })
        val coverage = report.getValue("coverage").jsonObject.getValue("payment").jsonObject
        assertEquals("3 mutation calls looked at; 2 request bodies compared.", coverage.getValue("line").jsonPrimitive.content)
        // `latest` is the probe session here, and the reply names the folder, never `latest`.
        assertEquals(PROBE_SESSION_ID, ok("latest").getValue("sessionId").jsonPrimitive.content)
    }

    @Test
    fun `the probe session has no findings, and every rule says it was checked`() = runBlocking {
        val report = ok(PROBE_SESSION_ID)
        assertEquals(16, report.int("total"))
        assertEquals(0, report.int("findingCount"))
        assertEquals(emptyList(), report.list("findings"))
        assertEquals(6, report.list("rules").size)
        assertEquals(List(6) { 0 }, report.list("rules").map { it.int("count") })
    }

    @Test
    fun `a finding on the oldest of 1203 rows is found`() = runBlocking {
        val report = ok("long")
        assertEquals(1203, report.int("total"))
        assertEquals(listOf("cfffffff"), report.list("findings").single().getValue("txnIds").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `category narrows, limit is clamped, and a bad one is a 400 naming the categories`() = runBlocking {
        assertEquals(6, ok("payments", "category" to "payment").list("rules").size)
        assertEquals("payment", ok("payments", "category" to "payment").getValue("category").jsonPrimitive.content)
        assertEquals(6, ok("payments", "category" to "").list("rules").size)

        val one = ok("payments", "limit" to "1")
        assertEquals(1, one.list("findings").size)
        assertEquals(5, one.int("findingCount"))
        assertEquals(1, ok("payments", "limit" to "0").list("findings").size, "clamped up to 1")
        assertEquals(5, ok("payments", "limit" to "nope").list("findings").size, "the default")

        val bad = findings("payments", "category" to "pay")
        assertEquals(400, bad.status.value)
        assertEquals("""{"error":"unknown category 'pay'. Use one of: payment."}""", bad.bodyAsText())
    }

    @Test
    fun `a dismissal in config json hides a finding on the next read unless includeDismissed, which must be a boolean`() = runBlocking {
        val first = ok("payments").list("findings").first()
        tmp.resolve("config.json").writeText(
            """{"findingDismissals":[{"ruleId":${first.getValue("ruleId")},"route":${first.getValue("route")},""" +
                """"location":${first["location"] ?: "null"},"path":${first["path"] ?: "null"},"reason":"a batch job"}]}""",
        )
        val hidden = ok("payments")
        assertEquals(listOf(4, 1), listOf("findingCount", "dismissedCount").map { hidden.int(it) })
        assertFalse(hidden.list("findings").any { it.getValue("id") == first.getValue("id") })
        val listed = ok("payments", "includeDismissed" to "true").list("findings").first()
        assertEquals(first.getValue("id"), listed.getValue("id"))
        assertEquals("a batch job", listed.getValue("dismissed").jsonObject.getValue("reason").jsonPrimitive.content)
        assertEquals(4, ok("payments", "includeDismissed" to "false").list("findings").size)

        val bad = findings("payments", "includeDismissed" to "yes")
        assertEquals(400, bad.status.value)
        assertEquals("""{"error":"includeDismissed must be true or false"}""", bad.bodyAsText())
    }

    @Test
    fun `an unknown or unsafe session is a 404`() = runBlocking {
        val unknown = findings("nope")
        assertEquals(404, unknown.status.value)
        assertEquals("""{"error":"no session 'nope'"}""", unknown.bodyAsText())
        assertEquals(404, findings("..").status.value)
        assertFalse(findings("nope").bodyAsText().contains("findings"))
    }
}

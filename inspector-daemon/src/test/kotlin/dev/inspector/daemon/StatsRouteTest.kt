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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `GET /api/sessions/{id}/stats` on a running daemon serving the probe fixture: the reply's shape,
 * the error replies, and the long session counted whole, past the 1000-row clamp of the transactions
 * route. That the route answers without the control header is `ReadRoutesOpenTest`'s, by registration.
 */
class StatsRouteTest {

    private lateinit var tmp: Path
    private lateinit var config: DaemonConfig
    private lateinit var daemon: InspectorDaemon
    private lateinit var http: HttpClient

    @BeforeTest
    fun setUp() = runBlocking {
        tmp = Files.createTempDirectory("inspector-stats-route")
        writeProbeFixture(tmp)
        config = DaemonConfig(port = ServerSocket(0).use { it.localPort }, dataDir = tmp)
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

    private suspend fun stats(session: String, vararg query: Pair<String, String>): HttpResponse =
        http.get(url("/api/sessions/$session/stats")) { query.forEach { (k, v) -> parameter(k, v) } }

    private suspend fun ok(session: String, vararg query: Pair<String, String>): JsonObject {
        val response = stats(session, *query)
        assertEquals(200, response.status.value, response.bodyAsText())
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    private fun JsonObject.routes() = getValue("routes").jsonArray.map { it.jsonObject }
    private fun JsonObject.route(route: String) = routes().single { it.getValue("route").jsonPrimitive.content == route }
    private fun JsonObject.int(key: String) = getValue(key).jsonPrimitive.content.toInt()

    @Test
    fun `the probe session's report has the documented shape`() = runBlocking {
        val report = ok(PROBE_SESSION_ID)
        assertEquals(PROBE_SESSION_ID, report.getValue("sessionId").jsonPrimitive.content)
        assertEquals("", report.getValue("filter").jsonPrimitive.content)
        assertEquals("attempts", report.getValue("sort").jsonPrimitive.content)
        assertEquals(true, report.getValue("filterCarried").jsonPrimitive.content.toBoolean())
        assertEquals(listOf(16, 16, 13), listOf(report.int("total"), report.int("matched"), report.int("routeCount")))

        val stock = report.route("/v3/shop/stock")
        assertEquals(
            listOf("method", "scheme", "host", "route", "routeSource", "filterTerm", "calls", "attempts", "retries",
                "redirects", "retried", "count2xx", "count3xx", "count4xx", "count5xx", "transportFailures", "inFlight",
                "outcomes", "rate4xx", "rate5xx", "transportFailureRate", "errorRate", "latencySamples", "p50Ms", "p90Ms",
                "maxMs", "tailWithheld", "meanReqBytes", "meanResBytes"),
            stock.keys.toList(),
        )
        assertEquals("method:GET host:api.example.invalid path:*/v3/shop/stock", stock.getValue("filterTerm").jsonPrimitive.content)
        assertEquals(listOf(2, 4, 2, 0, 1, 3), listOf("calls", "attempts", "retries", "redirects", "count2xx", "count5xx").map { stock.int(it) })
        assertEquals(JsonPrimitive(true), stock.getValue("retried"))
        assertEquals(JsonPrimitive(0.75), stock.getValue("rate5xx"))
        assertEquals(JsonPrimitive("n<20"), stock.getValue("tailWithheld"))
        assertEquals("heuristic", stock.getValue("routeSource").jsonPrimitive.content)
        assertEquals(1, report.route("/v3/shop/products/{id}").int("attempts"))
        val promo = report.route("/v3/shop/promo/current")
        assertEquals(1, promo.int("redirects"))
        assertEquals(JsonPrimitive(false), promo.getValue("retried"))
        // `latest` resolves to the folder, and the reply names the folder.
        assertEquals(report, ok("latest"))
    }

    @Test
    fun `the long session is counted whole, past the transactions route's clamp`() = runBlocking {
        val report = ok(LONG_SESSION_ID)
        assertEquals(LONG_SESSION_CALLS, report.int("matched"))
        val bulk = report.route("/v3/long/bulk")
        assertEquals(LONG_SESSION_CALLS - 2, bulk.int("attempts"))
        assertTrue("p95Ms" in bulk && "tailWithheld" !in bulk, bulk.toString())
    }

    @Test
    fun `limit cuts the rows and not the count`() = runBlocking {
        val report = ok(PROBE_SESSION_ID, "limit" to "1")
        assertEquals(1, report.routes().size)
        assertEquals(13, report.int("routeCount"))
        assertEquals("/v3/shop/stock", report.routes().single().getValue("route").jsonPrimitive.content)
        assertEquals(13, ok(PROBE_SESSION_ID, "limit" to "0").int("routeCount"))
        assertEquals(1, ok(PROBE_SESSION_ID, "limit" to "0").routes().size, "a limit below 1 is 1")
        assertEquals(13, ok(PROBE_SESSION_ID, "limit" to "x").routes().size, "an unreadable limit is the default")
    }

    @Test
    fun `a filter is applied and carried into each term`() = runBlocking {
        val report = ok(PROBE_SESSION_ID, "filter" to "status>=500", "sort" to "p90")
        assertEquals(listOf("/v3/shop/orders/submit", "/v3/shop/stock"), report.routes().map { it.getValue("route").jsonPrimitive.content }.sorted())
        assertEquals(4, report.int("matched"))
        assertTrue(report.routes().all { it.getValue("filterTerm").jsonPrimitive.content.startsWith("status>=500 ") })
        val or = ok(PROBE_SESSION_ID, "filter" to "status:500 | status:503")
        assertFalse(or.getValue("filterCarried").jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `errors are a 404 for a session and a 400 for a filter or a sort`() = runBlocking {
        val missing = stats("x")
        assertEquals(404, missing.status.value)
        assertEquals("""{"error":"no session 'x'"}""", missing.bodyAsText())

        val badFilter = stats(PROBE_SESSION_ID, "filter" to "status>=x")
        assertEquals(400, badFilter.status.value)
        assertEquals("""{"error":"'status' expects a number, got 'x'. Try 'status>=400'."}""", badFilter.bodyAsText())

        val badSort = stats(PROBE_SESSION_ID, "sort" to "p75")
        assertEquals(400, badSort.status.value)
        assertEquals("""{"error":"unknown sort 'p75'. Use one of: attempts, p50, p90, p95, p99, max, errors."}""", badSort.bodyAsText())
    }

    @Test
    fun `a session with no calls is an empty table`() = runBlocking {
        writeSession(config, "2026-02-02T10-00-00_empty_ci_debug", "2026-02-02T10:00:00.000Z")
        val report = ok("2026-02-02T10-00-00_empty_ci_debug")
        assertEquals(0, report.int("total"))
        assertEquals(emptyList(), report.routes())
    }
}

package dev.inspector.daemon

import dev.inspector.daemon.archive.DaemonConfig
import dev.inspector.daemon.http.InspectorDaemon
import dev.inspector.daemon.service.DiffReport
import dev.inspector.daemon.service.DiffRoute
import dev.inspector.model.InspectorJson
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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `GET /api/diff` on a running daemon serving the probe fixture: the base session against the probe
 * session, rule by rule, the default pair, every error, and the long session read whole. That the
 * route answers without the control header is `ReadRoutesOpenTest`'s, by registration.
 */
class DiffRouteTest {

    private lateinit var tmp: Path
    private lateinit var config: DaemonConfig
    private lateinit var daemon: InspectorDaemon
    private lateinit var http: HttpClient

    @BeforeTest
    fun setUp() = runBlocking {
        tmp = Files.createTempDirectory("inspector-diff-route")
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

    private suspend fun get(path: String, vararg query: Pair<String, String>): HttpResponse =
        http.get(url(path)) { query.forEach { (k, v) -> parameter(k, v) } }

    private suspend fun ok(vararg query: Pair<String, String>): Pair<JsonObject, DiffReport> {
        val response = get("/api/diff", *query)
        val text = response.bodyAsText()
        assertEquals(200, response.status.value, text)
        return Json.parseToJsonElement(text).jsonObject to InspectorJson.decodeFromString(DiffReport.serializer(), text)
    }

    private suspend fun error(status: Int, vararg query: Pair<String, String>): String {
        val response = get("/api/diff", *query)
        val text = response.bodyAsText()
        assertEquals(status, response.status.value, text)
        return Json.parseToJsonElement(text).jsonObject.getValue("error").jsonPrimitive.content
    }

    private fun DiffReport.on(route: String, method: String? = null): DiffRoute = routes.on(route, method)

    @Test
    fun `the base session against the probe session shows each rule`() = runBlocking {
        val (json, report) = ok("a" to BASE_SESSION_ID, "b" to PROBE_SESSION_ID)
        assertEquals(
            setOf("a", "b", "aChosen", "sameApp", "filter", "routeCount", "counts", "shapeBudget", "routes"),
            json.keys,
        )
        assertEquals(
            setOf("sessionId", "appId", "appVersion", "device", "startedAt", "endedAt", "total", "matched", "filterCarried", "markers"),
            json.getValue("a").jsonObject.keys,
        )
        assertEquals(listOf("given", "true"), listOf(report.aChosen, report.sameApp.toString()))
        assertEquals(listOf("launch", "tapped checkout"), report.b.markers)
        assertEquals(14, report.routeCount)
        // The feed is counted unsure, not unchanged: A kept no whole body of it and B kept one.
        assertEquals(listOf(7, 1, 4, 1, 1), with(report.counts) { listOf(added, removed, changed, unsure, unchanged) })
        assertEquals(setOf("added", "removed", "changed", "unsure", "unchanged"), json.getValue("counts").jsonObject.keys)

        // Loud rows first, then by their highest kind: the DELETE the probe session added, then the
        // order called 2 → 1, answered 200 → 500, whose keys went and whose error came.
        assertEquals("DELETE" to "/v3/shop/cart/items", report.routes[0].method to report.routes[0].route)
        assertTrue(report.routes[0].loud)
        val orders = report.routes[1]
        assertEquals("/v3/shop/orders/submit", orders.route)
        assertTrue(orders.loud)
        assertEquals(listOf("statusMix", "responseShape", "requestShape", "calls"), orders.changes)
        assertEquals(2 to 1, orders.a!!.calls to orders.b!!.calls)
        assertEquals(mapOf("200" to 2) to mapOf("500" to 1), orders.status.a to orders.status.b)
        assertEquals(listOf("\$.orderId", "\$.status"), orders.responseShape!!.removed.map { it.path })
        assertEquals(listOf("\$.error"), orders.responseShape!!.added.map { it.path })
        assertEquals(listOf("\$.card_number", "\$.cvc"), orders.requestShape!!.added.map { it.path })

        // A route removed.
        assertEquals("removed", report.on("/v3/shop/legacy-banner").presence)
        assertNull(report.on("/v3/shop/legacy-banner").b)

        // The token: a request header added; the redacted response is not compared, so no type change.
        val token = report.on("/oauth/token")
        assertEquals(listOf("x-request-id"), token.requestHeaders!!.added)
        assertEquals(listOf("\$.access_token", "\$.expires_in"), token.responseShape!!.notCompared)
        assertEquals(emptyList(), token.responseShape!!.typeChanged)
        assertEquals(listOf("requestHeaders"), token.changes)

        // The feed: A kept a prefix, so its shape is unknown and nothing is claimed.
        val feed = report.on("/v3/shop/feed").responseShape!!
        assertFalse(feed.compared)
        assertEquals("A kept no complete JSON body here: 1 truncated", feed.why)
        assertTrue(feed.removed.isEmpty() && feed.added.isEmpty() && feed.unsure.isEmpty())
        assertEquals(emptyList(), report.on("/v3/shop/feed").changes)
        assertEquals(true, report.on("/v3/shop/feed").unsure)
        assertEquals(listOf("/v3/shop/feed"), report.routes.filter { it.unsure == true }.map { it.route })

        // The product: price became a string.
        val price = report.on("/v3/shop/products/{id}").responseShape!!.typeChanged.single()
        assertEquals(Triple("\$.price", listOf("number"), listOf("string")), Triple(price.path, price.typesA, price.typesB))

        // The stock: five against four, so latency is not judged; the 503 is new, and so is its body's key.
        val stock = report.on("/v3/shop/stock")
        assertEquals("notJudged" to "n<5", stock.latency!!.verdict to stock.latency!!.reason)
        assertEquals(listOf("statusMix", "responseShape", "calls"), stock.changes)
        assertEquals(listOf("\$.error"), stock.responseShape!!.added.map { it.path })
        assertFalse(stock.loud)

        assertEquals(emptyList(), report.on("/v3/shop/config").changes)
        assertTrue(report.shapeBudget.bodiesRead > 0 && !report.shapeBudget.exhausted)
    }

    @Test
    fun `with no ids the latest run is compared with its previous run`() = runBlocking {
        val (_, report) = ok()
        assertEquals(PROBE_SESSION_ID to LONG_SESSION_ID, report.b.sessionId to report.a.sessionId)
        assertEquals("previousRun", report.aChosen)
        val bulk = report.on("/v3/long/bulk")
        assertEquals("removed", bulk.presence)
        assertEquals(1201 to 1201, bulk.a!!.calls to bulk.a!!.attempts)
    }

    @Test
    fun `each error answers with its status and the service's words`() = runBlocking {
        assertEquals("no session 'nope' (a)", error(404, "a" to "nope"))
        assertEquals("no session 'nope' (b)", error(404, "b" to "nope"))
        assertEquals(
            "no earlier session of app 'com.example.projectx' before '$BASE_SESSION_ID'; name one with a=",
            error(404, "b" to BASE_SESSION_ID),
        )
        Files.createDirectories(config.sessionsDir.resolve("bare"))
        assertEquals(
            "session 'bare' has no metadata, so its previous run cannot be found; name one with a=",
            error(404, "b" to "bare"),
        )
        assertEquals("a and b are the same session '$PROBE_SESSION_ID'", error(400, "a" to "latest"))
        assertTrue(error(400, "filter" to "status>>").isNotBlank())
        assertEquals(
            "session A has no marker 'checkout'. It has none.",
            error(400, "a" to BASE_SESSION_ID, "sinceA" to "checkout"),
        )
        assertEquals(
            "session B has no marker 'nope'. Its markers: launch, tapped checkout.",
            error(400, "a" to BASE_SESSION_ID, "sinceB" to "nope"),
        )
        assertEquals("unchanged is true or false", error(400, "unchanged" to "yes"))

        for (id in listOf(PROBE_SESSION_ID, LONG_SESSION_ID, PAYMENTS_SESSION_ID, BASE_SESSION_ID, "bare")) {
            config.sessionsDir.resolve(id).toFile().deleteRecursively()
        }
        Files.deleteIfExists(config.latestLink)
        assertEquals("no session 'latest' (b)", error(404))
    }

    @Test
    fun `limit and unchanged cut the routes, and routeCount and counts stay whole`() = runBlocking {
        val (_, full) = ok("a" to BASE_SESSION_ID)
        val (_, one) = ok("a" to BASE_SESSION_ID, "limit" to "1")
        assertEquals(listOf(full.routes.first()), one.routes)
        assertEquals(full.routeCount, one.routeCount)
        val (_, changed) = ok("a" to BASE_SESSION_ID, "unchanged" to "false")
        assertEquals(full.routeCount - full.counts.unchanged, changed.routes.size)
        assertEquals(full.counts, changed.counts)
    }

    @Test
    fun `QA's pair with unchanged=false lists the unsure route and counts it, never reading as no change`() = runBlocking {
        writeQaPair(tmp)
        val (json, report) = ok("a" to QA_PAIR_A, "b" to QA_PAIR_B, "unchanged" to "false")
        assertEquals(listOf(0, 1, 1), with(report.counts) { listOf(changed, unsure, unchanged) })
        val route = json.getValue("routes").jsonArray.single().jsonObject
        assertEquals("/v1/product", route.getValue("route").jsonPrimitive.content)
        assertEquals("true", route.getValue("unsure").jsonPrimitive.content)
        assertEquals("removed", report.routes.single().responseShape!!.unsure.single().would)
        val (all, _) = ok("a" to QA_PAIR_A, "b" to QA_PAIR_B)
        val health = all.getValue("routes").jsonArray.last().jsonObject
        assertEquals("/v1/health", health.getValue("route").jsonPrimitive.content)
        assertFalse("unsure" in health.keys, "the flag is written only when true")
    }

    @Test
    fun `diff md is the same report as an attachment, and errors stay JSON`() = runBlocking {
        val response = get("/api/diff.md", "a" to BASE_SESSION_ID)
        assertEquals(200, response.status.value)
        assertEquals("text/markdown; charset=UTF-8", response.headers["Content-Type"])
        assertEquals(
            "attachment; filename=\"diff-$BASE_SESSION_ID-vs-$PROBE_SESSION_ID.md\"",
            response.headers["Content-Disposition"],
        )
        val text = response.bodyAsText()
        assertTrue(text.startsWith("# Session diff: $PROBE_SESSION_ID against $BASE_SESSION_ID\n"))
        assertTrue(text.indexOf("## Changed routes") in 1 until text.indexOf("## Routes added"))

        val missing = get("/api/diff.md", "a" to "nope")
        assertEquals(404, missing.status.value)
        assertEquals("""{"error":"no session 'nope' (a)"}""", missing.bodyAsText())
        assertEquals(400, get("/api/diff.md", "unchanged" to "maybe").status.value)
    }
}

package dev.inspector.daemon

import dev.inspector.daemon.archive.RouteTemplates
import dev.inspector.daemon.service.RouteStatsAccumulator
import dev.inspector.daemon.service.RouteStatsReport
import dev.inspector.daemon.service.RouteStatsRow
import dev.inspector.daemon.service.StatsSort
import dev.inspector.model.FilterContext
import dev.inspector.model.FilterParser
import dev.inspector.model.InspectorJson
import dev.inspector.model.NetworkTransaction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The accumulator alone: what each count means, how a percentile is taken, when the tail is
 * withheld, and the order. Every rule here is one `GET …/stats`, `route_stats` and the exports share.
 */
class RouteStatsTest {

    private fun report(
        rows: List<NetworkTransaction>,
        filter: String = "",
        sort: StatsSort = StatsSort.ATTEMPTS,
        limit: Int = 100,
    ): RouteStatsReport {
        val accumulator = RouteStatsAccumulator(RouteTemplates.NONE, FilterParser.parseOrThrow(filter), FilterContext.EMPTY)
        rows.forEach(accumulator::add)
        return accumulator.snapshot("s", filter, sort, limit)
    }

    private fun RouteStatsReport.row(route: String): RouteStatsRow = routes.single { it.route == route }

    private var n = 0
    private fun row(
        path: String = "/a",
        status: Int? = 200,
        ms: Long? = 100,
        callId: String = "c${n}",
        attempt: Int = 1,
        error: String? = null,
        method: String = "GET",
    ) = txn("%08x".format(n++), path = path, status = status, ms = ms, callId = callId, attempt = attempt, error = error, method = method)

    @Test
    fun `a 503, 503, 200 chain is one call of three attempts with two retries`() {
        val r = report(listOf(row(status = 503, callId = "x"), row(status = 503, callId = "x", attempt = 2), row(callId = "x", attempt = 3))).row("/a")
        assertEquals(1, r.calls)
        assertEquals(3, r.attempts)
        assertEquals(2, r.retries)
        assertEquals(0, r.redirects)
        assertTrue(r.retried)
    }

    @Test
    fun `a hop after a 3xx is a redirect, on whichever route it lands, even under status 200`() {
        val rows = listOf(row(path = "/promo", status = 302, callId = "p"), row(path = "/promo/current", callId = "p", attempt = 2))
        for (filter in listOf("", "status:200")) {
            val target = report(rows, filter).row("/promo/current")
            assertEquals(1, target.redirects, filter)
            assertEquals(0, target.retries, filter)
            assertFalse(target.retried, filter)
            assertEquals(1, target.calls, filter)
        }
        assertEquals(1, report(rows).row("/promo").attempts)
    }

    @Test
    fun `a second attempt with no first one counts as a retry`() {
        assertEquals(1, report(listOf(row(attempt = 2, callId = "lonely"))).row("/a").retries)
    }

    @Test
    fun `rates divide by attempts with an outcome and an in-flight row has none`() {
        val r = report(listOf(row(status = 404), row(status = 500), row(status = null, ms = null, error = "Timeout"), row(status = null, ms = null))).row("/a")
        assertEquals(4, r.attempts)
        assertEquals(1, r.inFlight)
        assertEquals(3, r.outcomes)
        assertEquals(1, r.transportFailures)
        assertEquals(0.3333, r.rate4xx)
        assertEquals(0.3333, r.rate5xx)
        assertEquals(0.3333, r.transportFailureRate)
        assertEquals(1.0, r.errorRate)
    }

    @Test
    fun `a status outside 200 to 599 counts in no class`() {
        val r = report(listOf(row(status = 101), row(status = 600), row(status = 204))).row("/a")
        assertEquals(listOf(1, 0, 0, 0), listOf(r.count2xx, r.count3xx, r.count4xx, r.count5xx))
        assertEquals(3, r.outcomes)
    }

    @Test
    fun `a transport failure is counted and never a latency`() {
        val r = report(listOf(row(ms = 120), row(status = null, ms = 30_000, error = "SocketTimeoutException"))).row("/a")
        assertEquals(1, r.transportFailures)
        assertEquals(0.5, r.errorRate)
        assertEquals(1, r.latencySamples)
        assertEquals(120, r.maxMs)
    }

    @Test
    fun `percentiles are nearest rank`() {
        val r = report((1L..20L).shuffled(kotlin.random.Random(7)).map { row(ms = it) }).row("/a")
        assertEquals(20, r.latencySamples)
        assertEquals(listOf(10L, 18L, 19L, 20L, 20L), listOf(r.p50Ms, r.p90Ms, r.p95Ms, r.p99Ms, r.maxMs))
        assertNull(r.tailWithheld)
    }

    @Test
    fun `below twenty samples the tail is withheld and says so`() {
        val r = report((1L..19L).map { row(ms = it) }).row("/a")
        assertNull(r.p95Ms)
        assertNull(r.p99Ms)
        assertEquals("n<20", r.tailWithheld)
        assertEquals(10L, r.p50Ms)
        assertEquals(19L, r.maxMs)
    }

    @Test
    fun `with no samples every latency is absent`() {
        val r = report(listOf(row(status = null, ms = null, error = "UnknownHost"))).row("/a")
        assertEquals(0, r.latencySamples)
        assertEquals(listOf<Long?>(null, null, null, null, null), listOf(r.p50Ms, r.p90Ms, r.p95Ms, r.p99Ms, r.maxMs))
        assertEquals("n<20", r.tailWithheld)
        assertNull(r.meanResBytes, "no attempt had a status")
        assertEquals(0L, r.meanReqBytes)
    }

    @Test
    fun `mean sizes round half up over their own denominators`() {
        val rows = listOf(
            row().copy(reqBytes = 1, resBytes = 10),
            row().copy(reqBytes = 2, resBytes = 11),
            // Counted for the request mean, not the response one: it never got a response.
            row(status = null, ms = null, error = "Reset").copy(reqBytes = 3, resBytes = 1000),
        )
        val r = report(rows).row("/a")
        assertEquals(2L, r.meanReqBytes)
        assertEquals(11L, r.meanResBytes)
    }

    @Test
    fun `a null is absent from the JSON and a zero is written`() {
        val json = InspectorJson.encodeToString(RouteStatsReport.serializer(), report(listOf(row())))
        val routeRow = Json.parseToJsonElement(json).jsonObject.getValue("routes").jsonArray.single().jsonObject
        assertFalse("p95Ms" in routeRow, json)
        assertFalse("port" in routeRow, json)
        assertEquals("0", routeRow.getValue("count4xx").toString())
        assertEquals("0.0", routeRow.getValue("rate5xx").toString())
        assertEquals("\"n<20\"", routeRow.getValue("tailWithheld").toString())
    }

    @Test
    fun `every sort is descending with nulls last and ties to the busier, then by name`() {
        val rows = buildList {
            repeat(25) { add(row(path = "/slow/x", ms = 1000L + it)) } // 25 samples
            repeat(3) { add(row(path = "/few", ms = 5000)) } // 3 samples: p95 withheld
            repeat(2) { add(row(path = "/b", status = 500, ms = 10)) }
            repeat(2) { add(row(path = "/a", status = 500, ms = 10)) }
            add(row(path = "/dead", status = null, ms = null, error = "Timeout")) // no samples at all
            add(row(path = "/a", status = 500, ms = 10, method = "POST"))
        }
        fun order(sort: StatsSort) = report(rows, sort = sort).routes.map { "${it.method} ${it.route}" }

        assertEquals(listOf("GET /slow/x", "GET /few", "GET /a", "GET /b", "GET /dead", "POST /a"), order(StatsSort.ATTEMPTS))
        assertEquals(listOf("GET /few", "GET /slow/x", "GET /a", "GET /b", "POST /a", "GET /dead"), order(StatsSort.P50))
        assertEquals(listOf("GET /few", "GET /slow/x", "GET /a", "GET /b", "POST /a", "GET /dead"), order(StatsSort.MAX))
        assertEquals(listOf("GET /slow/x", "GET /few", "GET /a", "GET /b", "GET /dead", "POST /a"), order(StatsSort.P95), "withheld sorts last")
        assertEquals(order(StatsSort.P95), order(StatsSort.P99))
        assertEquals(listOf("GET /a", "GET /b", "GET /dead", "POST /a", "GET /slow/x", "GET /few"), order(StatsSort.ERRORS))
        assertEquals(order(StatsSort.P50), order(StatsSort.P90))

        val limited = report(rows, limit = 2)
        assertEquals(2, limited.routes.size)
        assertEquals(6, limited.routeCount)
        assertEquals(34, limited.total)
    }

    @Test
    fun `an unknown sort is refused with the list and a blank one is the default`() {
        assertNull(StatsSort.parse("p75"))
        assertEquals(StatsSort.ATTEMPTS, StatsSort.parse(null))
        assertEquals(StatsSort.ATTEMPTS, StatsSort.parse(""))
        assertEquals("unknown sort 'p75'. Use one of: attempts, p50, p90, p95, p99, max, errors.", StatsSort.unknown("p75"))
    }
}

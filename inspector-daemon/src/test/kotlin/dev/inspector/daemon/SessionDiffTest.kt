package dev.inspector.daemon

import dev.inspector.daemon.service.DiffRoute
import dev.inspector.daemon.service.SessionDiff
import dev.inspector.daemon.service.StatusMix
import dev.inspector.model.InspectorJson
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The diff's route rules, on rows and bodies in memory: presence, calls, status mix, latency, sizes,
 * header names, the order and the caps. The body-shape rules are `DiffShapeTest`'s.
 */
class SessionDiffTest {

    private fun DiffSideRows.times(n: Int, path: String = "/v1/items", ms: Long = 100, status: Int = 200, method: String = "GET") =
        repeat(n) { call(path = path, ms = ms, status = status, method = method) }

    @Test
    fun `a route on one side only is added or removed, and a mutation's presence is loud`() {
        val a = DiffSideRows().apply { call(path = "/v1/old"); call(path = "/v1/both"); call(path = "/v1/gone", method = "DELETE") }
        val b = DiffSideRows().apply { call(path = "/v1/both"); call(path = "/v1/new") }
        val routes = diffInMemory(a, b)
        assertEquals(listOf("removed"), routes.on("/v1/old").changes)
        assertEquals(listOf("added"), routes.on("/v1/new").changes)
        assertEquals(emptyList(), routes.on("/v1/both").changes)
        assertEquals(setOf("removed", "added", "both"), routes.map { it.presence }.toSet())
        val gone = routes.on("/v1/gone")
        assertTrue(gone.loud && gone.mutation)
        assertEquals("/v1/gone", routes.first().route, "a loud row sorts first")
        assertNull(routes.on("/v1/new").a)
        assertNull(routes.on("/v1/new").latency, "a field needing both sides is absent")
        assertEquals(StatusMix(a = null, b = mapOf("200" to 1)), routes.on("/v1/new").status)
    }

    @Test
    fun `calls are distinct callIds, reported on every route and loud only on a mutation`() {
        val a = DiffSideRows().apply { times(1, "/v1/read"); times(1, "/v1/pay", method = "POST") }
        val b = DiffSideRows().apply { times(2, "/v1/read"); times(2, "/v1/pay", method = "POST") }
        val routes = diffInMemory(a, b)
        assertEquals(listOf("calls"), routes.on("/v1/read").changes)
        assertFalse(routes.on("/v1/read").loud)
        assertTrue(routes.on("/v1/pay").loud)
        assertEquals("/v1/pay", routes.first().route)

        // A retry is a second attempt of one call, not a second call.
        val retried = DiffSideRows().apply {
            call(path = "/v1/pay", method = "POST", status = 503, callId = "c-1")
            call(path = "/v1/pay", method = "POST", callId = "c-1", attempt = 2)
        }
        val once = DiffSideRows().apply { call(path = "/v1/pay", method = "POST", callId = "c-9") }
        val route = diffInMemory(once, retried).single()
        assertFalse("calls" in route.changes, route.changes.toString())
        assertFalse(route.loud)
    }

    @Test
    fun `status mix changes on a new code or a transport failure, never on a shift inside one set`() {
        val a = DiffSideRows().apply { times(5, "/v1/shift"); times(1, "/v1/shift", status = 404); times(2, "/v1/new"); times(2, "/v1/net") }
        val b = DiffSideRows().apply {
            times(4, "/v1/shift"); times(2, "/v1/shift", status = 404)
            times(1, "/v1/new"); times(1, "/v1/new", status = 404)
            times(1, "/v1/net"); call(path = "/v1/net", status = null, error = "SocketTimeoutException")
        }
        val routes = diffInMemory(a, b)
        assertFalse("statusMix" in routes.on("/v1/shift").changes)
        assertEquals(StatusMix(mapOf("200" to 5, "404" to 1), mapOf("200" to 4, "404" to 2)), routes.on("/v1/shift").status)
        assertTrue("statusMix" in routes.on("/v1/new").changes)
        assertTrue("statusMix" in routes.on("/v1/net").changes)
        assertEquals(mapOf("200" to 1, "transport" to 1), routes.on("/v1/net").status.b)
    }

    private fun latency(nA: Int, msA: Long, nB: Int, msB: Long) =
        diffInMemory(DiffSideRows().apply { times(nA, ms = msA) }, DiffSideRows().apply { times(nB, ms = msB) }).single()

    @Test
    fun `latency is judged on p50 only with five responses a side, 50 ms and 25 percent`() {
        val four = latency(4, 100, 5, 900).latency!!
        assertEquals("notJudged", four.verdict)
        assertEquals("n<5", four.reason)
        assertEquals("same", latency(5, 100, 5, 149).latency!!.verdict)
        val slower = latency(5, 100, 5, 150)
        assertEquals("slower", slower.latency!!.verdict)
        assertEquals(listOf(50L, 50L), listOf(slower.latency!!.deltaMs, slower.latency!!.deltaPct))
        assertTrue("slower" in slower.changes)
        assertEquals("same", latency(5, 400, 5, 490).latency!!.verdict, "90 ms is 22.5%")
        assertEquals("faster", latency(5, 200, 5, 100).latency!!.verdict)
        val fromZero = latency(5, 0, 5, 60).latency!!
        assertEquals("slower", fromZero.verdict)
        assertNull(fromZero.deltaPct, "no percentage of zero")
    }

    @Test
    fun `sizes change at 1024 bytes and 25 percent, from five samples a side`() {
        assertEquals("grew", SessionDiff.size(4000, 5024, 5, 5).verdict)
        assertEquals("same", SessionDiff.size(4000, 5023, 5, 5).verdict, "1023 bytes")
        assertEquals("grew", SessionDiff.size(8000, 10_000, 5, 5).verdict, "exactly 25%")
        assertEquals("same", SessionDiff.size(8000, 9_999, 5, 5).verdict, "just under 25%")
        assertEquals("shrank", SessionDiff.size(10_000, 7_000, 5, 5).verdict)
        val few = SessionDiff.size(100, 90_000, 4, 5)
        assertEquals(listOf("notJudged", "n<5"), listOf(few.verdict, few.reason))
    }

    @Test
    fun `header names are case-folded, and no header or body value is in the report`() {
        val secret = "planted-header-value-7f3a"
        val bodySecret = "planted-body-value-91c2"
        val a = DiffSideRows().apply { call(reqHeaders = mapOf("X-Trace" to listOf("trace-planted-a")), res = """{"token":"$bodySecret"}""") }
        val b = DiffSideRows().apply {
            call(
                reqHeaders = mapOf("x-trace" to listOf("trace-planted-b"), "X-New" to listOf(secret)),
                resHeaders = mapOf("Set-Cookie" to listOf("sid=$secret")),
                res = """{"token":"$bodySecret","more":"$bodySecret"}""",
            )
        }
        val route = diffInMemory(a, b).single()
        assertEquals(listOf("x-new"), route.requestHeaders!!.added)
        assertEquals(emptyList(), route.requestHeaders!!.removed)
        assertEquals(listOf("set-cookie"), route.responseHeaders!!.added)
        val json = InspectorJson.encodeToString(ListSerializer(DiffRoute.serializer()), listOf(route))
        for (value in listOf(secret, bodySecret, "trace-planted-a", "trace-planted-b")) assertFalse(value in json, "the report holds '$value'")
    }

    @Test
    fun `header lists are capped with a count of what was cut`() {
        val b = DiffSideRows().apply { call(reqHeaders = (1..150).associate { "x-h$it" to listOf("v") }) }
        val headers = diffInMemory(DiffSideRows().apply { call() }, b).single().requestHeaders!!
        assertEquals(SessionDiff.MAX_HEADERS, headers.added.size)
        assertEquals(mapOf("added" to 50), headers.more)
    }

    @Test
    fun `QA's pair is unsure with no change, and the same key gone from bodies B kept whole is removed`() {
        val (a, b) = qaPair()
        val routes = diffInMemory(a, b)
        val product = routes.on("/v1/product")
        assertEquals(emptyList(), product.changes, "6.4: unsure sets no change kind")
        assertEquals(true, product.unsure)
        assertEquals(listOf("\$.price" to "removed"), product.responseShape!!.unsure.map { it.path to it.would })
        assertNull(routes.on("/v1/health").unsure, "a route with nothing unsure carries no flag")

        // B kept both bodies whole: the key is removed, the route changed, and nothing is unsure.
        val known = DiffSideRows().apply { repeat(2) { call(path = "/v1/product", res = """{"id":1}""") } }
        val removed = diffInMemory(a, known).on("/v1/product")
        assertEquals(listOf("responseShape"), removed.changes)
        assertEquals(listOf("\$.price"), removed.responseShape!!.removed.map { it.path })
        assertEquals(emptyList(), removed.responseShape!!.unsure)
        assertNull(removed.unsure)
    }

    @Test
    fun `a route that changed and holds an unsure key is changed and unsure`() {
        // B is all whole, so $.price is removed; A truncated one body, so B's $.next is only unsure.
        val a = DiffSideRows().apply {
            call(res = """{"id":1,"price":9.99}""")
            call(res = """{"id":1,"pri""", resBytes = 900_000, resTruncated = true)
        }
        val b = DiffSideRows().apply { repeat(2) { call(res = """{"id":1,"next":"p2"}""") } }
        val route = diffInMemory(a, b).single()
        assertEquals(listOf("responseShape"), route.changes)
        assertEquals(listOf("\$.price"), route.responseShape!!.removed.map { it.path })
        assertEquals(listOf("\$.next" to "added"), route.responseShape!!.unsure.map { it.path to it.would })
        assertEquals(true, route.unsure)
    }

    @Test
    fun `a direction one side kept whole JSON for and the other no whole body is unsure, and no other uncompared pair`() {
        fun one(a: DiffSideRows.() -> Unit, b: DiffSideRows.() -> Unit) = diffInMemory(DiffSideRows().apply(a), DiffSideRows().apply(b)).single()
        val truncated: DiffSideRows.() -> Unit = { call(res = """{"items":[{"id":1""", resBytes = 300_000, resTruncated = true) }
        val whole: DiffSideRows.() -> Unit = { call(res = """{"items":[{"id":1}]}""") }

        // The feed: A kept a prefix, B a whole body. Either way round.
        for (route in listOf(one(truncated, whole), one(whole, truncated))) {
            assertFalse(route.responseShape!!.compared)
            assertEquals(emptyList(), route.changes)
            assertEquals(true, route.unsure, route.responseShape!!.why)
        }
        // Both unknown, as images omitted by content type are; both empty, as every GET's request is.
        val image: DiffSideRows.() -> Unit = { call(res = "png", resType = "image/png", resOmitted = "content-type") }
        assertNull(one(image, image).unsure)
        assertNull(one({ call() }, { call() }).unsure)
        // Wide JSON on both sides is not compared, but nothing on either side went unkept.
        val deep = "[".repeat(40) + "1" + "]".repeat(40)
        val wide = one({ call(res = deep) }, { call(res = deep) })
        assertFalse(wide.responseShape!!.compared, wide.responseShape.toString())
        assertNull(wide.unsure)
        // Added and removed routes have no shapes to be unsure of.
        val added = diffInMemory(DiffSideRows(), DiffSideRows().apply(truncated)).single()
        assertEquals("added" to null, added.presence to added.unsure)
    }

    @Test
    fun `the order is loud, then the highest kind, then unsure, then unchanged, then method, host, port, route and scheme`() {
        fun row(route: String, changes: List<String>, loud: Boolean = false, method: String = "GET", host: String = "h", port: Int? = null, scheme: String = "https", unsure: Boolean? = null) =
            DiffRoute(
                method = method, scheme = scheme, host = host, port = port, route = route, routeSource = "heuristic",
                presence = "both", mutation = false, loud = loud, changes = changes, unsure = unsure, status = StatusMix(),
            )
        val expected = listOf(
            row("/loud", listOf("calls"), loud = true),
            row("/removed", listOf("removed")),
            row("/added", listOf("added")),
            row("/status", listOf("statusMix", "slower")),
            row("/slower", listOf("slower")),
            row("/res", listOf("responseShape")),
            row("/req", listOf("requestShape")),
            row("/resh", listOf("responseHeaders")),
            row("/reqh", listOf("requestHeaders")),
            row("/ress", listOf("responseSize")),
            row("/reqs", listOf("requestSize")),
            row("/calls", listOf("calls")),
            row("/faster", listOf("faster")),
            row("/faster", listOf("faster"), unsure = true, host = "z"),
            // A route whose only news is unsure: after every change, before every unchanged route.
            row("/unsure", emptyList(), unsure = true),
            row("/unsure", emptyList(), method = "PUT", unsure = true),
            row("/same", emptyList(), method = "DELETE"),
            row("/same", emptyList(), method = "GET", host = "a"),
            row("/same", emptyList(), host = "b"),
            row("/same", emptyList(), host = "b", port = 8080),
            row("/same", emptyList(), host = "b", port = 9090),
            row("/z", emptyList(), host = "b", port = 9090, scheme = "http"),
            row("/z", emptyList(), host = "b", port = 9090, scheme = "https"),
        )
        assertEquals(expected.map { it.toString() }, expected.reversed().sortedWith(SessionDiff.ORDER).map { it.toString() })
    }
}

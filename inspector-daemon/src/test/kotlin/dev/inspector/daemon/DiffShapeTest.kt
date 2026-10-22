package dev.inspector.daemon

import dev.inspector.daemon.service.DiffBudget
import dev.inspector.daemon.service.DiffCaps
import dev.inspector.daemon.service.ShapeDiff
import dev.inspector.model.BodyOmission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Body shape across two sessions: a body capture did not keep whole is unknown, and a key is never
 * reported removed because of one. Each case is one route, `GET /v1/items`, compared A → B.
 */
class DiffShapeTest {

    private fun shape(caps: DiffCaps = DiffCaps(), budget: DiffBudget? = null, a: DiffSideRows.() -> Unit, b: DiffSideRows.() -> Unit): ShapeDiff {
        val left = DiffSideRows().apply(a)
        val right = DiffSideRows().apply(b)
        val route = diffInMemory(left, right, caps, budget ?: DiffBudget(caps.bytesBudget)).single()
        return route.responseShape!!
    }

    private val ShapeDiff.removedPaths get() = removed.map { it.path }
    private val ShapeDiff.addedPaths get() = added.map { it.path }
    private val ShapeDiff.unsurePaths get() = unsure.map { it.path to it.would }

    @Test
    fun `never removed from unknown - a truncated body on the other side makes the key unsure`() {
        val diff = shape(
            a = { call(res = """{"next":"p2","items":[]}""") },
            b = {
                call(res = """{"items":[]}""")
                call(res = """{"items":[]}""")
                // The cut prefix of a large response that may well have held `next`.
                call(res = """{"items":[{"id":1},{"id""", resBytes = 300_000, resTruncated = true)
            },
        )
        assertTrue(diff.compared)
        assertEquals(emptyList(), diff.removedPaths)
        assertEquals(listOf("\$.next" to "removed"), diff.unsurePaths)
        assertEquals(1, diff.b.truncated)
        assertEquals(2, diff.b.complete)
    }

    @Test
    fun `never removed from unknown - omitted, missing and unparsed bodies too`() {
        val unknowns: List<DiffSideRows.() -> Unit> = listOf(
            { call(res = """{"next":"p2"}""", resOmitted = BodyOmission.CONTENT_TYPE) },
            { call(res = """{"next":"p2"}""", resFileGone = true) },
            { call(res = """{"next":"p2",}""") },
        )
        for ((i, unknown) in unknowns.withIndex()) {
            val diff = shape(a = { call(res = """{"next":"p2"}""") }, b = { call(res = "{}"); unknown() })
            assertEquals(emptyList(), diff.removedPaths, "case $i")
            assertEquals(listOf("\$.next" to "removed"), diff.unsurePaths, "case $i")
        }
    }

    @Test
    fun `a key is removed when every body on the other side was read whole, and added needs A known`() {
        val removed = shape(a = { call(res = """{"next":"p2","n":1}""") }, b = { call(res = """{"n":1}"""); call(res = """{"n":2}""") })
        assertEquals(listOf("\$.next"), removed.removedPaths)
        assertEquals(1, removed.removed.single().`in`)
        assertTrue(removed.unsure.isEmpty())

        val added = shape(
            a = { call(res = """{"n":1}"""); call(res = """{"n":1""", resBytes = 900_000, resTruncated = true) },
            b = { call(res = """{"n":1,"extra":true}""") },
        )
        assertEquals(emptyList(), added.addedPaths)
        assertEquals(listOf("\$.extra" to "added"), added.unsurePaths)

        val sure = shape(a = { call(res = """{"n":1}""") }, b = { call(res = """{"n":1,"extra":true}""") })
        assertEquals(listOf("\$.extra"), sure.addedPaths)
    }

    @Test
    fun `a key under arrays that were all empty is not observable, so not removed`() {
        val diff = shape(a = { call(res = """{"items":[{"id":1}]}""") }, b = { call(res = """{"items":[]}""") })
        assertEquals(emptyList(), diff.removedPaths)
        assertTrue(diff.unsure.isEmpty())
    }

    @Test
    fun `only the top-most removed path is listed, and arrays merge their elements`() {
        val diff = shape(
            a = { call(res = """{"order":{"id":"o1","lines":[{"sku":"a"},{"sku":"b","qty":2}]},"n":1}""") },
            b = { call(res = """{"n":1}""") },
        )
        assertEquals(listOf("\$.order"), diff.removedPaths)
    }

    @Test
    fun `a type that changed on both known sides is typeChanged`() {
        val diff = shape(a = { call(res = """{"id":42,"price":9.99}""") }, b = { call(res = """{"id":42,"price":"9.99"}""") })
        assertEquals(listOf(Triple("\$.price", listOf("number"), listOf("string"))), diff.typeChanged.map { Triple(it.path, it.typesA, it.typesB) })
    }

    @Test
    fun `nothing at or under a redacted path is compared, presence included`() {
        val diff = shape(
            a = {
                call(
                    res = """{"access_token":"‹redacted›","expires_in":"‹redacted›","card":"‹redacted›"}""",
                    redacted = listOf("body:res:\$.access_token", "body:res:\$.expires_in", "body:res:\$.card"),
                )
            },
            b = { call(res = """{"access_token":"abc","expires_in":3600,"card":{"number":"x","exp":"y"}}""") },
        )
        assertTrue(diff.compared)
        assertEquals(emptyList(), diff.typeChanged)
        assertEquals(emptyList(), diff.addedPaths)
        assertTrue(diff.unsure.isEmpty())
        assertEquals(
            listOf("\$.access_token", "\$.card", "\$.card.exp", "\$.card.number", "\$.expires_in"),
            diff.notCompared,
        )
        // The placeholder alone, with no redacted[] entry (an older capture), is not compared either.
        val bare = shape(a = { call(res = """{"pin":"‹redacted›"}""") }, b = { call(res = """{"pin":1234}""") })
        assertEquals(emptyList(), bare.typeChanged)
        assertEquals(listOf("\$.pin"), bare.notCompared)
    }

    @Test
    fun `a JSON response that became empty is a change, compared or not`() {
        val a = DiffSideRows().apply { call(res = """{"n":1}""") }
        val b = DiffSideRows().apply { call() }
        val route = diffInMemory(a, b).single()
        assertTrue("responseShape" in route.changes, route.changes.toString())
        assertFalse(route.responseShape!!.compared)
        assertEquals("json" to "empty", route.responseShape!!.a.kind to route.responseShape!!.b.kind)
    }

    @Test
    fun `a side with no complete sample is not compared, and says why in words`() {
        val diff = shape(
            a = { call(res = """{"items":[""", resBytes = 300_000, resTruncated = true) },
            b = { call(res = """{"items":[]}""") },
        )
        assertFalse(diff.compared)
        assertEquals("A kept no complete JSON body here: 1 truncated", diff.why)
        assertEquals("unknown", diff.a.kind)
        assertTrue(diff.removed.isEmpty() && diff.added.isEmpty() && diff.unsure.isEmpty())
    }

    @Test
    fun `strict JSON only - NaN, a leading zero, a bare word, an unquoted key and bad UTF-8 are unparsed`() {
        val bad = listOf("""{"a":NaN}""", """{"a":01}""", """{"a":abc}""", """{a:1}""", """{"a":1,}""")
        for (text in bad) {
            val diff = shape(a = { call(res = """{"a":1}""") }, b = { call(res = text) })
            assertEquals(1, diff.b.unparsed, text)
            assertFalse(diff.compared, text)
        }
        val utf8 = shape(a = { call(res = """{"a":1}""") }, b = { call(resRaw = byteArrayOf('{'.code.toByte(), '"'.code.toByte(), 0xC3.toByte(), '"'.code.toByte(), ':'.code.toByte(), '1'.code.toByte(), '}'.code.toByte()), resType = "application/json") })
        assertEquals(1, utf8.b.unparsed)
        val fine = shape(a = { call(res = """{"a":-1.5e3,"b":true,"c":null}""") }, b = { call(res = """{"a":0,"b":false,"c":null}""", resType = "application/problem+json") })
        assertEquals(listOf(1, 1), listOf(fine.a.complete, fine.b.complete))
    }

    @Test
    fun `a cap only ever makes a claim unsure`() {
        val oneBody = shape(
            caps = DiffCaps(bodiesPerRoute = 1),
            a = { call(res = """{"next":"p2"}""") },
            b = { call(res = "{}"); call(res = """{"next":"p3"}""") },
        )
        assertEquals(1, oneBody.b.unread)
        assertEquals(emptyList(), oneBody.removedPaths)
        assertEquals(listOf("\$.next" to "removed"), oneBody.unsurePaths)

        // A reads its 13 bytes, B its first 2, and the budget refuses B's second.
        val budget = DiffBudget(15)
        val bytes = shape(
            budget = budget,
            a = { call(res = """{"next":"p2"}""") },
            b = { call(res = "{}"); call(res = "{}") },
        )
        assertTrue(budget.exhausted)
        assertEquals(1, bytes.b.unread)
        assertEquals(listOf("\$.next" to "removed"), bytes.unsurePaths)
    }

    @Test
    fun `past the path cap or the depth cap a direction is not compared, and says which`() {
        val paths = shape(caps = DiffCaps(pathsPerRoute = 2), a = { call(res = """{"a":1,"b":2}""") }, b = { call(res = """{"a":1}""") })
        assertFalse(paths.compared)
        assertEquals("A's JSON here holds more distinct key paths than the diff keeps", paths.why)

        val deep = (1..33).fold("1") { inner, _ -> """{"x":$inner}""" }
        val depth = shape(a = { call(res = deep) }, b = { call(res = """{"x":1}""") })
        assertFalse(depth.compared)
        assertEquals("A's JSON here nests deeper than 32 levels, past what the diff walks", depth.why)
    }

    @Test
    fun `shape lists are capped at 200 with a count of what was cut`() {
        val wide = (1..250).joinToString(",", "{", "}") { "\"k$it\":1" }
        val diff = shape(a = { call(res = wide) }, b = { call(res = "{}") })
        assertEquals(200, diff.removed.size)
        assertEquals(mapOf("removed" to 50), diff.more)
    }
}

package dev.inspector.daemon

import dev.inspector.daemon.archive.DaemonConfig
import dev.inspector.daemon.archive.RouteTemplates
import dev.inspector.daemon.archive.SessionLayout
import dev.inspector.daemon.archive.SessionRepository
import dev.inspector.daemon.service.BodyEvidence
import dev.inspector.daemon.service.BodySide
import dev.inspector.daemon.service.BodyState
import dev.inspector.daemon.service.JsonPaths
import dev.inspector.model.BodyOmission
import dev.inspector.model.NetworkTransaction
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.io.path.deleteExisting
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Shared body evidence: the one answer to "is this a mutation" and "how much of this body do we
 * have", which findings, the session diff and sensitive-data detection all read.
 */
class BodyShapeTest {

    private lateinit var tmp: Path
    private lateinit var config: DaemonConfig
    private lateinit var repository: SessionRepository

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("inspector-body-shape")
        config = DaemonConfig(port = 8099, dataDir = tmp)
        Files.createDirectories(config.sessionsDir)
        repository = SessionRepository(config)
    }

    @AfterTest
    fun tearDown() {
        tmp.toFile().deleteRecursively()
    }

    private fun post(
        id: String,
        reqBytes: Long = 12,
        type: String? = "application/json",
        truncated: Boolean = false,
        omitted: String? = null,
        redacted: List<String> = emptyList(),
    ) = txn(id, method = "POST").copy(
        reqBytes = reqBytes,
        reqContentType = type,
        reqBodyTruncated = truncated,
        reqBodyOmitted = omitted,
        redacted = redacted,
    )

    /** Writes [row] with [body] through the real writer, so the ref is what the daemon stores. */
    private fun stored(row: NetworkTransaction, body: ByteArray?): Pair<Path, NetworkTransaction> {
        val dir = writeSession(config, "s-${row.id}", "2026-08-16T10:14:02.311Z", listOf(row), bodies = mapOf(row.id to (body to null)))
        return dir to repository.readTransactions(dir).single()
    }

    @Test
    fun `mutation is POST PUT PATCH and DELETE and nothing else`() {
        val templates = RouteTemplates.NONE
        for (method in listOf("POST", "PUT", "PATCH", "DELETE", "post")) {
            assertTrue(templates.keyOf(txn("a", method = method)).mutation, method)
        }
        for (method in listOf("GET", "HEAD", "OPTIONS", "TRACE", "CONNECT")) {
            assertFalse(templates.keyOf(txn("a", method = method)).mutation, method)
        }
    }

    @Test
    fun `mutation takes no part in a key's equality`() {
        val a = RouteTemplates.NONE.keyOf(txn("a", method = "POST"))
        assertEquals(a, a.copy())
        assertEquals(a.hashCode(), a.copy().hashCode())
    }

    @Test
    fun `a truncated body is truncated and never read, even with its prefix on disk`() {
        val (dir, row) = stored(post("t1", truncated = true), """{"amount":""".toByteArray())
        assertEquals(BodyState.TRUNCATED, BodyEvidence.state(row, BodySide.REQ))
        assertNull(BodyEvidence.read(repository, dir, row, BodySide.REQ))
        // Classified from the row alone: the file is gone and the answer is the same.
        SessionLayout.bodyFile(dir, row.id, "req").deleteExisting()
        assertEquals(BodyState.TRUNCATED, BodyEvidence.state(row, BodySide.REQ))
    }

    @Test
    fun `an omitted body is omitted for each reason`() {
        for (reason in listOf(BodyOmission.CONTENT_TYPE, BodyOmission.STREAMING, BodyOmission.DISCARDED)) {
            val row = post("o1", omitted = reason)
            assertEquals(BodyState.OMITTED, BodyEvidence.state(row, BodySide.REQ), reason)
            assertNull(BodyEvidence.read(repository, tmp, row, BodySide.REQ))
        }
    }

    @Test
    fun `a whole JSON body is complete and read byte for byte`() {
        val bytes = """{"amount":"10.00"}""".toByteArray()
        val (dir, row) = stored(post("c1", reqBytes = bytes.size.toLong()), bytes)
        assertEquals(BodyState.COMPLETE, BodyEvidence.state(row, BodySide.REQ))
        assertContentEquals(bytes, BodyEvidence.read(repository, dir, row, BodySide.REQ))
    }

    @Test
    fun `a body that is not declared JSON is notJson and still read whole`() {
        for (type in listOf("text/plain", null)) {
            val id = if (type == null) "n2" else "n1"
            val (dir, row) = stored(post(id, type = type), "a=1&b=2".toByteArray())
            assertEquals(BodyState.NOT_JSON, BodyEvidence.state(row, BodySide.REQ), type)
            assertContentEquals("a=1&b=2".toByteArray(), BodyEvidence.read(repository, dir, row, BodySide.REQ))
        }
        assertEquals(BodyState.COMPLETE, BodyEvidence.state(post("j1", type = "application/problem+json").copy(reqBodyRef = "bodies/j1.req"), BodySide.REQ))
    }

    @Test
    fun `no body is empty and reads as no bytes`() {
        val row = post("e1", reqBytes = 0)
        assertEquals(BodyState.EMPTY, BodyEvidence.state(row, BodySide.REQ))
        assertContentEquals(ByteArray(0), BodyEvidence.read(repository, tmp, row, BodySide.REQ))
    }

    @Test
    fun `bytes counted with no ref, or a ref whose file is gone, are missing`() {
        assertEquals(BodyState.MISSING, BodyEvidence.state(post("m1"), BodySide.REQ))
        val (dir, row) = stored(post("m2"), "{}".toByteArray())
        SessionLayout.bodyFile(dir, row.id, "req").deleteExisting()
        assertNull(BodyEvidence.read(repository, dir, row, BodySide.REQ))
    }

    @Test
    fun `each side reads its own fields`() {
        val row = post("s1", truncated = true).copy(resBytes = 0)
        assertEquals(BodyState.TRUNCATED, BodyEvidence.state(row, BodySide.REQ))
        assertEquals(BodyState.EMPTY, BodyEvidence.state(row, BodySide.RES))
        assertEquals("req", BodySide.REQ.key)
        assertEquals("res", BodySide.RES.key)
    }

    @Test
    fun `the seven states and their words`() {
        assertEquals(
            listOf("complete", "empty", "truncated", "omitted", "missing", "notJson", "unparsed"),
            BodyState.entries.map { it.key },
        )
    }

    @Test
    fun `redactedPaths reads its own side and the older sideless form as both`() {
        val row = post(
            "r1",
            redacted = listOf("header:authorization", "query:token", "body:res:$.items[3].token", "body:req:$.pin", "body:$.x"),
        )
        assertEquals(setOf("$.pin", "$.x"), JsonPaths.redactedPaths(row, BodySide.REQ))
        assertEquals(setOf("$.items[3].token", "$.x"), JsonPaths.redactedPaths(row, BodySide.RES))
        assertEquals(emptySet(), JsonPaths.redactedPaths(post("r2"), BodySide.REQ))
    }

    @Test
    fun `walk visits every element with its concrete path in the Redactor's syntax`() {
        val json = Json.parseToJsonElement("""{"a":{"b":[{"id":1},{"id":"x","tags":[]}]},"c":null,"d":true}""")
        val seen = ArrayList<Pair<String, String>>()
        val whole = JsonPaths.walk(json) { path, element ->
            seen += path to when (element) {
                is JsonObject -> "object"
                is JsonArray -> "array"
                JsonNull -> "null"
                is JsonPrimitive -> if (element.isString) "string" else element.content.toBooleanStrictOrNull()?.let { "boolean" } ?: "number"
            }
        }
        assertTrue(whole)
        assertEquals(
            listOf(
                "$" to "object", "$.a" to "object", "$.a.b" to "array", "$.a.b[0]" to "object", "$.a.b[0].id" to "number",
                "$.a.b[1]" to "object", "$.a.b[1].id" to "string", "$.a.b[1].tags" to "array", "$.c" to "null", "$.d" to "boolean",
            ),
            seen,
        )
        // The Redactor's own entry for the second id compares equal to the walked path as a string.
        val redacted = JsonPaths.redactedPaths(post("r3", redacted = listOf("body:req:$.a.b[1].id")), BodySide.REQ)
        assertTrue("$.a.b[1].id" in redacted && seen.any { it.first == "$.a.b[1].id" })
    }

    @Test
    fun `walk stops below its depth and says so`() {
        fun nested(levels: Int) = Json.parseToJsonElement("{\"k\":".repeat(levels) + "1" + "}".repeat(levels))
        var deepest = 0
        assertTrue(JsonPaths.walk(nested(32)) { path, _ -> deepest = maxOf(deepest, path.count { it == '.' }) }, "32 levels is whole")
        assertEquals(32, deepest)
        deepest = 0
        assertFalse(JsonPaths.walk(nested(33)) { path, _ -> deepest = maxOf(deepest, path.count { it == '.' }) }, "33 levels is not")
        assertEquals(32, deepest, "the 33rd level is not visited")
        assertTrue(JsonPaths.walk(nested(3), maxDepth = 3) { _, _ -> })
        assertFalse(JsonPaths.walk(nested(3), maxDepth = 2) { _, _ -> })
    }

    @Test
    fun `group writes every index as a star`() {
        assertEquals("$.items[*].id", JsonPaths.group("$.items[12].id"))
        assertEquals("$.a[*][*].b", JsonPaths.group("$.a[0][3].b"))
        assertEquals("$.items[*].id", JsonPaths.group("$.items[*].id"))
        assertEquals("$", JsonPaths.group("$"))
    }

    @Test
    fun `isAtOrUnder holds a path and its children, never a sibling sharing a prefix`() {
        val prefixes = setOf("$.card", "$.items[3].token")
        assertTrue(JsonPaths.isAtOrUnder("$.card", prefixes))
        assertTrue(JsonPaths.isAtOrUnder("$.card.number", prefixes))
        assertTrue(JsonPaths.isAtOrUnder("$.card[0]", prefixes))
        assertTrue(JsonPaths.isAtOrUnder("$.items[3].token.x", prefixes))
        assertFalse(JsonPaths.isAtOrUnder("$.cardholder", prefixes))
        assertFalse(JsonPaths.isAtOrUnder("$.items[3].tokens", prefixes))
        assertFalse(JsonPaths.isAtOrUnder("$.items[4].token", prefixes))
        assertFalse(JsonPaths.isAtOrUnder("$", prefixes))
        assertFalse(JsonPaths.isAtOrUnder("$.card", emptySet()))
    }

    /**
     * The diff's fold: a map keyed by ids is one path, not one per id, by the route heuristic itself.
     * Its misses are the route heuristic's, pinned so nobody "fixes" one here alone: a currency code,
     * an e-mail address or a short slug stays literal.
     */
    @Test
    fun `foldIdKeys reads id-like object keys as the route heuristic does`() {
        assertEquals("$.accounts.{id}.balance", JsonPaths.foldIdKeys("$.accounts.12345.balance"))
        assertEquals("$.byUser.{id}", JsonPaths.foldIdKeys("$.byUser.0b8f3c2e-5d1a-4f6b-9a7c-1e2d3f4a5b6c"))
        assertEquals("$.items[*].{id}[*].{id}", JsonPaths.foldIdKeys("$.items[*].deadbeef42[*].7"))
        assertEquals("$.rates.USD", JsonPaths.foldIdKeys("$.rates.USD"), "a currency code is a documented miss")
        assertEquals("$.byEmail.a@example.invalid", JsonPaths.foldIdKeys("$.byEmail.a@example.invalid"))
        assertEquals("$.id.v2", JsonPaths.foldIdKeys("$.id.v2"), "a short key stays literal")
        assertEquals("$", JsonPaths.foldIdKeys("$"))
        assertEquals("$[*]", JsonPaths.foldIdKeys("$[*]"))
        // Every key the heuristic reads as an id, and no other, over the same segments the route test uses.
        for (key in listOf("42", "abc", "USD", "deadbeefcafe", "a1b2c3d4", "notification-preferences", "")) {
            val expected = if (RouteTemplates.NONE.routeOf("/$key").placeholders.single()) "{id}" else key
            assertEquals("$.m.$expected", JsonPaths.foldIdKeys("$.m.$key"), "key '$key'")
        }
    }
}

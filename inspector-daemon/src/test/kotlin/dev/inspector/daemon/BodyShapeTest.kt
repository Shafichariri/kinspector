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
}

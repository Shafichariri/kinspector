package dev.inspector.daemon

import dev.inspector.daemon.archive.DaemonConfig
import dev.inspector.daemon.archive.SessionRepository
import dev.inspector.daemon.archive.SessionWriter
import dev.inspector.daemon.http.InspectorDaemon
import dev.inspector.stream.StreamSink
import dev.inspector.stream.StreamState
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `POST /api/sessions/delete`: many sessions in one request, answered one by one.
 *
 * Every id goes through `SessionDeleter`, the rule `DELETE /api/sessions/{id}` and `clear` use, so
 * the refusals asserted here are worded exactly as `SessionDeletionTest` reads them on the single
 * route. A batch is where a refusal is easiest to lose: four deletions and one "still being written"
 * reported as one status would read as five deletions.
 */
class SessionBulkDeleteTest {

    private lateinit var tmp: Path
    private lateinit var config: DaemonConfig
    private lateinit var repository: SessionRepository
    private lateinit var daemon: InspectorDaemon
    private lateinit var http: HttpClient
    private var sink: StreamSink? = null

    private val first = "2026-08-15T08-00-00_app_dev_debug"
    private val second = "2026-08-16T10-14-02_app_dev_debug"
    private val third = "2026-08-17T09-01-30_app_dev_debug"

    @BeforeTest
    fun setUp() = runBlocking {
        tmp = Files.createTempDirectory("inspector-bulk-delete")
        config = DaemonConfig(port = ServerSocket(0).use { it.localPort }, dataDir = tmp)
        Files.createDirectories(config.sessionsDir)
        repository = SessionRepository(config)
        writeSession(config, first, "2026-08-15T08:00:00.000Z", transactions = listOf(txn("aaaa0000", mono = 100)))
        writeSession(config, second, "2026-08-16T10:14:02.000Z", transactions = listOf(txn("aaaa1111", mono = 100)))
        writeSession(config, third, "2026-08-17T09:01:30.000Z", transactions = listOf(txn("bbbb2222", mono = 100)))
        SessionWriter.updateLatestLink(config.dataDir, config.sessionsDir.resolve(third))

        daemon = InspectorDaemon(config)
        daemon.start(wait = false)
        http = HttpClient(CIO)
        withTimeoutOrNull(10_000) {
            while (runCatching { http.get(url("/api/sessions")).status.value }.getOrNull() != 200) delay(50)
        }
        Unit
    }

    @AfterTest
    fun tearDown() {
        sink?.stop()
        http.close()
        daemon.stop()
        Files.walk(tmp).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    private fun url(path: String) = "http://127.0.0.1:${config.port}$path"
    private fun dir(id: String) = config.sessionsDir.resolve(id)

    private suspend fun bulk(body: String, type: ContentType = ContentType.Application.Json): HttpResponse =
        http.post(url("/api/sessions/delete")) {
            header("X-Inspector-Control", "1")
            setBody(TextContent(body, type))
        }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun ids(vararg ids: String) = ids.joinToString(",", """{"ids":[""", "]}") { "\"$it\"" }

    /** Each result as `id outcome`, in reply order. */
    private fun JsonObject.outcomes(): List<String> = getValue("results").jsonArray.map {
        val result = it.jsonObject
        "${result.getValue("id").jsonPrimitive.content} ${result.getValue("outcome").jsonPrimitive.content}"
    }

    private fun JsonObject.result(id: String): JsonObject = getValue("results").jsonArray
        .map { it.jsonObject }
        .single { it.getValue("id").jsonPrimitive.content == id }

    private suspend fun attachLiveSession(): String {
        val live = StreamSink(client = clientInfo(), host = "127.0.0.1", port = config.port).also {
            it.start()
            sink = it
        }
        withTimeoutOrNull(10_000) {
            while (live.state.value != StreamState.Connected) delay(20)
        } ?: error("the sink never connected (was ${live.state.value})")
        return withTimeoutOrNull(10_000) {
            var id: String? = null
            while (id == null) {
                id = daemon.liveApps.attachedSessionIds().firstOrNull()
                if (id == null) delay(20)
            }
            id
        } ?: error("no session was ever attached")
    }

    @Test
    fun `named ended sessions go, answered in request order with their bytes summed`() = runBlocking {
        val sizes = listOf(third, first).associateWith { repository.sessionBytes(dir(it)) }

        val response = bulk(ids(third, first))

        assertEquals(200, response.status.value)
        val reply = response.json()
        assertEquals(listOf("$third deleted", "$first deleted"), reply.outcomes())
        assertEquals(listOf(third, first), reply.getValue("deleted").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(1, reply.getValue("remaining").jsonPrimitive.content.toInt())
        assertEquals(sizes.values.sum(), reply.getValue("freedBytes").jsonPrimitive.long)
        assertTrue(sizes.values.all { it > 0 }, "a session with a transaction cannot be zero bytes")
        assertEquals(sizes.getValue(third), reply.result(third).getValue("freedBytes").jsonPrimitive.long)
        assertNull(reply.result(third)["error"], "a deleted session carries no error")
        assertFalse(dir(third).exists())
        assertFalse(dir(first).exists())
        assertTrue(dir(second).isDirectory(), "an unnamed session went too")
    }

    @Test
    fun `the session being written is refused with the single route's words while the others go`() = runBlocking {
        val liveId = attachLiveSession()

        val reply = bulk(ids(first, liveId, second)).json()

        assertEquals(listOf("$first deleted", "$liveId refused", "$second deleted"), reply.outcomes())
        assertEquals(
            "session $liveId is still being written; disconnect the app first",
            reply.result(liveId).getValue("error").jsonPrimitive.content,
        )
        assertTrue(dir(liveId).isDirectory(), "the live session's folder was deleted out from under it")
        assertFalse(dir(first).exists())
        assertFalse(dir(second).exists())
    }

    @Test
    fun `latest is refused per item, and unknown or unsafe ids are not found`() = runBlocking {
        // Something outside sessions/ that `../outside` would reach if resolve let it through.
        val outside = tmp.resolve("outside").also { Files.createDirectories(it) }
        outside.resolve("keep.txt").writeText("not a session")

        val reply = bulk(ids("latest", "no-such-session", "../outside", "..", second)).json()

        assertEquals(
            listOf("latest refused", "no-such-session notFound", "../outside notFound", ".. notFound", "$second deleted"),
            reply.outcomes(),
        )
        assertEquals(
            "refusing to delete through the 'latest' alias — name the session explicitly",
            reply.result("latest").getValue("error").jsonPrimitive.content,
        )
        assertEquals("no session 'no-such-session'", reply.result("no-such-session").getValue("error").jsonPrimitive.content)
        assertTrue(dir(third).isDirectory(), "'latest' resolved and deleted the newest session")
        assertTrue(outside.resolve("keep.txt").exists(), "an id walked out of sessions/ and deleted something")
        assertTrue(config.sessionsDir.isDirectory(), "'..' resolved to the archive itself")
    }

    @Test
    fun `a repeated id is answered once`() = runBlocking {
        val reply = bulk(ids(first, second, first, first)).json()

        assertEquals(listOf("$first deleted", "$second deleted"), reply.outcomes())
    }

    @Test
    fun `a malformed body is a 400 and deletes nothing`() = runBlocking {
        val bodies = listOf(
            "{}",
            """{"ids":"$first"}""",
            """{"ids":[]}""",
            ids(*Array(1001) { first }),
            """{"ids":[1,2]}""",
            """{"ids":["$first",null]}""",
            """{"ids":["$first"],"also":true}""",
            """["$first"]""",
            "not json",
        )
        val wrong = bodies.mapNotNull { body ->
            val status = bulk(body).status.value
            if (status == 400) null else "${body.take(60)} → $status"
        }
        assertEquals(emptyList(), wrong, "expected 400 for each malformed body")
        assertEquals(3, repository.sessionDirs().size, "a malformed request deleted something")
    }

    @Test
    fun `a body not declared as JSON is a 415`() = runBlocking {
        assertEquals(415, bulk(ids(first), ContentType.Text.Plain).status.value)
        val untyped = http.post(url("/api/sessions/delete")) {
            header("X-Inspector-Control", "1")
            setBody(ids(first).toByteArray())
        }
        assertEquals(415, untyped.status.value)
        assertTrue(dir(first).isDirectory())
    }
}

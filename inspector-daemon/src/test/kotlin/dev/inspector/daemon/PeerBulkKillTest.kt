package dev.inspector.daemon

import dev.inspector.daemon.archive.DaemonConfig
import dev.inspector.daemon.http.InspectorDaemon
import dev.inspector.daemon.service.KillOutcome
import dev.inspector.daemon.service.PeerDaemon
import dev.inspector.daemon.service.PeerRegistry
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
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `POST /api/peers/kill`: many pids in one request, each through [PeerRegistry.kill].
 *
 * The fake registry shows the route's own work: order, `force`, duplicates, the reply for each
 * outcome. The real one shows the property a batch must not lose: each pid is re-verified as an
 * inspector process when it is signalled, so a live process that is not one, sent in a list, is
 * refused and keeps running.
 */
class PeerBulkKillTest {

    /** Answers by pid: [SIGNALLED] is signalled, [GONE] has exited, [SELF] is this daemon. */
    private class FakePeers : PeerRegistry {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override fun list(): List<PeerDaemon> = emptyList()
        override fun kill(pid: Long, force: Boolean): KillOutcome {
            calls += "$pid force=$force"
            return when (pid) {
                SIGNALLED -> KillOutcome.Signalled
                GONE -> KillOutcome.NoSuchProcess
                else -> KillOutcome.Refused(SELF_REASON)
            }
        }
    }

    private lateinit var tmp: Path
    private lateinit var config: DaemonConfig
    private var daemon: InspectorDaemon? = null
    private var http: HttpClient? = null
    private var bystander: Process? = null

    private fun start(peers: PeerRegistry?): HttpClient = runBlocking {
        tmp = Files.createTempDirectory("inspector-bulk-kill")
        config = DaemonConfig(port = ServerSocket(0).use { it.localPort }, dataDir = tmp)
        Files.createDirectories(config.sessionsDir)
        daemon = (if (peers == null) InspectorDaemon(config) else InspectorDaemon(config, peers = peers))
            .also { it.start(wait = false) }
        val client = HttpClient(CIO).also { http = it }
        withTimeoutOrNull(10_000) {
            while (runCatching { client.get(url("/api/server")).status.value }.getOrNull() != 200) delay(50)
        }
        client
    }

    @AfterTest
    fun tearDown() {
        bystander?.destroyForcibly()?.waitFor()
        runCatching { http?.close() }
        runCatching { daemon?.stop() }
        runCatching { tmp.toFile().deleteRecursively() }
    }

    private fun url(path: String) = "http://127.0.0.1:${config.port}$path"

    private suspend fun HttpClient.bulk(body: String, type: ContentType = ContentType.Application.Json): HttpResponse =
        post(url("/api/peers/kill")) {
            header("X-Inspector-Control", "1")
            setBody(TextContent(body, type))
        }

    private suspend fun HttpResponse.results(): List<JsonObject> =
        Json.parseToJsonElement(bodyAsText()).jsonObject.getValue("results").jsonArray.map { it.jsonObject }

    private fun JsonObject.line(): String =
        "${getValue("pid").jsonPrimitive.content} ${getValue("outcome").jsonPrimitive.content}"

    @Test
    fun `each pid is answered in request order, force passed through, a repeat answered once`() = runBlocking {
        val peers = FakePeers()
        val client = start(peers)

        val response = client.bulk("""{"pids":[$SELF,$GONE,$SIGNALLED,$GONE],"force":true}""")

        assertEquals(200, response.status.value)
        val results = response.results()
        assertEquals(listOf("$SELF refused", "$GONE noSuchProcess", "$SIGNALLED signalled"), results.map { it.line() })
        assertEquals(listOf("$SELF force=true", "$GONE force=true", "$SIGNALLED force=true"), peers.calls)
        assertEquals(SELF_REASON, results[0].getValue("error").jsonPrimitive.content, "the registry's reason, untouched")
        assertEquals("no live process with pid $GONE", results[1].getValue("error").jsonPrimitive.content)
        assertTrue("error" !in results[2], "a signalled pid carries no error")
    }

    @Test
    fun `force defaults to false`() = runBlocking {
        val peers = FakePeers()
        start(peers).bulk("""{"pids":[$SIGNALLED]}""")

        assertEquals(listOf("$SIGNALLED force=false"), peers.calls)
    }

    @Test
    fun `the single route reads an outcome the same way`() = runBlocking {
        val client = start(FakePeers())
        val gone = client.post(url("/api/peers/$GONE/kill")) { header("X-Inspector-Control", "1") }
        val self = client.post(url("/api/peers/$SELF/kill")) { header("X-Inspector-Control", "1") }

        assertEquals(404, gone.status.value)
        assertContains(gone.bodyAsText(), "no live process with pid $GONE")
        assertEquals(409, self.status.value)
        assertContains(self.bodyAsText(), "that is this daemon")
    }

    @Test
    fun `a malformed body is a 400 and kills nothing`() = runBlocking {
        val peers = FakePeers()
        val client = start(peers)
        val bodies = listOf(
            "{}",
            """{"pids":$SIGNALLED}""",
            """{"pids":[]}""",
            """{"pids":[${(1..101).joinToString(",")}]}""",
            """{"pids":["$SIGNALLED"]}""",
            """{"pids":[1.5]}""",
            """{"pids":[-1]}""",
            """{"pids":[$SIGNALLED],"force":"yes"}""",
            """{"pids":[$SIGNALLED],"forse":true}""",
            "[$SIGNALLED]",
            "not json",
        )
        val wrong = bodies.mapNotNull { body ->
            val status = client.bulk(body).status.value
            if (status == 400) null else "${body.take(60)} → $status"
        }
        assertEquals(emptyList(), wrong, "expected 400 for each malformed body")
        assertEquals(emptyList(), peers.calls, "a malformed request reached the registry")
    }

    @Test
    fun `a body not declared as JSON is a 415`() = runBlocking {
        val peers = FakePeers()
        assertEquals(415, start(peers).bulk("""{"pids":[$SIGNALLED]}""", ContentType.Text.Plain).status.value)
        assertEquals(emptyList(), peers.calls)
    }

    /**
     * The real registry, so the re-verification is the real one: a live `sleep` is a process this
     * user owns and may signal, and only the identity check stands between it and the kill.
     */
    @Test
    fun `a live process that is not an inspector, sent in a list, is refused and left running`() = runBlocking {
        val process = ProcessBuilder("sleep", "60").start().also { bystander = it }
        assertTrue(process.isAlive, "the bystander did not start")
        val self = ProcessHandle.current().pid()
        val client = start(peers = null)

        val results = client.bulk("""{"pids":[${process.pid()},$self]}""").results()

        // The damage first, so a broken check reports what it cost rather than what it said.
        // `destroy` is asynchronous, so give a wrongly delivered signal time to land.
        Thread.sleep(200)
        assertTrue(process.isAlive, "the bulk route signalled a process the registry should have refused")
        assertEquals(listOf("${process.pid()} refused", "$self refused"), results.map { it.line() })
        assertContains(results[0].getValue("error").jsonPrimitive.content, "not an inspector process")
        assertContains(results[1].getValue("error").jsonPrimitive.content, "that is this daemon")
    }

    private companion object {
        const val SIGNALLED = 4101L
        const val GONE = 4102L
        const val SELF = 4103L
        const val SELF_REASON = "that is this daemon — use Stop, which shuts down cleanly after replying"
    }
}

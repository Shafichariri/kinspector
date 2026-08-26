package dev.inspector.daemon

import dev.inspector.stream.StreamSink
import dev.inspector.stream.StreamState
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Removing sessions from the archive.
 *
 * The archive is the only copy — there is no trash — so the guards matter more than the happy
 * path: an alias must not resolve to a delete target, a session being written must survive a
 * clear, and `latest` must not be left pointing at a folder that is gone.
 */
class SessionDeletionTest {

    private lateinit var tmp: Path
    private lateinit var config: DaemonConfig
    private lateinit var repository: SessionRepository
    private lateinit var daemon: InspectorDaemon
    private lateinit var http: HttpClient
    private var sink: StreamSink? = null

    private val older = "2026-08-16T10-14-02_app_dev_debug"
    private val newer = "2026-08-17T09-01-30_app_dev_debug"

    @BeforeTest
    fun setUp() = runBlocking {
        tmp = Files.createTempDirectory("inspector-delete")
        config = DaemonConfig(port = ServerSocket(0).use { it.localPort }, dataDir = tmp)
        Files.createDirectories(config.sessionsDir)
        repository = SessionRepository(config)

        writeSession(
            config, older, "2026-08-16T10:14:02.000Z",
            transactions = listOf(txn("aaaa1111", mono = 100)),
        )
        writeSession(
            config, newer, "2026-08-17T09:01:30.000Z",
            transactions = listOf(txn("bbbb2222", mono = 100)),
        )
        SessionWriter.updateLatestLink(config.dataDir, config.sessionsDir.resolve(newer))

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

    @Test
    fun deleting_a_session_removes_its_folder_and_leaves_the_others_alone() = runBlocking {
        val response = http.delete(url("/api/sessions/$older")) { header("X-Inspector-Control", "1") }

        assertEquals(200, response.status.value)
        assertContains(response.bodyAsText(), older)
        assertContains(response.bodyAsText(), "\"remaining\":1")
        assertFalse(dir(older).exists(), "the deleted session's folder is still on disk")
        assertTrue(dir(newer).isDirectory(), "an unrelated session was deleted too")
    }

    @Test
    fun the_latest_alias_is_refused_rather_than_resolved() = runBlocking {
        val response = http.delete(url("/api/sessions/latest")) { header("X-Inspector-Control", "1") }

        assertEquals(400, response.status.value)
        assertContains(response.bodyAsText(), "latest")
        // The guard is only worth anything if the folder it aliases survived.
        assertTrue(dir(newer).isDirectory(), "'latest' resolved and deleted the newest session")
    }

    @Test
    fun deleting_without_the_control_header_is_refused() = runBlocking {
        val response = http.delete(url("/api/sessions/$older"))

        assertEquals(403, response.status.value)
        assertTrue(dir(older).isDirectory(), "a header-less delete went through anyway")
    }

    @Test
    fun deleting_the_session_that_latest_points_at_repoints_the_link() = runBlocking {
        http.delete(url("/api/sessions/$newer")) { header("X-Inspector-Control", "1") }

        val link = config.latestLink
        assertTrue(link.exists(LinkOption.NOFOLLOW_LINKS), "the link was removed while a session remained")
        assertEquals(dir(older).toRealPath(), link.toRealPath(), "'latest' was left pointing at the deleted session")
        // And the alias still answers, which is the reason the link is repaired at all.
        assertContains(http.get(url("/api/sessions/latest/summary")).bodyAsText(), "\"txnCount\":1")
    }

    @Test
    fun deleting_the_last_session_removes_the_link_rather_than_dangling_it() = runBlocking {
        for (id in listOf(older, newer)) {
            http.delete(url("/api/sessions/$id")) { header("X-Inspector-Control", "1") }
        }

        assertFalse(
            config.latestLink.exists(LinkOption.NOFOLLOW_LINKS),
            "'latest' survived every session it could have pointed at",
        )
    }

    @Test
    fun clearing_removes_every_session_and_reports_what_it_freed() = runBlocking {
        val response = http.post(url("/api/sessions/clear")) { header("X-Inspector-Control", "1") }

        assertEquals(200, response.status.value)
        val body = response.bodyAsText()
        assertContains(body, older)
        assertContains(body, newer)
        assertContains(body, "\"remaining\":0")
        assertFalse(body.contains("\"freedBytes\":0"), "a clear that deleted two sessions freed nothing")
        assertEquals(emptyList(), repository.sessionDirs().map { it.fileName.toString() })
    }

    @Test
    fun clearing_without_the_control_header_is_refused() = runBlocking {
        assertEquals(403, http.post(url("/api/sessions/clear")).status.value)
        assertEquals(2, repository.sessionDirs().size)
    }

    /**
     * The rule that matters most. Deleting the folder underneath an open writer corrupts the
     * append stream and throws away the traffic on screen.
     */
    @Test
    fun a_session_being_written_survives_a_clear_and_is_named_as_kept() = runBlocking {
        val live = StreamSink(client = clientInfo(), host = "127.0.0.1", port = config.port).also {
            it.start()
            sink = it
        }
        withTimeoutOrNull(10_000) {
            while (live.state.value != StreamState.Connected) delay(20)
        } ?: error("the sink never connected (was ${live.state.value})")
        val liveId = withTimeoutOrNull(10_000) {
            var id: String? = null
            while (id == null) {
                id = daemon.liveApps.attachedSessionIds().firstOrNull()
                if (id == null) delay(20)
            }
            id
        } ?: error("no session was ever attached")

        val body = http.post(url("/api/sessions/clear")) { header("X-Inspector-Control", "1") }.bodyAsText()

        assertContains(body, "\"kept\":[\"$liveId\"]")
        assertTrue(dir(liveId).isDirectory(), "the live session's folder was deleted out from under it")
        assertContains(body, older)
        assertContains(body, newer)
    }

    @Test
    fun a_session_being_written_cannot_be_deleted_one_at_a_time_either() = runBlocking {
        val live = StreamSink(client = clientInfo(), host = "127.0.0.1", port = config.port).also {
            it.start()
            sink = it
        }
        val liveId = withTimeoutOrNull(10_000) {
            var id: String? = null
            while (id == null) {
                id = daemon.liveApps.attachedSessionIds().firstOrNull()
                if (id == null) delay(20)
            }
            id
        } ?: error("no session was ever attached")

        val response = http.delete(url("/api/sessions/$liveId")) { header("X-Inspector-Control", "1") }

        assertEquals(409, response.status.value)
        assertContains(response.bodyAsText(), "still being written")
        assertTrue(dir(liveId).isDirectory())
    }
}

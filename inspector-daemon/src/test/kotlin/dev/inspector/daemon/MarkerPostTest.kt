package dev.inspector.daemon

import dev.inspector.model.InspectorJson
import dev.inspector.model.Marker
import dev.inspector.model.MarkerSource
import dev.inspector.stream.StreamSink
import dev.inspector.stream.StreamState
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Posting a marker, and asking which sessions can take one.
 *
 * `POST /api/sessions/{id}/markers` had no test: it was written for the MCP `add_marker` tool and
 * for `HttpMarkerPoster`, both of which pass a label and nothing else. The web UI is the third
 * caller and the first one that is a person, which is what made `source` start mattering.
 */
class MarkerPostTest {

    private lateinit var tmp: Path
    private lateinit var config: DaemonConfig
    private var daemon: InspectorDaemon? = null
    private var sink: StreamSink? = null
    private lateinit var http: HttpClient

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("inspector-markers")
        config = DaemonConfig(port = ServerSocket(0).use { it.localPort }, dataDir = tmp)
        http = HttpClient(CIO) { install(WebSockets) }
        daemon = InspectorDaemon(config).also { it.start(wait = false) }
    }

    @AfterTest
    fun tearDown() {
        sink?.stop()
        http.close()
        daemon?.stop()
        Files.walk(tmp).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    private fun url(path: String) = "http://127.0.0.1:${config.port}$path"

    /** A session with an app attached, which is the only kind that can take a marker. */
    private suspend fun openLiveSession(): StreamSink {
        val opened = StreamSink(clientInfo(), host = "127.0.0.1", port = config.port)
            .also { it.start(); sink = it }
        withTimeoutOrNull(10_000) { while (opened.state.value != StreamState.Connected) delay(20) }
        assertEquals(StreamState.Connected, opened.state.value, "the sink never connected")
        return opened
    }

    private suspend fun postMarker(body: String) =
        http.post(url("/api/sessions/latest/markers")) { setBody(body) }

    private suspend fun recordingNow(): List<String> = InspectorJson.decodeFromString(
        ListSerializer(String.serializer()),
        http.get(url("/api/recording")).bodyAsText(),
    )

    private suspend fun markers(): List<Marker> = InspectorJson.decodeFromString(
        ListSerializer(Marker.serializer()),
        http.get(url("/api/sessions/latest/markers")).bodyAsText(),
    )

    @Test
    fun a_marker_from_a_person_is_recorded_as_theirs() = runBlocking {
        openLiveSession()

        assertEquals(200, postMarker("""{"label":"tapped checkout","source":"user"}""").status.value)

        val recorded = markers().single()
        assertEquals("tapped checkout", recorded.label)
        // `MarkerSource.USER` existed with no caller: every marker in every archive said `app` or
        // `agent`. A marker dropped by the person watching is a different claim from one an agent
        // left while working through the session, and whoever reads the archive later is entitled
        // to tell them apart.
        assertEquals(MarkerSource.USER, recorded.source)
    }

    @Test
    fun a_marker_with_no_source_is_still_an_agent() = runBlocking {
        openLiveSession()

        // Every caller before the web UI omitted the field, and their markers must keep meaning
        // what they meant.
        assertEquals(200, postMarker("""{"label":"from mcp"}""").status.value)
        assertEquals(MarkerSource.AGENT, markers().single().source)
    }

    @Test
    fun an_unrecognised_source_is_refused_rather_than_stored() = runBlocking {
        openLiveSession()

        val response = postMarker("""{"label":"who","source":"robot"}""")
        // Stored, it would render as nothing in both UIs and in the MCP tools — a marker that is
        // in the archive and invisible everywhere that reads it.
        assertEquals(400, response.status.value)
        assertContains(response.bodyAsText(), "source must be one of")
        assertTrue(markers().isEmpty(), "the refused marker must not have been written")
    }

    @Test
    fun a_marker_needs_a_session_something_is_connected_to() = runBlocking {
        // No sink: the session folder exists but nothing is writing to it.
        writeSession(config, "2026-08-16T10-14-02_app_dev_debug", "2026-08-16T10:14:02.000Z")
        SessionWriter.updateLatestLink(
            config.dataDir,
            config.sessionsDir.resolve("2026-08-16T10-14-02_app_dev_debug"),
        )

        val response = postMarker("""{"label":"too late","source":"user"}""")
        assertEquals(409, response.status.value)
        // The message is shown verbatim by the web UI, so it has to say what to do about it.
        assertContains(response.bodyAsText(), "currently recording")
    }

    @Test
    fun the_recording_endpoint_names_the_open_sessions_and_nothing_else() = runBlocking {
        // A finished session on disk, which has no `endedAt` either — this is the case the web
        // UI used to get wrong, offering a marker button that the POST above refuses with a 409.
        writeSession(config, "2026-08-16T09-00-00_app_dev_debug", "2026-08-16T09:00:00.000Z")

        val before = recordingNow()
        assertTrue(before.isEmpty(), "nothing is connected, so nothing is recording: got $before")

        openLiveSession()
        val during = recordingNow()
        assertEquals(1, during.size, "exactly the session the sink opened: got $during")
        // The archived one is on disk throughout and must never appear here.
        assertTrue(during.none { it.startsWith("2026-08-16T09-00-00") })
    }
}

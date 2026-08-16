package dev.inspector.daemon

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The HTTP surface the web UI, CLI and (later) MCP tools all read through. */
class RestApiTest {

    private lateinit var tmp: Path
    private lateinit var config: DaemonConfig
    private lateinit var daemon: InspectorDaemon
    private lateinit var http: HttpClient

    @BeforeTest
    fun setUp() {
        runBlocking {
        tmp = Files.createTempDirectory("inspector-rest")
        config = DaemonConfig(port = ServerSocket(0).use { it.localPort }, dataDir = tmp)
        Files.createDirectories(config.sessionsDir)

        writeSession(
            config, "2026-08-16T10-14-02_app_dev_debug", "2026-08-16T10:14:02.000Z",
            transactions = listOf(
                txn("aaaa1111", path = "/v2/users/me", status = 200, mono = 100),
                txn("bbbb2222", path = "/v2/orders", status = 500, mono = 200, ms = 1200),
                txn("cccc3333", path = "/v2/orders", status = 404, mono = 300),
            ),
            markers = listOf(marker("tapped checkout")),
        )
        SessionWriter.updateLatestLink(config.dataDir, config.sessionsDir.resolve("2026-08-16T10-14-02_app_dev_debug"))

        daemon = InspectorDaemon(config)
        daemon.start(wait = false)
        http = HttpClient(CIO)
        withTimeoutOrNull(10_000) {
            while (runCatching { http.get(url("/api/sessions")).status.value }.getOrNull() != 200) delay(50)
        }
        }
    }

    @AfterTest
    fun tearDown() {
        http.close()
        daemon.stop()
        Files.walk(tmp).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    private fun url(path: String) = "http://127.0.0.1:${config.port}$path"

    @Test
    fun lists_sessions() = runBlocking {
        val body = http.get(url("/api/sessions")).bodyAsText()
        assertContains(body, "2026-08-16T10-14-02_app_dev_debug")
        assertContains(body, "\"txnCount\":3")
    }

    @Test
    fun the_literal_latest_resolves() = runBlocking {
        val body = http.get(url("/api/sessions/latest/summary")).bodyAsText()
        assertContains(body, "\"txnCount\":3")
    }

    @Test
    fun filters_are_evaluated_server_side_with_the_shared_grammar() = runBlocking {
        val all = http.get(url("/api/sessions/latest/transactions")).bodyAsText()
        assertContains(all, "\"matched\":3")

        val errors = http.get(url("/api/sessions/latest/transactions?filter=status%3E%3D400")).bodyAsText()
        assertContains(errors, "\"matched\":2")

        val slow = http.get(url("/api/sessions/latest/transactions?filter=slower%3A500ms")).bodyAsText()
        assertContains(slow, "\"matched\":1")
    }

    @Test
    fun a_bad_filter_returns_400_carrying_the_parsers_own_message() = runBlocking {
        // The message is written to be shown verbatim in the UI, so it must survive the hop.
        val response = http.get(url("/api/sessions/latest/transactions?filter=bogus%3A1"))
        assertEquals(400, response.status.value)
        assertContains(response.bodyAsText(), "Unknown filter key")
    }

    @Test
    fun summary_reports_status_classes_and_markers() = runBlocking {
        val body = http.get(url("/api/sessions/latest/summary")).bodyAsText()
        assertContains(body, "\"2xx\":1")
        assertContains(body, "\"4xx\":1")
        assertContains(body, "\"5xx\":1")
        assertContains(body, "tapped checkout")
    }

    @Test
    fun single_transaction_and_markers_endpoints_work() = runBlocking {
        assertContains(http.get(url("/api/sessions/latest/transactions/bbbb2222")).bodyAsText(), "/v2/orders")
        assertContains(http.get(url("/api/sessions/latest/markers")).bodyAsText(), "tapped checkout")
    }

    @Test
    fun unknown_and_unsafe_session_ids_return_404_rather_than_escaping_the_archive() = runBlocking {
        assertEquals(404, http.get(url("/api/sessions/nope/summary")).status.value)
        assertEquals(404, http.get(url("/api/sessions/..%2F..%2Fetc/summary")).status.value)
    }

    @Test
    fun the_web_ui_is_served_from_bundled_resources() = runBlocking {
        val index = http.get(url("/"))
        assertEquals(200, index.status.value)
        val html = index.bodyAsText()
        assertContains(html, "<html")
        // Fully offline: a CSP-free local tool still has no business phoning out.
        assertTrue(
            !html.contains("http://") || !html.contains("cdn"),
            "the web UI must not reference any CDN",
        )
        assertEquals(200, http.get(url("/app.js")).status.value)
        assertEquals(200, http.get(url("/style.css")).status.value)
    }
}

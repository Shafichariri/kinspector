package dev.inspector.daemon.http

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import io.ktor.client.engine.cio.CIO as ClientCIO

/**
 * The control gate, on a server of its own, so each property is seen on routes built to test it.
 *
 * `ControlHeaderCoverageTest` proves the daemon's eight control routes refuse without the header;
 * it cannot say *why* they do, and every property below is one a plausible gate gets wrong while
 * still refusing those eight. A counter per handler is what tells "refused" from "refused after
 * running": a gate that responded 403 and then let the handler run anyway would pass on status.
 */
class ControlGateTest {

    private val hits = ConcurrentHashMap<String, AtomicInteger>()
    private lateinit var server: EmbeddedServer<*, *>
    private lateinit var http: HttpClient
    private var port = 0

    private fun count(name: String) = hits.getOrPut(name) { AtomicInteger() }.get()

    private suspend fun RoutingContext.hit(name: String) {
        hits.getOrPut(name) { AtomicInteger() }.incrementAndGet()
        call.respondText(name)
    }

    /**
     * Not inside `runBlocking`: a server started from within one becomes part of it, and the
     * `runBlocking` then waits for the server to finish before `setUp` can return.
     */
    @BeforeTest
    fun setUp() {
        port = ServerSocket(0).use { it.localPort }
        server = embeddedServer(CIO, port = port, host = "127.0.0.1") {
            routing {
                // 1-3: one gated route.
                controlGated { post("/g") { hit("g") } }

                // 4: the gate applied twice on one parent lands on one node; both stay gated.
                controlGated { post("/twice/a") { hit("twice-a") } }
                controlGated { post("/twice/b") { hit("twice-b") } }

                // 5: a gated literal beside an open parameter must not fall through to it.
                controlGated { post("/x/clear") { hit("x-clear") } }
                post("/x/{id}") { hit("x-id") }

                // 5b: an open literal beside a gated parameter stays open.
                post("/x2/clear") { hit("x2-clear") }
                controlGated { post("/x2/{id}") { hit("x2-id") } }

                // 6: an open GET beside a gated POST under the same prefix.
                get("/y") { hit("y") }
                controlGated { post("/y/z") { hit("y-z") } }

                // 7: routes nested inside the gate are gated too.
                controlGated { route("/n") { post("/m") { hit("n-m") } } }
            }
        }
        server.start(wait = false)
        http = HttpClient(ClientCIO)
        runBlocking {
            withTimeoutOrNull(10_000) {
                while (runCatching { http.get("http://127.0.0.1:$port/y").status.value }.getOrNull() != 200) delay(50)
            }
        }
        // The readiness probe hit GET /y; case 6 counts from zero.
        hits.clear()
    }

    @AfterTest
    fun tearDown() {
        runCatching { http.close() }
        runCatching { server.stop(gracePeriodMillis = 0, timeoutMillis = 1000) }
    }

    private class Reply(val status: Int, val body: String, val contentType: String?)

    private fun send(method: HttpMethod, path: String, control: String? = null): Reply = runBlocking {
        val response = http.request("http://127.0.0.1:$port$path") {
            this.method = method
            if (control != null) header("X-Inspector-Control", control)
        }
        Reply(response.status.value, response.bodyAsText(), response.headers["Content-Type"])
    }

    private fun assertRefused(reply: Reply, what: String) {
        assertEquals(403, reply.status, "$what: expected the gate's 403, got ${reply.status} ${reply.body}")
        assertTrue("X-Inspector-Control" in reply.body, "$what: a 403 that does not name the header is not the gate: ${reply.body}")
    }

    @Test
    fun `1 no header is refused and the handler never runs`() {
        assertRefused(send(HttpMethod.Post, "/g"), "POST /g with no header")
        assertEquals(0, count("g"))
    }

    @Test
    fun `2 a header with any value but 1 is refused and the handler never runs`() {
        assertRefused(send(HttpMethod.Post, "/g", control = "0"), "POST /g with X-Inspector-Control: 0")
        assertEquals(0, count("g"))
    }

    @Test
    fun `3 the header set to 1 reaches the handler once`() {
        val reply = send(HttpMethod.Post, "/g", control = "1")
        assertEquals(200, reply.status, reply.body)
        assertEquals(1, count("g"))
    }

    @Test
    fun `4 the gate applied twice on one parent installs once and gates both routes`() {
        // Reaching this test at all means installing twice did not throw while the server started.
        assertRefused(send(HttpMethod.Post, "/twice/a"), "POST /twice/a with no header")
        assertRefused(send(HttpMethod.Post, "/twice/b"), "POST /twice/b with no header")
        assertEquals(200, send(HttpMethod.Post, "/twice/a", control = "1").status)
        assertEquals(200, send(HttpMethod.Post, "/twice/b", control = "1").status)
        assertEquals(1, count("twice-a"))
        assertEquals(1, count("twice-b"))
    }

    @Test
    fun `5 a gated literal beside an open parameter is refused rather than falling through`() {
        assertRefused(send(HttpMethod.Post, "/x/clear"), "POST /x/clear with no header")
        assertEquals(0, count("x-clear"))
        assertEquals(0, count("x-id"), "the refused call fell through to the open POST /x/{id}")
        assertEquals(200, send(HttpMethod.Post, "/x/clear", control = "1").status)
        assertEquals(1, count("x-clear"))
        assertEquals(0, count("x-id"))
    }

    @Test
    fun `5b an open literal beside a gated parameter stays open`() {
        assertEquals(200, send(HttpMethod.Post, "/x2/clear").status)
        assertEquals(1, count("x2-clear"))
        assertEquals(0, count("x2-id"))
        assertRefused(send(HttpMethod.Post, "/x2/other"), "POST /x2/other with no header")
        assertEquals(0, count("x2-id"))
    }

    @Test
    fun `6 an open GET beside a gated POST under one prefix answers without the header`() {
        assertEquals(200, send(HttpMethod.Get, "/y").status)
        assertEquals(1, count("y"))
        assertRefused(send(HttpMethod.Post, "/y/z"), "POST /y/z with no header")
        assertEquals(0, count("y-z"))
    }

    @Test
    fun `7 routes nested inside the gate are refused`() {
        assertRefused(send(HttpMethod.Post, "/n/m"), "POST /n/m with no header")
        assertEquals(0, count("n-m"))
    }

    /**
     * The literals are what the daemon answered before the gate existed, measured from a running
     * `inspector serve` with `curl -D -`. The web UI and the MCP tools read this body, so the gate
     * replacing eight checks must not change one byte of it.
     */
    @Test
    fun `8 the refusal body and content type are the ones the daemon gave before the gate`() {
        val reply = send(HttpMethod.Post, "/g")
        assertEquals(403, reply.status)
        assertEquals("""{"error":"control endpoints require the X-Inspector-Control: 1 header"}""", reply.body)
        assertEquals("application/json", reply.contentType)
    }
}

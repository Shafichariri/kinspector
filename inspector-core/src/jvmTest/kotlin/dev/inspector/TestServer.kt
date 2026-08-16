package dev.inspector

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.delay
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger

/**
 * Real HTTP server exercising every capture path, per plan §9.
 *
 * Deliberately a real server rather than `MockEngine`: redirect and retry behaviour is the
 * thing the per-attempt contract hinges on, and a mock would only ever confirm our own fiction
 * about how Ktor sequences them.
 */
class TestServer {

    private lateinit var server: EmbeddedServer<*, *>
    var port: Int = 0
        private set

    /** Per-path attempt counters, so `/flaky` can fail a fixed number of times then succeed. */
    private val attempts = mutableMapOf<String, AtomicInteger>()

    fun url(path: String): String = "http://127.0.0.1:$port$path"

    fun start() {
        port = ServerSocket(0).use { it.localPort }
        server = embeddedServer(CIO, port = port) {
            install(Compression) { gzip() }
            routing {
                get("/json") {
                    call.respondText(
                        """{"id":42,"name":"example","nested":{"ok":true},"list":[1,2,3]}""",
                        ContentType.Application.Json,
                    )
                }

                get("/slow") {
                    val ms = call.request.queryParameters["ms"]?.toLongOrNull() ?: 500
                    delay(ms)
                    call.respondText("""{"sleptMs":$ms}""", ContentType.Application.Json)
                }

                get("/status/{code}") {
                    val code = call.parameters["code"]?.toIntOrNull() ?: 200
                    call.respondText(
                        """{"code":$code}""",
                        ContentType.Application.Json,
                        HttpStatusCode.fromValue(code),
                    )
                }

                get("/redirect") {
                    call.response.header(HttpHeaders.Location, "/json")
                    call.respond(HttpStatusCode.Found)
                }

                // Two redirects before landing, so an attempt chain is unambiguously > 2.
                get("/redirect-twice") {
                    call.response.header(HttpHeaders.Location, "/redirect")
                    call.respond(HttpStatusCode.Found)
                }

                // Fails `failures` times per key, then succeeds. Keyed so parallel tests do not
                // steal each other's attempts.
                get("/flaky") {
                    val key = call.request.queryParameters["key"] ?: "default"
                    val failures = call.request.queryParameters["failures"]?.toIntOrNull() ?: 2
                    val n = attempts.getOrPut(key) { AtomicInteger(0) }.incrementAndGet()
                    if (n <= failures) {
                        call.respondText(
                            """{"attempt":$n,"failed":true}""",
                            ContentType.Application.Json,
                            HttpStatusCode.InternalServerError,
                        )
                    } else {
                        call.respondText(
                            """{"attempt":$n,"failed":false}""",
                            ContentType.Application.Json,
                        )
                    }
                }

                get("/large") {
                    val kb = call.request.queryParameters["kb"]?.toIntOrNull() ?: 1024
                    call.respondTextWriter(ContentType.Text.Plain) {
                        val chunk = "x".repeat(1024)
                        repeat(kb) { write(chunk) }
                    }
                }

                get("/gzip") {
                    call.respondText(
                        """{"compressed":true,"padding":"${"y".repeat(4096)}"}""",
                        ContentType.Application.Json,
                    )
                }

                get("/binary") {
                    val bytes = ByteArray(2048) { (it % 256).toByte() }
                    call.respondBytes(bytes, ContentType.Application.OctetStream)
                }

                // A structured-syntax suffix type. Real APIs serve these and the first version
                // of the allowlist dropped their bodies as if they were binary.
                get("/vendor-json") {
                    call.respondText(
                        """{"data":{"type":"user","id":"1"}}""",
                        ContentType.parse("application/vnd.api+json"),
                    )
                }

                get("/empty") {
                    call.respond(HttpStatusCode.NoContent)
                }

                post("/echo") {
                    val body = call.receiveText()
                    call.respondText(body, ContentType.Application.Json)
                }

                // Proves redaction end to end, in both directions.
                get("/secret") {
                    call.respondText(
                        """{"token":"tok_live_abc123","password":"hunter2","safe":"visible"}""",
                        ContentType.Application.Json,
                    )
                }
            }
        }
        server.start(wait = false)
        waitUntilReady()
    }

    fun stop() {
        if (::server.isInitialized) server.stop(gracePeriodMillis = 0, timeoutMillis = 1000)
    }

    fun resetAttempts() = attempts.clear()

    private fun waitUntilReady() {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            try {
                java.net.Socket("127.0.0.1", port).close()
                return
            } catch (_: Exception) {
                Thread.sleep(25)
            }
        }
        error("test server did not become ready on port $port")
    }
}

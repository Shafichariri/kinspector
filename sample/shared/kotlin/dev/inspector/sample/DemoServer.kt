package dev.inspector.sample

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
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
import java.util.concurrent.atomic.AtomicInteger

/** Embedded demo server so the sample needs no external service. Mirrors the test server. */
object DemoServer {
    const val PORT = 9090
    private val flakyAttempts = AtomicInteger(0)

    fun url(path: String) = "http://127.0.0.1:$PORT$path"

    fun start() {
        embeddedServer(CIO, port = PORT) {
            install(Compression) { gzip() }
            routing {
                get("/json") {
                    call.respondText(
                        """{"id":42,"name":"example","nested":{"ok":true},"list":[1,2,3]}""",
                        ContentType.Application.Json,
                    )
                }
                get("/slow") {
                    delay(call.request.queryParameters["ms"]?.toLongOrNull() ?: 800)
                    call.respondText("""{"slow":true}""", ContentType.Application.Json)
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
                get("/flaky") {
                    val n = flakyAttempts.incrementAndGet()
                    if (n % 3 != 0) {
                        call.respondText(
                            """{"attempt":$n}""",
                            ContentType.Application.Json,
                            HttpStatusCode.InternalServerError,
                        )
                    } else {
                        call.respondText("""{"attempt":$n,"ok":true}""", ContentType.Application.Json)
                    }
                }
                get("/large") {
                    val kb = call.request.queryParameters["kb"]?.toIntOrNull() ?: 2048
                    call.respondTextWriter(ContentType.Text.Plain) {
                        val chunk = "x".repeat(1024)
                        repeat(kb) { write(chunk) }
                    }
                }
                get("/binary") {
                    call.respondBytes(ByteArray(4096) { (it % 256).toByte() }, ContentType.Application.OctetStream)
                }
                post("/echo") {
                    call.respondText(call.receiveText(), ContentType.Application.Json)
                }
                get("/secret") {
                    call.respondText(
                        """{"token":"tok_live_abc123","password":"hunter2","safe":"visible"}""",
                        ContentType.Application.Json,
                    )
                }
            }
        }.start(wait = false)
    }
}

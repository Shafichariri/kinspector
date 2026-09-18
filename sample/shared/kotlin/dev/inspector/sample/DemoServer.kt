package dev.inspector.sample

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.delay
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch

/**
 * Embedded demo server so the sample needs no external service. Mirrors the test server.
 *
 * Shared by all three sample apps — desktop, Android and iOS — which constrains it to APIs that
 * exist on Kotlin/Native too. Hence `kotlin.concurrent.AtomicInt` rather than
 * `java.util.concurrent`, and hence two things that are deliberately *not* here:
 *
 * **No `Compression` plugin and no chunked writer.** `ktor-server-compression` has no native
 * variant and `respondTextWriter` is JVM-only, so keeping either would have meant three copies of
 * this file or an `expect`/`actual` seam per consuming module. Gzipped and chunked *responses* are
 * the more valuable thing to cover and they already are, in `:inspector-core`'s byte-identical
 * body tests across all four platforms — which is stronger evidence than a human clicking a
 * button. `/large` still returns a couple of megabytes; it simply builds the body rather than
 * streaming it.
 */
@OptIn(ExperimentalAtomicApi::class)
object DemoServer {
    const val PORT = 9090
    private val flakyAttempts = AtomicInt(0)

    fun url(path: String) = "http://127.0.0.1:$PORT$path"

    fun start() {
        embeddedServer(CIO, port = PORT) {
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
                    val n = flakyAttempts.incrementAndFetch()
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
                    call.respondText("x".repeat(kb * 1024), ContentType.Text.Plain)
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

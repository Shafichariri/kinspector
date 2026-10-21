package dev.inspector

import dev.inspector.internal.installInspector
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assume

/**
 * A real call to the IPv6 loopback, through both capture paths, gives a `url` that is a URL.
 *
 * OkHttp's `HttpUrl.host` hands an IPv6 literal over without brackets and the interceptor stores it
 * as it comes, so `url` used to read `http://::1:8080/v6`, which no client can parse and whose
 * label cannot say where the address ends. Whether Ktor's `Url.host` keeps the brackets was never
 * checked; this test is where it is, because the two paths must arrive at the same `url` whatever
 * each stores in `host`.
 *
 * A machine with no IPv6 loopback cannot run it. Then the test is **skipped with the reason**, as a
 * JUnit assumption, and never passes: a check that found nothing to check must not read as green.
 */
class Ipv6CaptureTest {

    private var server: EmbeddedServer<*, *>? = null
    private var port = 0
    private val okHttpClients = mutableListOf<OkHttpClient>()

    @BeforeTest
    fun setUp() {
        val loopback = InetAddress.getByName("::1")
        // Bound and released first, on the address itself, so the reason for a skip is the
        // machine's and not the server's: a CIO start failure would otherwise read as a capture bug.
        port = try {
            ServerSocket().use { it.bind(InetSocketAddress(loopback, 0)); it.localPort }
        } catch (e: Exception) {
            Assume.assumeTrue("skipped: no IPv6 loopback on this machine (binding [::1] failed: $e)", false)
            return
        }
        server = embeddedServer(ServerCIO, port = port, host = "::1") {
            routing {
                get("/v6") { call.respondText("""{"family":"inet6"}""", ContentType.Application.Json) }
            }
        }.also { it.start(wait = false) }
        val deadline = System.currentTimeMillis() + 10_000
        while (true) {
            try {
                Socket(loopback, port).close()
                break
            } catch (e: Exception) {
                if (System.currentTimeMillis() > deadline) error("the server on [::1]:$port did not come up: $e")
                Thread.sleep(25)
            }
        }
    }

    @AfterTest
    fun tearDown() {
        for (client in okHttpClients) {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
        okHttpClients.clear()
        server?.stop(gracePeriodMillis = 0, timeoutMillis = 1000)
    }

    @Test
    fun a_ktor_call_to_the_ipv6_loopback_records_a_bracketed_url() = runBlocking {
        val sink = CollectingSink()
        val recorder = Recorder(InspectorConfig()).apply { addSink(sink) }
        HttpClient(ClientCIO) { installInspector(recorder) }.use { client ->
            client.get("http://[::1]:$port/v6").bodyAsText()
        }
        val txn = sink.awaitTransactions(1).single()
        assertEquals("http://[::1]:$port/v6", txn.url, "host as captured: '${txn.host}'")
        assertEquals("[::1]:$port", txn.hostPort)
    }

    @Test
    fun an_okhttp_call_to_the_ipv6_loopback_records_a_bracketed_url() = runBlocking {
        val sink = CollectingSink()
        val recorder = Recorder(InspectorConfig()).apply { addSink(sink) }
        val client = OkHttpClient.Builder()
            .readTimeout(10, TimeUnit.SECONDS)
            .addInterceptor(dev.inspector.internal.InspectorOkHttpInterceptor(recorder))
            .build()
            .also { okHttpClients += it }
        client.newCall(Request.Builder().url("http://[::1]:$port/v6").build()).execute().use { it.body!!.string() }
        val txn = sink.awaitTransactions(1).single()
        assertEquals("http://[::1]:$port/v6", txn.url, "host as captured: '${txn.host}'")
        assertEquals("[::1]:$port", txn.hostPort)
    }
}

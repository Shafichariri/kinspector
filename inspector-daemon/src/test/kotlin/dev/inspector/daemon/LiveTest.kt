package dev.inspector.daemon

import dev.inspector.model.InspectorJson
import dev.inspector.model.NetworkTransaction
import dev.inspector.model.Signal
import dev.inspector.stream.StreamSink
import dev.inspector.stream.StreamState
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.jsonObject
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * What a viewer watching `WS /api/live` actually receives.
 *
 * This exists because the live path and the REST path were not equivalent: the REST path served
 * rows read back from `index.jsonl` (refs present), while the live path broadcast the row as it
 * arrived off the wire, where `*BodyRef` is null by protocol contract. A live viewer therefore
 * saw every body as missing while the bytes sat on disk. Nothing tested the live payload, so
 * nothing caught it.
 */
class LiveTest {

    private lateinit var tmp: Path
    private lateinit var config: DaemonConfig
    private var daemon: InspectorDaemon? = null
    private var sink: StreamSink? = null
    private lateinit var http: HttpClient

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("inspector-live")
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

    /** Collects the first live `txn` event, or fails. */
    private suspend fun firstLiveTransaction(
        emit: suspend () -> Unit,
    ): NetworkTransaction = coroutineScope {
        val received = CompletableDeferred<NetworkTransaction>()

        val watcher = launch {
            http.webSocket(host = "127.0.0.1", port = config.port, path = "/api/live") {
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    val root = InspectorJson.parseToJsonElement(frame.readText()).jsonObject
                    val type = root["type"]?.toString()?.trim('"')
                    if (type != "txn") continue
                    received.complete(
                        InspectorJson.decodeFromJsonElement(
                            NetworkTransaction.serializer(),
                            root.getValue("txn"),
                        )
                    )
                    return@webSocket
                }
            }
        }

        // Give the socket time to subscribe; the live flow has no replay, so emitting too early
        // would simply lose the event and make this test flaky rather than red.
        delay(400)
        emit()

        val result = withTimeoutOrNull(10_000) { received.await() }
        watcher.cancel()
        assertNotNull(result, "no live txn event arrived within 10s")
    }

    /** Collects the first live `signal` event, or fails. */
    private suspend fun firstLiveSignal(
        emit: suspend () -> Unit,
    ): Signal = coroutineScope {
        val received = CompletableDeferred<Signal>()

        val watcher = launch {
            http.webSocket(host = "127.0.0.1", port = config.port, path = "/api/live") {
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    val root = InspectorJson.parseToJsonElement(frame.readText()).jsonObject
                    if (root["type"]?.toString()?.trim('"') != "signal") continue
                    received.complete(
                        InspectorJson.decodeFromJsonElement(
                            Signal.serializer(),
                            root.getValue("signal"),
                        )
                    )
                    return@webSocket
                }
            }
        }

        delay(400)
        emit()

        val result = withTimeoutOrNull(10_000) { received.await() }
        watcher.cancel()
        assertNotNull(result, "no live signal event arrived within 10s")
    }

    @Test
    fun a_live_signal_carries_the_data_ref_the_viewer_needs_to_fetch_the_payload() = runBlocking {
        // Defect #1, reproduced in the signal path: the wire contract sends `dataRef` as null, so
        // a viewer handed the incoming row sees every payload as absent while it sits on disk.
        // A passing REST test proves nothing here, which is why this lives in LiveTest.
        val sink = StreamSink(clientInfo(), host = "127.0.0.1", port = config.port)
            .also { it.start(); this@LiveTest.sink = it }
        withTimeoutOrNull(10_000) { while (sink.state.value != StreamState.Connected) delay(20) }

        val payload = """{"route":"Checkout","items":3}"""
        val live = firstLiveSignal {
            sink.onSignal(signal(id = "cccc3333", bytes = payload.length.toLong()), payload.toByteArray())
        }

        assertEquals("signals/cccc3333.json", live.dataRef)
        assertEquals(null, live.data, "the payload travels beside the row, never on it")

        // And the ref must actually resolve through the same API the viewer calls.
        val repo = SessionRepository(config)
        val dir = repo.sessionDirs().single()
        assertEquals(payload, repo.readSignalPayload(dir, "cccc3333")!!.decodeToString())
    }

    @Test
    fun a_payload_free_signal_reports_no_ref_rather_than_a_dangling_one() = runBlocking {
        val sink = StreamSink(clientInfo(), host = "127.0.0.1", port = config.port)
            .also { it.start(); this@LiveTest.sink = it }
        withTimeoutOrNull(10_000) { while (sink.state.value != StreamState.Connected) delay(20) }

        val live = firstLiveSignal { sink.onSignal(signal(id = "dddd4444"), null) }

        assertEquals(null, live.dataRef)
    }

    @Test
    fun a_live_transaction_carries_the_body_refs_the_viewer_needs_to_fetch_bodies() = runBlocking {
        val sink = StreamSink(clientInfo(), host = "127.0.0.1", port = config.port)
            .also { it.start(); this@LiveTest.sink = it }
        withTimeoutOrNull(10_000) { while (sink.state.value != StreamState.Connected) delay(20) }

        val live = firstLiveTransaction {
            sink.onTransaction(
                txn("aaaa1111", method = "PUT", path = "/v3/profile"),
                """{"language":"ar"}""".toByteArray(),
                """{"meta":{"ok":true}}""".toByteArray(),
            )
        }

        // The exact failure the user hit: bodies on disk, viewer told they were never captured.
        assertEquals("bodies/aaaa1111.req", live.reqBodyRef)
        assertEquals("bodies/aaaa1111.res", live.resBodyRef)

        // And the refs must actually resolve through the same API the viewer calls.
        val repo = SessionRepository(config)
        val dir = repo.sessionDirs().single()
        assertEquals("""{"language":"ar"}""", repo.readBody(dir, "aaaa1111", "req")!!.decodeToString())
    }

    @Test
    fun a_body_free_transaction_reports_no_refs_rather_than_dangling_ones() = runBlocking {
        val sink = StreamSink(clientInfo(), host = "127.0.0.1", port = config.port)
            .also { it.start(); this@LiveTest.sink = it }
        withTimeoutOrNull(10_000) { while (sink.state.value != StreamState.Connected) delay(20) }

        val live = firstLiveTransaction { sink.onTransaction(txn("bbbb2222"), null, null) }

        assertEquals(null, live.reqBodyRef)
        assertEquals(null, live.resBodyRef)
    }
}

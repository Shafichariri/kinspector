package dev.inspector

import dev.inspector.internal.installInspector
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.runBlocking
import kotlinx.io.readByteArray
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the interaction between capture and `HttpRequestRetry`.
 *
 * `HttpRequestRetry` sits outside Inspector's `Send` hook and probes the response it gets back with
 * `throwOnInvalidResponseBody`, which cancels `rawContent` in a `finally`. Ktor's saved response
 * hands out a throwaway reader per access, so that cancel is free. Inspector used to hand back a
 * single teed channel from `replaceResponse`, so the same cancel destroyed the one channel the
 * caller had left to read, and the call died with `ClosedByteChannelException`.
 *
 * It was load-dependent — the cancel only did damage when it beat the tee to the bytes — which is
 * why it surfaced as an intermittent CI failure in the burst test rather than anywhere obvious.
 * These tests remove the coin flip by using a body far too large for the tee to have forwarded in
 * the window between the hook returning and the retry plugin probing.
 */
class SavedBodyTest {

    private lateinit var server: TestServer

    /** Large enough that the old tee could not possibly have drained it before the probe. */
    private val bodyKb = 2048
    private val expectedBytes = bodyKb * 1024

    @BeforeTest
    fun setUp() {
        server = TestServer()
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.stop()
    }

    private fun clientWith(sink: CollectingSink, block: HttpClientConfig<*>.() -> Unit = {}): HttpClient {
        val recorder = Recorder(InspectorConfig(captureAllBodies = true))
        recorder.addSink(sink)
        return HttpClient(CIO) {
            // The plugin that does the cancelling. Its presence is the whole point of the test.
            install(HttpRequestRetry) {
                retryOnServerErrors(maxRetries = 3)
                constantDelay(millis = 1, randomizationMs = 0)
            }
            block()
            installInspector(recorder)
        }
    }

    @Test
    fun a_large_saved_body_survives_the_retry_plugins_cancel() = runBlocking {
        val sink = CollectingSink()
        val client = clientWith(sink)

        val body = client.get(server.url("/large?kb=$bodyKb")).bodyAsBytes()
        assertEquals(expectedBytes, body.size, "caller received a short body")
        assertTrue(body.all { it == 'x'.code.toByte() }, "caller received corrupted bytes")

        val txn = sink.awaitTransactions(1).single()
        assertEquals(200, txn.status)
        assertEquals(
            expectedBytes.toLong(),
            txn.resBytes,
            "capture undercounted a body it does not own",
        )
        assertNull(txn.resBodyOmitted, "a fully readable saved body must not be reported as omitted")

        client.close()
    }

    /**
     * The caller must be able to read a saved body more than once.
     *
     * This one does *not* discriminate the bug — it passed against the broken code too, because
     * `HttpStatement.fetchResponse` re-saves the body afterwards and papers over a single-use
     * channel on small responses. It is kept as a plain contract guard: replayability is part of
     * what a consuming app is entitled to, and nothing else asserts it.
     */
    @Test
    fun a_saved_body_is_still_replayable_after_capture() = runBlocking {
        val sink = CollectingSink()
        val client = clientWith(sink)

        val response = client.get(server.url("/json"))
        val first = response.bodyAsText()
        val second = response.bodyAsText()
        assertTrue(first.isNotEmpty(), "saved response read back empty")
        assertEquals(first, second, "saved response stopped being replayable")

        sink.awaitTransactions(1)
        client.close()
    }

    /**
     * Streaming responses still go through the tee, because there is nothing buffered to re-read.
     *
     * `isSaved` is false for these, which is exactly the condition that short-circuits the retry
     * plugin's cancel — so the tee is safe here, and it remains the only way to capture a stream
     * without buffering it, which the efficiency contract forbids.
     */
    @Test
    fun a_streaming_body_is_still_teed_and_still_recorded() = runBlocking {
        val sink = CollectingSink()
        val client = clientWith(sink)
        val streamedKb = 64

        client.prepareGet(server.url("/large?kb=$streamedKb")).execute { response ->
            val bytes = response.bodyAsChannel().readRemaining().readByteArray()
            assertEquals(streamedKb * 1024, bytes.size, "the tee altered a streamed body")
            assertTrue(bytes.all { it == 'x'.code.toByte() }, "the tee corrupted a streamed body")
        }

        val txn = sink.awaitTransactions(1).single()
        assertEquals((streamedKb * 1024).toLong(), txn.resBytes)
        assertNull(txn.resBodyOmitted)

        client.close()
    }
}

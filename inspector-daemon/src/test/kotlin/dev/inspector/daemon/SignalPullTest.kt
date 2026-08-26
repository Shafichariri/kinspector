package dev.inspector.daemon

import dev.inspector.model.SignalError
import dev.inspector.model.SignalRequest
import dev.inspector.model.SignalTags
import dev.inspector.model.SignalTrigger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The host asking a running app what it holds *now*.
 *
 * A push records what was true at a moment; a pull answers the live question. The two are told
 * apart in the archive by `trigger`, and every failure here must be reported rather than left to
 * a timeout — an app that cannot answer looks exactly like an app that has gone away.
 */
class SignalPullTest {

    private val sessionId = "2026-08-16T10-14-02_app_dev_debug"

    /** A live app that answers pulls the way a device would, via the same frames. */
    private fun CoroutineScope.attachApp(
        liveApps: LiveApps,
        answer: (SignalRequest) -> Any,
    ): LiveApps.Connection = liveApps.register(sessionId) { outbound ->
        if (outbound is SignalRequest) {
            launch {
                when (val reply = answer(outbound)) {
                    is SignalError -> liveApps.failSignal(sessionId, reply)
                    else -> liveApps.completeSignal(
                        sessionId,
                        signal(
                            id = "pull0001",
                            tag = outbound.tag,
                            name = outbound.name,
                            trigger = SignalTrigger.Request,
                            requestId = outbound.requestId,
                        ),
                    )
                }
            }
        }
    }

    @Test
    fun a_pull_returns_a_row_marked_as_requested_and_correlated() = runBlocking {
        val liveApps = LiveApps()
        val connection = attachApp(liveApps) { "ok" }

        val signal = connection.requestSignal(SignalTags.CACHE, "response")

        assertEquals(SignalTrigger.Request, signal.trigger, "provenance must survive the round trip")
        assertEquals(SignalTags.CACHE, signal.tag)
        assertEquals("response", signal.name)
        assertTrue(!signal.requestId.isNullOrBlank(), "the reply must carry its correlation id")
    }

    @Test
    fun an_unregistered_name_reports_what_is_registered() {
        // Discovery is the error path in v1, which is only acceptable if the error is useful.
        val liveApps = LiveApps()
        runBlocking {
            val connection = attachApp(liveApps) { request ->
                SignalError(
                    requestId = request.requestId,
                    error = "no provider for ${request.tag}/${request.name}; " +
                        "registered: cache/response, cache/prefs",
                )
            }

            val failure = assertFailsWith<SignalProviderException> {
                connection.requestSignal(SignalTags.CACHE, "orders")
            }
            assertContains(failure.message!!, "no provider for cache/orders")
            assertContains(failure.message!!, "cache/response")
        }
    }

    @Test
    fun a_provider_that_throws_surfaces_rather_than_hanging_the_deferred() = runBlocking {
        val liveApps = LiveApps()
        val connection = attachApp(liveApps) { request ->
            SignalError(requestId = request.requestId, error = "IllegalStateException: cache closed")
        }

        val failure = assertFailsWith<SignalProviderException> {
            connection.requestSignal(SignalTags.CACHE, "response")
        }
        assertContains(failure.message!!, "cache closed")
    }

    @Test
    fun an_app_that_never_answers_times_out_cleanly() = runBlocking {
        // Killing the app mid-pull must not hang the daemon's request thread.
        val liveApps = LiveApps()
        val connection = liveApps.register(sessionId) { _ -> /* swallow: the app is gone */ }

        val failure = assertFailsWith<SignalProviderException> {
            connection.requestSignal(SignalTags.CACHE, "response", timeoutMs = 150)
        }
        assertContains(failure.message!!, "did not answer")
        assertContains(failure.message!!, "cache/response")
    }

    @Test
    fun a_reply_to_a_timed_out_request_is_dropped_rather_than_mismatched() = runBlocking {
        // A late reply must never be handed to whoever asks next.
        val liveApps = LiveApps()
        val connection = liveApps.register(sessionId) { _ -> }

        assertFailsWith<SignalProviderException> {
            connection.requestSignal(SignalTags.CACHE, "response", timeoutMs = 100)
        }

        liveApps.completeSignal(
            sessionId,
            signal(id = "late0001", trigger = SignalTrigger.Request, requestId = "stale-id"),
        )

        val failure = assertFailsWith<SignalProviderException> {
            connection.requestSignal(SignalTags.CACHE, "response", timeoutMs = 100)
        }
        assertContains(
            failure.message!!,
            "did not answer",
            message = "a stale reply must not satisfy a new pull",
        )
    }

    @Test
    fun an_ordinary_pushed_row_never_completes_a_pull() = runBlocking {
        // completeSignal sees every archived row; only one carrying a requestId is a reply.
        val liveApps = LiveApps()
        val connection = liveApps.register(sessionId) { _ -> }

        liveApps.completeSignal(sessionId, signal(id = "push0001"))

        val failure = assertFailsWith<SignalProviderException> {
            connection.requestSignal(SignalTags.SCREEN, "Route", timeoutMs = 150)
        }
        assertContains(failure.message!!, "did not answer")
    }
}

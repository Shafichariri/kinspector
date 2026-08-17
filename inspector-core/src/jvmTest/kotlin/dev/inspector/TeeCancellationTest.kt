package dev.inspector

import dev.inspector.internal.CapturedBody
import dev.inspector.internal.teeBody
import dev.inspector.model.BodyOmission
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.close
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A row must survive the call scope being cancelled underneath it.
 *
 * Ktor discards intermediate responses — every redirect hop, every retried attempt — by cancelling
 * the response's coroutine scope, and `teeBody` launches into exactly that scope. A hop whose tee
 * coroutine has not been dispatched by the time the cancellation lands therefore never runs its
 * `finally`, never calls `onComplete`, and produces no row at all.
 *
 * That is silent data loss in the feature the tool leads with — "redirects and retries each get a
 * row" — and it is load-dependent, which is why it appeared only on a small CI runner while passing
 * on a 12-core laptop.
 */
class TeeCancellationTest {

    @Test
    fun a_body_teed_into_a_scope_cancelled_before_dispatch_still_reports() = runBlocking {
        val completions = AtomicInteger()

        repeat(200) {
            val source = ByteChannel(autoFlush = true)
            source.writeFully("hello".toByteArray())
            source.close()

            // A scope standing in for an intermediate response's, cancelled immediately after the
            // tee is set up — exactly what Ktor does to a redirect hop it is about to discard.
            val callScope = CoroutineScope(Job() + Dispatchers.Default)
            callScope.teeBody(source, capture = true, maxBytes = 1024) { completions.incrementAndGet() }
            callScope.cancel()
        }

        val reached = withTimeoutOrNull(10_000) {
            while (completions.get() < 200) delay(10)
            true
        }

        assertEquals(
            200, completions.get(),
            "every teed body must report once, even when its call scope dies first — " +
                "reached=${reached == true}",
        )
    }

    @Test
    fun a_discarded_body_is_reported_as_unread_rather_than_empty() = runBlocking {
        val bodies = mutableListOf<CapturedBody>()
        val source = ByteChannel(autoFlush = true)
        source.writeFully("would have been a body".toByteArray())
        source.close()

        // Cancelled *before* the tee, so "the pump never runs" is deterministic rather than a race
        // the reader might win — which is precisely the state a discarded hop is left in.
        val callScope = CoroutineScope(Job() + Dispatchers.Default)
        callScope.cancel()
        callScope.teeBody(source, capture = true, maxBytes = 1024) { bodies += it }

        withTimeoutOrNull(10_000) { while (bodies.isEmpty()) delay(10) }

        assertEquals(1, bodies.size, "the hop must still produce a row")
        // "Empty" would be a claim capture cannot support: nothing was ever read, so nobody knows
        // whether a body was there. This is the guess the omission field exists to prevent.
        assertEquals(BodyOmission.DISCARDED, bodies.single().omitted)
    }
}

private fun CoroutineScope.cancel() = (coroutineContext[Job] as Job).cancel()

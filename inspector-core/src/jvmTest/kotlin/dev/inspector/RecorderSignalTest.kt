package dev.inspector

import dev.inspector.model.NetworkTransaction
import dev.inspector.model.Signal
import dev.inspector.model.SignalTags
import dev.inspector.model.SignalTrigger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Conflation is what keeps an app that emits one `state` signal per keystroke from saturating the
 * wire and the archive. These tests drive it on a virtual clock, because the behaviour that matters
 * is entirely about *when* a value is emitted.
 */
class RecorderSignalTest {

    /** Runs [block] against a recorder whose conflation windows are driven by virtual time. */
    private fun withRecorder(
        config: InspectorConfig = InspectorConfig(),
        block: (Recorder, TestCoroutineScheduler) -> Unit,
    ) {
        val scheduler = TestCoroutineScheduler()
        val scope = CoroutineScope(StandardTestDispatcher(scheduler) + Job())
        val recorder = Recorder(config, scope, now = { scheduler.currentTime })
        try {
            block(recorder, scheduler)
        } finally {
            scope.cancel()
        }
    }

    private fun policy(
        minIntervalMs: Long = 150,
        dropUnchanged: Boolean = true,
        maxPayloadBytes: Int = 64 * 1024,
        ringBufferMaxBytes: Long = 2L * 1024 * 1024,
    ) = InspectorConfig(
        signals = SignalPolicy(
            minIntervalMs = minIntervalMs,
            dropUnchanged = dropUnchanged,
            maxPayloadBytes = maxPayloadBytes,
            ringBufferMaxBytes = ringBufferMaxBytes,
        )
    )

    @Test
    fun a_burst_of_identical_payloads_produces_one_row() {
        withRecorder(policy()) { recorder, scheduler ->
            repeat(100) {
                recorder.signal(SignalTags.STATE, "Checkout", JsonPrimitive("Idle"))
            }
            scheduler.advanceUntilIdle()

            assertEquals(
                1,
                recorder.signals.value.size,
                "dropUnchanged must collapse an unchanging state to a single row",
            )
        }
    }

    @Test
    fun a_burst_of_differing_payloads_keeps_the_last_value_not_the_first() {
        withRecorder(policy()) { recorder, scheduler ->
            repeat(100) { i ->
                recorder.signal(SignalTags.STATE, "Checkout", JsonPrimitive("step-$i"))
            }
            scheduler.advanceUntilIdle()

            val rows = recorder.signals.value
            assertTrue(rows.size < 100, "the burst must be conflated, got ${rows.size} rows")
            // Newest first. The value the state settled on is the one worth keeping.
            assertEquals(
                JsonPrimitive("step-99"),
                rows.first().data,
                "trailing edge: the last value of the burst must survive",
            )
        }
    }

    @Test
    fun a_burst_that_stops_mid_window_still_emits_the_value_it_settled_on() {
        // The failure this guards: an event-driven worker with no timer holds the final value of
        // a burst forever, losing exactly the row trailing-edge conflation exists to keep.
        withRecorder(policy(minIntervalMs = 150)) { recorder, scheduler ->
            recorder.signal(SignalTags.STATE, "Form", JsonPrimitive("a"))
            scheduler.advanceUntilIdle()
            assertEquals(1, recorder.signals.value.size, "the first value emits immediately")

            // Second value lands inside the window, then nothing else ever arrives.
            recorder.signal(SignalTags.STATE, "Form", JsonPrimitive("b"))
            scheduler.advanceTimeBy(10)
            scheduler.runCurrent()
            assertEquals(1, recorder.signals.value.size, "still held: the window has not closed")

            scheduler.advanceUntilIdle()
            assertEquals(2, recorder.signals.value.size, "the held value must emit when it closes")
            assertEquals(JsonPrimitive("b"), recorder.signals.value.first().data)
        }
    }

    @Test
    fun values_outside_the_window_are_not_conflated_at_all() {
        withRecorder(policy(minIntervalMs = 150)) { recorder, scheduler ->
            repeat(3) { i ->
                recorder.signal(SignalTags.SCREEN, "Route", JsonPrimitive("screen-$i"))
                scheduler.advanceTimeBy(200)
                scheduler.runCurrent()
            }
            scheduler.advanceUntilIdle()

            assertEquals(3, recorder.signals.value.size, "spaced-out values must all survive")
        }
    }

    @Test
    fun a_pull_reply_bypasses_dropUnchanged() {
        // The failure this guards: a pull taken while the cache had not changed is swallowed as
        // "unchanged", the host's deferred times out, and the app is reported unresponsive at the
        // moment it was behaving most predictably.
        withRecorder(policy()) { recorder, scheduler ->
            recorder.signal(SignalTags.CACHE, "response", JsonPrimitive("same"))
            scheduler.advanceUntilIdle()
            assertEquals(1, recorder.signals.value.size)

            recorder.signal(
                tag = SignalTags.CACHE,
                name = "response",
                payload = JsonPrimitive("same"),
                trigger = SignalTrigger.Request,
                requestId = "r-1",
            )
            scheduler.advanceUntilIdle()

            val rows = recorder.signals.value
            assertEquals(2, rows.size, "a reply somebody is awaiting must never be dropped")
            assertEquals(SignalTrigger.Request, rows.first().trigger)
            assertEquals("r-1", rows.first().requestId)
        }
    }

    @Test
    fun a_pull_reply_is_not_delayed_by_the_conflation_window() {
        withRecorder(policy(minIntervalMs = 5_000)) { recorder, scheduler ->
            recorder.signal(SignalTags.CACHE, "response", JsonPrimitive("first"))
            scheduler.advanceUntilIdle()

            recorder.signal(
                tag = SignalTags.CACHE,
                name = "response",
                payload = JsonPrimitive("fresh"),
                trigger = SignalTrigger.Request,
                requestId = "r-2",
            )
            // Only drain what is already runnable; do not let virtual time jump the window.
            scheduler.runCurrent()

            assertEquals(2, recorder.signals.value.size, "a pull must not wait for a window")
        }
    }

    @Test
    fun conflation_is_per_tag_and_name_not_global() {
        withRecorder(policy()) { recorder, scheduler ->
            recorder.signal(SignalTags.SCREEN, "Home", JsonPrimitive("x"))
            recorder.signal(SignalTags.STATE, "Home", JsonPrimitive("x"))
            recorder.signal(SignalTags.SCREEN, "Detail", JsonPrimitive("x"))
            scheduler.advanceUntilIdle()

            assertEquals(3, recorder.signals.value.size, "distinct keys must not conflate together")
        }
    }

    @Test
    fun an_oversized_payload_is_truncated_but_reports_its_true_size() {
        withRecorder(policy(maxPayloadBytes = 64)) { recorder, scheduler ->
            recorder.signal(SignalTags.CACHE, "big", JsonPrimitive("x".repeat(5_000)))
            scheduler.advanceUntilIdle()

            val row = recorder.signals.value.single()
            assertTrue(row.dataTruncated, "the payload exceeded the cap and must say so")
            assertTrue(row.bytes > 5_000, "bytes must be the true size, was ${row.bytes}")
        }
    }

    @Test
    fun the_signal_ring_evicts_under_its_own_budget_without_touching_transactions() {
        // Sharing one budget would let a single large cache snapshot evict the entire network
        // history — at exactly the moment somebody needs both side by side.
        withRecorder(policy(minIntervalMs = 0, ringBufferMaxBytes = 4_000)) { recorder, scheduler ->
            repeat(5) { i -> recorder.submit(txn("t$i"), null, null) }
            scheduler.advanceUntilIdle()
            val transactionsBefore = recorder.transactions.value.size

            repeat(40) { i ->
                recorder.signal(SignalTags.CACHE, "dump-$i", JsonPrimitive("y".repeat(1_000)))
            }
            scheduler.advanceUntilIdle()

            assertTrue(
                recorder.signals.value.size < 40,
                "the signal ring must have evicted, kept ${recorder.signals.value.size}",
            )
            assertEquals(
                transactionsBefore,
                recorder.transactions.value.size,
                "signal pressure must not evict captured transactions",
            )
        }
    }

    @Test
    fun signals_carry_the_same_monotonic_clock_as_transactions() {
        // A merged timeline is only possible if both come off one clock; two monotonic clocks
        // cannot be compared to each other.
        withRecorder(policy()) { recorder, scheduler ->
            scheduler.advanceTimeBy(1_234)
            recorder.signal(SignalTags.SCREEN, "Route", JsonPrimitive("a"))
            scheduler.advanceUntilIdle()

            assertEquals(1_234, recorder.signals.value.single().mono)
        }
    }


    @Test
    fun signals_dropped_by_a_full_queue_are_counted_not_silently_lost() {
        // Overload must be visible. A silent drop turns "the signal never appeared" into an
        // unfalsifiable claim about the app rather than a number anyone can read.
        withRecorder(policy()) { recorder, _ ->
            // The worker never runs: nothing advances the scheduler, so the bounded queue fills.
            repeat(1_000) { i ->
                recorder.signal(SignalTags.STATE, "Flood", JsonPrimitive("v$i"))
            }

            assertTrue(recorder.dropped.value > 0, "a saturated queue must report its drops")
        }
    }

    @Test
    fun submitting_a_signal_never_throws_even_with_no_worker_draining() {
        // signal() runs on the app's coroutine. It may drop, but it may never blow up there.
        withRecorder(policy()) { recorder, _ ->
            repeat(1_000) { recorder.signal(SignalTags.STATE, "X", JsonPrimitive("y")) }
        }
    }


    @Test
    fun a_pull_records_a_row_marked_as_requested() = runBlocking {
        withRecorder(policy()) { recorder, scheduler ->
            recorder.registerProvider(SignalTags.CACHE, "response") {
                JsonPrimitive("fresh")
            }

            val error = runBlocking {
                recorder.answerProviderRequest(SignalTags.CACHE, "response", "r-1")
            }
            scheduler.advanceUntilIdle()

            assertNull(error, "a registered provider must answer without error")
            val row = recorder.signals.value.single()
            assertEquals(SignalTrigger.Request, row.trigger)
            assertEquals("r-1", row.requestId)
            assertEquals(JsonPrimitive("fresh"), row.data)
        }
    }

    @Test
    fun an_unregistered_name_names_what_is_registered() {
        // Discovery is the error path in v1, so the error has to carry the listing.
        withRecorder(policy()) { recorder, scheduler ->
            recorder.registerProvider(SignalTags.CACHE, "response") { null }
            recorder.registerProvider(SignalTags.CACHE, "prefs") { null }

            val error = runBlocking {
                recorder.answerProviderRequest(SignalTags.CACHE, "orders", "r-1")
            }
            scheduler.advanceUntilIdle()

            assertNotNull(error)
            assertContains(error, "no provider for cache/orders")
            assertContains(error, "cache/prefs")
            assertContains(error, "cache/response")
            assertTrue(recorder.signals.value.isEmpty(), "a failed pull must leave no row behind")
        }
    }

    @Test
    fun a_provider_that_throws_is_reported_and_records_nothing() {
        withRecorder(policy()) { recorder, scheduler ->
            recorder.registerProvider(SignalTags.CACHE, "response") {
                error("cache closed")
            }

            val message = runBlocking {
                recorder.answerProviderRequest(SignalTags.CACHE, "response", "r-1")
            }
            scheduler.advanceUntilIdle()

            assertNotNull(message)
            assertContains(message, "cache closed")
            assertTrue(recorder.signals.value.isEmpty(), "errors are replies, never rows")
        }
    }

    @Test
    fun unregistering_removes_the_provider_from_the_listing() {
        withRecorder(policy()) { recorder, _ ->
            recorder.registerProvider(SignalTags.CACHE, "response") { null }
            recorder.registerProvider(SignalTags.STATE, "Checkout") { null }
            recorder.unregisterProvider(SignalTags.CACHE, "response")

            assertEquals(listOf("state/Checkout"), recorder.registeredProviders())
        }
    }

    @Test
    fun a_provider_returning_null_still_records_a_row() {
        // "The cache is empty" is an answer. Reporting nothing would look like a failed pull.
        withRecorder(policy()) { recorder, scheduler ->
            recorder.registerProvider(SignalTags.CACHE, "response") { null }

            val error = runBlocking {
                recorder.answerProviderRequest(SignalTags.CACHE, "response", "r-9")
            }
            scheduler.advanceUntilIdle()

            assertNull(error)
            assertEquals(1, recorder.signals.value.size)
            assertNull(recorder.signals.value.single().data)
        }
    }

    @Test
    fun the_error_listing_says_none_when_nothing_is_registered() {
        withRecorder(policy()) { recorder, _ ->
            val error = runBlocking { recorder.answerProviderRequest(SignalTags.CACHE, "x", "r-1") }
            assertNotNull(error)
            assertContains(error, "registered: none")
        }
    }

    private fun txn(id: String) = NetworkTransaction(
        id = id,
        ts = "2026-08-16T10:14:02.311Z",
        mono = 0,
        method = "GET",
        scheme = "https",
        host = "api.example.com",
        path = "/x",
        callId = "c-$id",
    )
}

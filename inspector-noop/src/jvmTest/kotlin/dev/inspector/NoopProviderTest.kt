package dev.inspector

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import java.lang.ref.WeakReference
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The one thing in this module that is not merely "does nothing".
 *
 * A retained provider lambda holds a reference to whatever it closes over — a cache, a repository,
 * a whole object graph — for the life of the process. That is how a no-op stops being free, and no
 * signature check or API-parity assertion would ever catch it: the signatures match either way.
 */
class NoopProviderTest {

    private class ExpensiveGraph(val payload: ByteArray = ByteArray(1 shl 20))

    /**
     * Registers a provider closing over a graph, then drops every reference this frame holds.
     *
     * The capture must be a `val` in a frame that ends. A captured `var` compiles to a
     * `Ref.ObjectRef`, so nulling it here would clear the box the closure points at and the
     * referent would be collected whether or not the lambda was retained — a test that passes
     * against the very mistake it exists to catch.
     */
    private fun registerAndForget(): WeakReference<ExpensiveGraph> {
        val graph = ExpensiveGraph()
        // Exactly what a consuming app writes at its composition root.
        Inspector.registerProvider("cache", "response") { JsonPrimitive(graph.payload.size) }
        return WeakReference(graph)
    }

    @Test
    fun registering_a_provider_does_not_retain_what_it_closes_over() {
        val weak = registerAndForget()

        assertTrue(collected(weak), "the no-op retained the closure; a release build would leak it")
        assertNull(weak.get())
    }

    @Test
    fun the_provider_is_never_invoked_and_the_reason_names_the_build() {
        var called = false
        Inspector.registerProvider("cache", "response") {
            called = true
            null
        }

        val error = runBlocking { Inspector.answerSignalRequest("cache", "response", "r-1") }

        assertTrue(!called, "a release build must never run a provider")
        assertTrue(
            error != null && error.contains("no capture code"),
            "the reason must name the build, or an operator reads it as a broken app: $error",
        )
    }

    /** GC is not deterministic; a few nudges is the usual, and sufficient, compromise. */
    private fun collected(ref: WeakReference<*>): Boolean {
        repeat(20) {
            if (ref.get() == null) return true
            System.gc()
            Thread.sleep(25)
        }
        return ref.get() == null
    }
}

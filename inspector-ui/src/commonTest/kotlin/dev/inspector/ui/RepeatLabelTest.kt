package dev.inspector.ui

import dev.inspector.model.DuplicateGroup
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What a repeated row says about itself.
 *
 * The whole point of the count and the span is telling two very different findings apart: three
 * calls over 40ms is one code path fetching twice, three over 2.4s is a poll or a retry storm.
 */
class RepeatLabelTest {

    @Test
    fun `a repeat says how many and over how long`() {
        val group = DuplicateGroup(ids = listOf("a", "b"), spanMs = 1900, callCount = 2)
        assertEquals("2× / 1.9s", repeatLabel(group))
    }

    @Test
    fun `the count is calls and not rows`() {
        // Three rows because one of the two calls was retried; the app still asked twice. Saying
        // "3×" would be answering a question nobody asked with a number that looks like the one
        // they did ask for.
        val group = DuplicateGroup(ids = listOf("a", "b", "c"), spanMs = 1100, callCount = 2)
        assertEquals("2× / 1.1s", repeatLabel(group))
    }

    @Test
    fun `a tight double fetch reads in milliseconds`() {
        val group = DuplicateGroup(listOf("a", "b"), spanMs = 40, callCount = 2)
        assertEquals("2× / 40ms", repeatLabel(group))
    }
}

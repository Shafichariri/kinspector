package dev.inspector.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The pretty-printer is shared by the overlay and the daemon's Markdown export, and both promise
 * the same thing: the body's own characters, re-indented, never a re-serialised copy.
 */
class PrettyJsonTest {

    @Test
    fun `it indents a minified object`() {
        assertEquals("{\n  \"a\": 1\n}", prettyJson("""{"a":1}"""))
    }

    @Test
    fun `a 64 bit id keeps every digit`() {
        // A parse through a double would print 1234567890123456800.
        assertTrue(prettyJson("""{"id":1234567890123456789}""").contains("1234567890123456789"))
    }

    @Test
    fun `a duplicated key survives`() {
        val out = prettyJson("""{"k":1,"k":2}""")
        assertEquals(2, Regex("\"k\"").findAll(out).count())
    }

    @Test
    fun `a truncated body is still formatted`() {
        val out = prettyJson("""{"items":[{"a":1},{"b""")
        assertTrue(out.startsWith("{\n  \"items\": [\n"), out)
    }

    @Test
    fun `structural characters inside strings are left alone`() {
        assertEquals("{\n  \"s\": \"a,b:{c}\\\"d\"\n}", prettyJson("""{"s":"a,b:{c}\"d"}"""))
    }
}

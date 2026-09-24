package dev.inspector.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The start-ellipsis rule, without a renderer: one unit of width per character.
 *
 * It exists because the renderer's own `StartEllipsis` clips the end on desktop and iOS, so the
 * part worth pinning is that it keeps the *tail* — the end of a path is what names the endpoint.
 */
class FitTailTest {

    private val chars: (String) -> Int = { it.length }

    @Test
    fun `text that fits is left alone`() {
        assertEquals("/v1/users", fitTail("/v1/users", 9, chars))
    }

    @Test
    fun `text that does not fit keeps its end`() {
        assertEquals("…/balance", fitTail("/v3/some-service/accounts/balance", 9, chars))
    }

    @Test
    fun `the tail is the longest that fits and never longer`() {
        val fitted = fitTail("/v3/some-service/accounts/balance", 20, chars)
        assertEquals(20, fitted.length)
        assertEquals("…" + "/v3/some-service/accounts/balance".takeLast(19), fitted)
    }

    @Test
    fun `no room for any tail leaves the ellipsis alone`() {
        assertEquals("…", fitTail("/v1/users", 1, chars))
    }
}

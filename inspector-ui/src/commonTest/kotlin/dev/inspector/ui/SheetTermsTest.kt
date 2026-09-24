package dev.inspector.ui

import dev.inspector.model.FilterParser
import dev.inspector.model.Signal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The filters sheet edits the filter text a term at a time, and these are the rules it edits by.
 *
 * The field is the source of truth and the sheet only a way of typing into it, so everything here
 * is about leaving the rest of the text alone: a term the reader typed by hand must survive every
 * tap on a chip.
 */
class SheetTermsTest {

    private val status = QUICK_FILTERS.map { it.second }

    @Test
    fun `a term is found whole and never as part of a longer one`() {
        // `4xx` is two terms; its first half on its own is a different filter.
        assertTrue(hasTerm("status>=400 status<500", "status>=400 status<500"))
        assertFalse(hasTerm("status>=4000", "status>=400"))
        assertFalse(hasTerm("xmethod:GET", "method:GET"))
        assertTrue(hasTerm("path:/v2  method:GET", "method:GET"))
    }

    @Test
    fun `a marker term with a space inside its quotes is one term`() {
        val term = markerFilterTerm("tapped checkout")
        assertTrue(hasTerm("method:POST $term", term))
        assertEquals("method:POST", removeTerm("method:POST $term", term))
    }

    @Test
    fun `picking in a section replaces the term of that section and keeps the rest`() {
        val typed = "path:/v2"
        val one = replaceTerm(typed, "has:error", status)
        assertEquals("path:/v2 has:error", one)
        // One status at a time: the grammar ANDs whitespace, and `5xx` AND `errors` is just `5xx`
        // with a chip lit that did nothing.
        assertEquals("path:/v2 status>=500", replaceTerm(one, "status>=500", status))
    }

    @Test
    fun `removing a term leaves hand-typed text exactly as it was`() {
        assertEquals("host:api  path:/v2", removeTerm("host:api  path:/v2 has:error", "has:error"))
        assertEquals("path:/v2", removeTerm("has:error path:/v2", "has:error"))
        assertEquals("", removeTerm("has:error", "has:error"))
        assertEquals("path:/v2", removeTerm("path:/v2", "has:error"))
    }

    @Test
    fun `every text the sheet can produce parses`() {
        // A sheet that could write a filter the parser rejects would show an error under the field
        // for something nobody typed.
        var text = "path:/v2"
        for (term in status + "method:POST" + markerFilterTerm("a b")) {
            text = replaceTerm(text, term, status)
            assertTrue(FilterParser.parse(text).isSuccess, "did not parse: $text")
        }
    }

    @Test
    fun `the button counts only terms the sheet owns`() {
        val sections = listOf(
            SheetSection("Status", QUICK_FILTERS.map { SheetChip(it.first, it.second, null) }),
            SheetSection("Method", listOf(SheetChip("GET", "method:GET", null))),
        )
        assertEquals(0, activeSheetTerms("path:/v2", sections))
        assertEquals(2, activeSheetTerms("path:/v2 has:error method:GET", sections))
    }

    private fun obs(name: String, ts: String) = Signal(id = name, ts = ts, mono = 0, tag = "cache", name = name)

    @Test
    fun `the glance is keys and oldest and unread or nothing at all`() {
        // 2026-09-19T10:00:00Z
        val now = 1_789_812_000_000L
        assertNull(nowGlance(emptyList(), now, unread = 0))
        assertEquals(NowGlance("", 2), nowGlance(emptyList(), now, unread = 2))
        val glance = nowGlance(
            listOf(obs("a", "2026-09-19T09:56:00Z"), obs("b", "2026-09-19T09:59:00Z")),
            now,
            unread = 1,
        )
        // The oldest, because that is what decides whether opening the panel is worth it.
        assertEquals(NowGlance("2 keys · oldest 4m", 1), glance)
    }

    @Test
    fun `an unreadable timestamp is not counted as the freshest`() {
        val now = 1_789_812_000_000L
        assertEquals(NowGlance("1 key", 0), nowGlance(listOf(obs("a", "nonsense")), now, unread = 0))
    }
}

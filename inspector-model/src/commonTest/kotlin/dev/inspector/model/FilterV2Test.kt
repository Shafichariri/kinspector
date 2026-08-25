package dev.inspector.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Grammar v2: two row types, one grammar.
 *
 * The rule under test throughout: **a term whose field does not exist on a row type excludes that
 * row type.** It is what lets a single filter string be handed to a merged timeline.
 */
class FilterV2Test {

    private fun parse(text: String) = FilterParser.parseOrThrow(text)

    private val screen = signal(tag = SignalTags.SCREEN, name = "Checkout", mono = 100)
    private val cache = signal(tag = SignalTags.CACHE, name = "response", mono = 200)
    private val call = txn(status = 500, host = "api.example.com", path = "/v2/orders", mono = 150)

    @Test
    fun tag_matches_signals_exactly_and_case_insensitively() {
        assertTrue(parse("tag:screen").matches(screen))
        assertTrue(parse("tag:SCREEN").matches(screen))
        assertFalse(parse("tag:scree").matches(screen), "tag is exact, not a substring")
        assertFalse(parse("tag:cache").matches(screen))
    }

    @Test
    fun name_matches_signals_as_a_substring() {
        assertTrue(parse("name:Check").matches(screen))
        assertTrue(parse("name:checkout").matches(screen))
        assertFalse(parse("name:Portfolio").matches(screen))
    }

    @Test
    fun a_signal_only_term_excludes_transactions() {
        assertFalse(parse("tag:screen").matches(call))
        assertFalse(parse("name:orders").matches(call), "name must not fall back to the path")
    }

    @Test
    fun a_transaction_only_term_excludes_signals() {
        assertFalse(parse("status>=400").matches(screen))
        assertFalse(parse("method:GET").matches(screen))
        assertFalse(parse("has:error").matches(screen))
        assertFalse(parse("slower:1ms").matches(screen))
        assertFalse(parse("larger:1b").matches(screen))
        assertFalse(parse("host:api").matches(screen))
        assertFalse(parse("path:/v2").matches(screen))
        assertFalse(parse("attempt>0").matches(screen))
    }

    @Test
    fun anding_terms_from_both_types_matches_nothing_at_all() {
        // Correct and consistent, not a bug: `status` excludes signals and `tag` excludes
        // transactions, so the conjunction can never hold. Documented so it is not discovered.
        val filter = parse("status:500 tag:screen")
        assertFalse(filter.matches(call))
        assertFalse(filter.matches(screen))
        assertFalse(filter.matches(cache))
    }

    @Test
    fun or_is_how_a_query_spans_both_types() {
        val filter = parse("status:500 | tag:screen")
        assertTrue(filter.matches(call))
        assertTrue(filter.matches(screen))
        assertFalse(filter.matches(cache))
    }

    @Test
    fun text_searches_both_types_but_never_payloads() {
        assertTrue(parse("text:orders").matches(call), "host, path and query on a transaction")
        assertTrue(parse("text:checkout").matches(screen), "tag and name on a signal")
        assertTrue(parse("text:cache").matches(cache), "the tag counts as text")

        val withPayload = signal(
            tag = SignalTags.STATE,
            name = "Holder",
            data = kotlinx.serialization.json.JsonPrimitive("refundable"),
        )
        assertFalse(
            parse("text:refundable").matches(withPayload),
            "payloads are never scanned, mirroring the never-bodies rule",
        )
    }

    @Test
    fun since_marker_cuts_both_streams_at_one_point() {
        // Both row types carry `mono`, which is exactly what makes a merged timeline cuttable.
        val ctx = FilterContext(listOf(marker("tapped submit", mono = 150)))
        val filter = parse("""since:marker("tapped submit")""")

        assertFalse(filter.matches(screen, ctx), "before the marker")
        assertTrue(filter.matches(cache, ctx), "after the marker")
        assertTrue(filter.matches(call, ctx), "at the marker")
    }

    @Test
    fun the_empty_filter_matches_either_row_type() {
        assertTrue(Filter.MatchAll.matches(screen))
        assertTrue(Filter.MatchAll.matches(call))
    }

    @Test
    fun an_unknown_key_still_names_the_valid_ones_including_the_new_terms() {
        val message = runCatching { parse("bogus:1") }.exceptionOrNull()?.message.orEmpty()
        assertTrue(message.contains("tag"), "the error must list the v2 keys too: $message")
        assertTrue(message.contains("name"))
    }

    @Test
    fun tag_and_name_reject_comparison_operators() {
        // They are string terms; `tag>screen` is a typo worth reporting rather than guessing at.
        assertEquals(true, runCatching { parse("tag>screen") }.isFailure)
        assertEquals(true, runCatching { parse("name>=x") }.isFailure)
    }

    @Test
    fun a_marker_survives_the_filter_it_was_used_to_cut_at() {
        // A timeline cut at a marker that then hid the marker would be a strange thing to read.
        val mark = marker("tapped submit", mono = 150)
        val ctx = FilterContext(listOf(mark))

        assertTrue(parse("""since:marker("tapped submit")""").matches(mark, ctx))
        assertTrue(parse("text:submit").matches(mark), "text reaches the label")
    }

    @Test
    fun typed_terms_exclude_markers_so_a_cross_type_and_is_truly_empty() {
        val mark = marker("tapped submit", mono = 150)
        assertFalse(parse("tag:screen").matches(mark))
        assertFalse(parse("status:500").matches(mark))
        assertFalse(parse("status:500 tag:screen").matches(mark))
    }

}

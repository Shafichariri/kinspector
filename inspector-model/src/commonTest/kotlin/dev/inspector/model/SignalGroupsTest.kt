package dev.inspector.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Grouping observations by what they observe, and the chips that browse them.
 *
 * The rules worth pinning are the ones that read as arbitrary until they are wrong: which order
 * each list is in, and that `(tag, name)` — not `name` alone — is the identity.
 */
class SignalGroupsTest {

    private fun obs(tag: String, name: String, mono: Long) =
        signal(id = "s$mono", tag = tag, name = name, mono = mono)

    @Test
    fun a_group_holds_every_observation_of_one_key() {
        val groups = signalGroups(
            listOf(
                obs("state", "CartViewModel", 100),
                obs("state", "CartViewModel", 300),
                obs("state", "CartViewModel", 200),
            )
        )
        val group = groups.single()
        assertEquals(SignalKey("state", "CartViewModel"), group.key)
        assertEquals(3, group.count)
        // Ascending by mono and not in draw order. A history is read as a sequence of changes, so
        // it has one true order and must not take the list's sort toggle.
        assertEquals(listOf(100L, 200L, 300L), group.observations.map { it.mono })
        assertEquals(300L, group.latest.mono)
        assertEquals(100L, group.earliest.mono)
        assertEquals(200L, group.spanMs)
    }

    @Test
    fun the_same_name_under_a_different_tag_is_a_different_thing() {
        val groups = signalGroups(
            listOf(obs("cache", "profile", 100), obs("state", "profile", 200))
        )
        // `name` is documented as identity *within* `tag`. Grouping on the name alone would merge
        // a cache entry with a view-model that happened to share a word, and the history would
        // then show a value changing into something it never was.
        assertEquals(2, groups.size)
        assertEquals(setOf("cache", "state"), groups.map { it.key.tag }.toSet())
    }

    @Test
    fun groups_are_ordered_by_what_changed_last() {
        val groups = signalGroups(
            listOf(obs("state", "a", 100), obs("state", "b", 300), obs("state", "c", 200))
        )
        // Recency, which is wrong for endpoint chips and right here: this orders a list somebody
        // is reading to find what changed, not a strip they are aiming at.
        assertEquals(listOf("b", "c", "a"), groups.map { it.key.name })
    }

    @Test
    fun a_tie_on_mono_breaks_deterministically() {
        // The conflation window emits a batch at one instant, so ties are ordinary rather than
        // exotic — and an order that depended on input order would make a test assert a set.
        val groups = signalGroups(
            listOf(obs("state", "b", 100), obs("cache", "a", 100), obs("state", "a", 100))
        )
        assertEquals(
            listOf("cache" to "a", "state" to "a", "state" to "b"),
            groups.map { it.key.tag to it.key.name },
        )
    }

    @Test
    fun a_single_observation_has_no_span() {
        // Not zero. Zero is a claim that it was watched and did not move.
        assertNull(signalGroups(listOf(obs("screen", "Home", 100))).single().spanMs)
    }

    @Test
    fun history_is_every_observation_of_one_key_oldest_first() {
        val signals = listOf(
            obs("state", "Cart", 300),
            obs("cache", "Cart", 250),
            obs("state", "Cart", 100),
            obs("state", "Other", 200),
        )
        assertEquals(listOf(100L, 300L), signalHistory(signals, "state", "Cart").map { it.mono })
    }

    @Test
    fun tag_chips_are_ordered_by_count_then_recency_then_name() {
        val shortcuts = signalTagShortcuts(
            listOf(
                obs("cache", "a", 100), obs("cache", "b", 110), obs("cache", "c", 120),
                obs("state", "a", 500),
                obs("screen", "Home", 400),
            )
        )
        // Count first, matching endpointShortcuts: a strip that reshuffled as signals arrived
        // would be impossible to aim at on a phone.
        assertEquals("cache", shortcuts.first().tag)
        assertEquals(3, shortcuts.first().count)
        // `state` and `screen` both have one; the later mono wins before the alphabet does.
        assertEquals(listOf("cache", "state", "screen"), shortcuts.map { it.tag })
    }

    @Test
    fun a_tag_that_cannot_be_written_as_a_term_gets_no_chip() {
        val shortcuts = signalTagShortcuts(
            listOf(obs("cache", "a", 100), obs("two words", "b", 110), obs("pipe|tag", "c", 120), obs("quo\"te", "d", 130))
        )
        // The tokenizer splits on whitespace and `|` and toggles on `"`, so a chip carrying one
        // would filter to something other than what its label says — worse than no chip.
        assertEquals(listOf("cache"), shortcuts.map { it.tag })
    }

    @Test
    fun an_unknown_tag_is_an_ordinary_tag() {
        val shortcuts = signalTagShortcuts(listOf(obs("bluetooth", "pairing", 100)))
        // The tag set is open by design. A chip list built from SignalTags would be a list of the
        // tags this build knows about, which is not a fact about the app being debugged.
        assertEquals(listOf("bluetooth"), shortcuts.map { it.tag })
        assertTrue(SignalTags.CACHE !in shortcuts.map { it.tag })
    }

    @Test
    fun the_chip_term_parses_and_selects_only_that_tag() {
        val term = tagFilterTerm("cache")
        assertEquals("tag:cache", term)
        // Asked of the parser rather than assumed, the same way a marker chip's term is.
        val filter = FilterParser.parse(term).getOrNull()!!
        assertTrue(filter.matches(signal(tag = "cache", name = "profile")))
        assertTrue(!filter.matches(signal(tag = "state", name = "profile")))
        // A `tag:` term is a SignalTerm, so it excludes every transaction. That is the exclusion
        // rule working, and it is what makes a tag chip a browser rather than a highlight.
        assertTrue(!filter.matches(txn()))
    }

    @Test
    fun an_empty_session_yields_nothing_rather_than_an_empty_group() {
        assertTrue(signalGroups(emptyList()).isEmpty())
        assertTrue(signalTagShortcuts(emptyList()).isEmpty())
        assertTrue(signalHistory(emptyList(), "state", "Cart").isEmpty())
        assertTrue(signalTagShortcuts(listOf(obs("cache", "a", 1)), limit = 0).isEmpty())
    }
}

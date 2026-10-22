package dev.inspector.daemon

import dev.inspector.daemon.archive.RouteTemplates
import dev.inspector.model.FilterParser
import dev.inspector.model.NetworkTransaction
import dev.inspector.model.matches
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A route's filter term parses, and matches every attempt the route counts: it is a superset.
 *
 * Through the real parser and the real `matches`, so a change to the tokenizer or to `globMatches`
 * turns this red rather than the Traffic list quietly dropping a route's calls. The superset cases
 * the caveat on the Traffic page exists for are asserted to match too, so the caveat stays true if
 * somebody tightens the term.
 */
class RouteFilterTermTest {

    private val host = "api.example.invalid"

    private fun call(path: String, method: String = "GET", host: String = this.host) =
        txn("t", path = path, method = method, host = host)

    private fun term(txn: NetworkTransaction, templates: RouteTemplates = RouteTemplates.NONE) =
        RouteTemplates.filterTerm(templates.keyOf(txn))

    /** Every way the term can fail its row, as one line per failure, so a red run names them all. */
    private fun failures(corpus: List<NetworkTransaction>, templates: RouteTemplates): List<String> =
        corpus.mapNotNull { txn ->
            val term = term(txn, templates)
            val filter = FilterParser.parse(term).getOrElse { return@mapNotNull "'${txn.path}' → '$term' does not parse: ${it.message}" }
            if (filter.matches(txn)) null else "'${txn.path}' → '$term' does not match its own row"
        }

    private val fixed = listOf(
        "/users/123", "/orders/3f2504e0-4f89-11d3-9a0c-0305e82c3301", "/blobs/1a2b3c4d",
        "/t/cus_N5fZqRb3Lk9xYw2Ta", "/x/deadbeefcafe", "/c/1a2b3c4", "/s/notification-preferences",
        "/api/listAccountsForUserV2", "/articles/my-title", "/files/123.json", "/users/1/", "", "/",
        "/Users/1", "//a", "/a//b/", "/users/1/profile/", "/users/me", "/Σ/1", "/aΣ/7/bΣ", "/ΣΣ/", "/İ/1",
        "/a*b/1", "/*/", "/a b/1", "/a|b/2", "/a\"b/3", "/a\tb/", "/%7B id%7D/9", "/{id}/1", "/{id}",
    )

    @Test
    fun `every term in the fixed corpus parses and matches its row`() {
        val corpus = fixed.map { call(it) } + listOf(
            call("/users/1", method = "M S"), call("/users/1", method = "G|T"), call("/users/1", host = "A\"B"),
            call("/users/1", host = ""), call("/", method = "a b", host = "c d"), call("/x").copy(port = 8080),
            call("/x", host = "API.Example.INVALID"),
        )
        assertEquals(emptyList(), failures(corpus, RouteTemplates.NONE))
        val overrides = RouteTemplates.compile(listOf("/articles/{slug}", "/a b/{x}", "/users/{u}/", "/"))
        assertEquals(emptyList(), failures(corpus, overrides))
    }

    @Test
    fun `five thousand seeded random paths each match their own term`() {
        val random = Random(20261020)
        val words = listOf("users", "Users", "me", "v2", "orders", "my-title", "Σigma", "123.json", "a b", "a|b",
            "q\"t", "*", "x*", "%2F", "~tilde", "deadbeefcafe", "profile", "İ", "")
        fun segment(): String = when (random.nextInt(7)) {
            0 -> random.nextLong(0, 1_000_000_000).toString()
            1 -> java.util.UUID(random.nextLong(), random.nextLong()).toString().let { if (random.nextBoolean()) it.uppercase() else it }
            2 -> (1..random.nextInt(6, 20)).map { "0123456789abcdefABCDEF"[random.nextInt(22)] }.joinToString("")
            3 -> (1..random.nextInt(16, 30)).map { "abcXYZ019_-"[random.nextInt(11)] }.joinToString("")
            else -> words[random.nextInt(words.size)]
        }
        val corpus = (0 until 5_000).map {
            val path = (1..random.nextInt(0, 6)).joinToString("/", prefix = "/") { segment() } +
                if (random.nextInt(4) == 0) "/" else ""
            call(path, method = listOf("GET", "post", "Delete")[random.nextInt(3)], host = listOf(host, "API.Example.Invalid")[random.nextInt(2)])
        }
        assertEquals(emptyList(), failures(corpus, RouteTemplates.NONE).take(20))
        val overrides = RouteTemplates.compile(listOf("/users/{id}", "/orders/{o}/", "/{a}/{b}/{c}", "/me"))
        assertEquals(emptyList(), failures(corpus, overrides).take(20))
    }

    @Test
    fun `the examples have exactly these terms`() {
        assertEquals("method:GET host:$host path:/users/*/profile", term(call("/users/42/profile")))
        assertEquals("method:GET host:$host path:*/users/me", term(call("/users/me")))
        assertEquals("method:GET host:$host", term(call("/")))
        assertEquals("method:GET host:$host", term(call("")))
        assertEquals("method:POST host:$host path:/*/*", term(call("/a b/1", method = "POST")))
    }

    // The key's host is the bare literal, and `host:` is a substring match, so the one term finds
    // both stored forms of the server. Keyed bracketed, the term would miss OkHttp's three.
    @Test
    fun `an IPv6 route's term opens the calls of both stored forms`() {
        val calls = ipv6BothClients().filter { it.port == 8080 }
        val terms = calls.map { term(it) }.toSet()
        assertEquals(setOf("method:GET host:::1 path:*/v6"), terms)
        val filter = FilterParser.parseOrThrow(terms.single())
        assertEquals(calls, calls.filter { filter.matches(it) })
    }

    @Test
    fun `the known superset cases match, which is what the caveat says`() {
        val profile = FilterParser.parseOrThrow(term(call("/users/1/profile")))
        assertTrue(profile.matches(call("/users/1/x/profile")), "a * crosses /")
        assertTrue(profile.matches(call("/users/me/profile")), "a * takes a word the route keeps literal")
        assertTrue(FilterParser.parseOrThrow(term(call("/users/me"))).matches(call("/v2/users/me")))
    }

    @Test
    fun `the stats filter is carried only when the parser finds no top-level or`() {
        val route = term(call("/users/1"))
        fun compose(filter: String) = RouteTemplates.compose(filter, FilterParser.parseOrThrow(filter.trim()), route)

        val (carried, yes) = compose(" status>=500 ")
        assertTrue(yes)
        assertEquals("status>=500 $route", carried)

        val (alone, no) = compose("status:500 | has:error")
        assertFalse(no)
        assertEquals(route, alone)

        val (quoted, quotedYes) = compose("since:marker(\"a|b\")")
        assertTrue(quotedYes, "a | inside quotes is not an OR")
        assertEquals("since:marker(\"a|b\") $route", quoted)
        FilterParser.parseOrThrow(quoted)

        assertEquals(route to true, compose(""))
    }
}

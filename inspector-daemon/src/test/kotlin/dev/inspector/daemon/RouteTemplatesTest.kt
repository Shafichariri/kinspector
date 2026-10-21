package dev.inspector.daemon

import dev.inspector.daemon.archive.RouteSource
import dev.inspector.daemon.archive.RouteTemplates
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * Which paths are one route: each class of id-like segment, each documented miss, and every rule of
 * the `routeTemplates` overrides.
 *
 * The misses are pinned on purpose. The segment rule is a heuristic, and a miss "fixed" in passing
 * regroups every recorded session without anybody deciding to; the fix for a miss is an override.
 */
class RouteTemplatesTest {

    private fun route(path: String, templates: RouteTemplates = RouteTemplates.NONE) = templates.routeOf(path).text

    private fun templates(vararg entries: String) = RouteTemplates.compile(entries.toList()) { error("unexpected: $it") }

    @Test
    fun `each id-like class reads as an id`() {
        assertEquals("/users/{id}", route("/users/123"))
        assertEquals("/orders/{id}", route("/orders/3f2504e0-4f89-11d3-9a0c-0305e82c3301"))
        assertEquals("/orders/{id}", route("/orders/3F2504E0-4F89-11D3-9A0C-0305E82C3301"))
        assertEquals("/blobs/{id}", route("/blobs/1a2b3c4d"))
        assertEquals("/t/{id}", route("/t/cus_N5fZqRb3Lk9xYw2Ta"))
        assertEquals("/t/{id}", route("/t/aB3dEfGhIjKlMnOpQrSt=="))
        assertEquals("/users/{id}/orders/{id}", route("/users/42/orders/7"))
    }

    @Test
    fun `words stay literal`() {
        assertEquals("/x/deadbeefcafe", route("/x/deadbeefcafe"), "all-letter hex has no digit")
        assertEquals("/c/1a2b3c4", route("/c/1a2b3c4"), "seven hex characters are under the threshold")
        assertEquals("/s/notification-preferences", route("/s/notification-preferences"))
        assertEquals("/users/me", route("/users/me"))
        assertEquals("/v2/users/me", route("/v2/users/me"))
    }

    @Test
    fun `the documented misses stay misses`() {
        assertEquals("/api/{id}", route("/api/listAccountsForUserV2"), "camelCase with a digit reads as an id")
        assertEquals("/articles/my-title", route("/articles/my-title"), "a slug stays literal")
        assertEquals("/files/123.json", route("/files/123.json"), "an id with an extension stays literal")
        assertEquals("/t/usr_123", route("/t/usr_123"), "a short token stays literal")
        assertEquals("/r/{id}", route("/r/20241001"), "a date reads as an id")
        assertEquals("/a/b%20c1D2e3F4g5H6i7J8k9L0", route("/a/b%20c1D2e3F4g5H6i7J8k9L0"), "% is not base64-like")
    }

    @Test
    fun `the path is taken as captured and only normalised from empty to root`() {
        assertEquals("/users/{id}/", route("/users/1/"), "a trailing slash is kept")
        assertNotEquals(route("/users/1/"), route("/users/1"))
        assertEquals("/", route(""))
        assertEquals("/", route("/"))
        assertEquals("//{id}", route("//7"))
        assertEquals("/a//{id}/", route("/a//7/"), "an empty segment is never an id")
        assertNotEquals(route("/Users/1"), route("/users/1"), "path case is kept")
    }

    @Test
    fun `the key lowercases the host, uppercases the method and drops only a default port`() {
        val t = RouteTemplates.NONE
        val key = t.keyOf(txn("a", path = "/users/9", host = "API.Example.INVALID", method = "get"))
        assertEquals("GET", key.method)
        assertEquals("api.example.invalid", key.host)
        assertNull(key.port)
        assertNull(t.keyOf(txn("b").copy(port = 443)).port)
        assertEquals(8080, t.keyOf(txn("c").copy(port = 8080)).port)
        assertNull(t.keyOf(txn("d").copy(scheme = "http", port = 80)).port)
        assertEquals(443, t.keyOf(txn("e").copy(scheme = "http", port = 443)).port)
        // The query is not part of a path at all; two queries on one path are one route.
        assertEquals(t.keyOf(txn("f").copy(query = "a=1")), t.keyOf(txn("g").copy(query = "token=x")))
    }

    @Test
    fun `an override wins in both directions and the first match wins`() {
        val t = templates("/reports/2024", "/articles/{slug}", "/v2/users/{userId}/avatar", "/v2/users/{other}/avatar")
        assertEquals("/reports/2024", route("/reports/2024", t), "a literal keeps what the heuristic would template")
        assertEquals(RouteSource.CONFIG, t.routeOf("/reports/2024").source)
        assertEquals("/reports/{id}", route("/reports/2025", t), "no template matches, so the heuristic applies")
        assertEquals(RouteSource.HEURISTIC, t.routeOf("/reports/2025").source)
        assertEquals("/articles/{slug}", route("/articles/my-title", t), "a placeholder groups what it would keep")
        assertEquals("/v2/users/{userId}/avatar", route("/v2/users/me/avatar", t), "first match wins, name as written")
    }

    @Test
    fun `a template matches segment by segment and a placeholder never takes an empty one`() {
        val t = templates("/articles/{slug}")
        assertEquals("/articles/my-title/comments", route("/articles/my-title/comments", t), "segment count differs")
        assertEquals("/articles/", route("/articles/", t), "an empty segment is not a slug")
        assertEquals("/Articles/x", route("/Articles/x", t), "literals compare with case")
        assertEquals("/articles/{slug}", route("/articles/x", t))
    }

    @Test
    fun `problems name each bad entry and why`() {
        val cases = mapOf(
            "articles/{slug}" to "starts with '/'",
            "/a/{bad name}" to "must be a whole placeholder",
            "/a/{1x}" to "must be a whole placeholder",
            "/a/x{id}" to "must be a whole placeholder",
            "/a/{id" to "must be a whole placeholder",
            "/a?b=1" to "the query never takes part",
            "/a#top" to "the query never takes part",
            "/" + "a".repeat(512) to "at most 512 characters",
        )
        for ((entry, why) in cases) {
            val problems = RouteTemplates.problems(listOf("/ok/{id}", entry))
            assertEquals(1, problems.size, "$entry: $problems")
            assertContains(problems.single(), "'$entry'")
            assertContains(problems.single(), why)
        }
        assertEquals(emptyList(), RouteTemplates.problems(listOf("/", "/a/{_x1}", "/a//b/", "/reports/2024")))
        val tooMany = RouteTemplates.problems(List(201) { "/a/$it" })
        assertContains(tooMany.single(), "the most is 200")
    }

    @Test
    fun `compile skips each bad entry, reports it, and keeps the rest`() {
        val reported = mutableListOf<String>()
        val t = RouteTemplates.compile(listOf("/a/{x}", "nope", "/b/{y}")) { reported += it }
        assertEquals(listOf("/a/{x}", "/b/{y}"), t.entries)
        assertEquals(listOf("ignoring routeTemplates entry 'nope': a template starts with '/', as in '/articles/{slug}'"), reported)
    }
}

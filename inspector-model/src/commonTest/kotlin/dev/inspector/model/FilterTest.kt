package dev.inspector.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Table-driven coverage of the filter grammar.
 *
 * The plan requires at least 40 matching cases plus malformed-input cases;
 * [case_table_meets_the_required_size] enforces that mechanically so the requirement cannot rot.
 */
class FilterTest {

    private data class Case(
        val filter: String,
        val txn: NetworkTransaction,
        val expected: Boolean,
        val ctx: FilterContext = FilterContext.EMPTY,
    )

    private val markerCtx = FilterContext(
        listOf(
            marker("login", mono = 500),
            marker("tapped checkout", mono = 1_000),
            marker("tapped checkout", mono = 2_000), // duplicate label: the later one wins
        )
    )

    private val cases = listOf(
        // --- empty filter -------------------------------------------------------------------
        Case("", txn(), true),
        Case("    ", txn(), true),

        // --- status -------------------------------------------------------------------------
        Case("status:200", txn(status = 200), true),
        Case("status:200", txn(status = 404), false),
        Case("status>=400", txn(status = 404), true),
        Case("status>=400", txn(status = 399), false),
        Case("status>400", txn(status = 400), false),
        Case("status<500", txn(status = 404), true),
        Case("status<=404", txn(status = 404), true),
        Case("status:200", txn(status = null, error = "boom"), false),
        Case("status>=400", txn(status = null, error = "boom"), false),

        // --- method -------------------------------------------------------------------------
        Case("method:POST", txn(method = "POST"), true),
        Case("method:post", txn(method = "POST"), true),
        Case("method:GET", txn(method = "POST"), false),

        // --- host ---------------------------------------------------------------------------
        Case("host:api.example.com", txn(host = "api.example.com"), true),
        Case("host:example", txn(host = "api.example.com"), true),
        Case("host:API.EXAMPLE", txn(host = "api.example.com"), true),
        Case("host:other.com", txn(host = "api.example.com"), false),

        // --- path: glob when it has '*', substring otherwise --------------------------------
        Case("path:/v2/users/me", txn(path = "/v2/users/me"), true),
        Case("path:/v2/users", txn(path = "/v2/users/me"), true),
        Case("path:/v2/users/*", txn(path = "/v2/users/me"), true),
        Case("path:/v2/*/me", txn(path = "/v2/users/me"), true),
        Case("path:/v1/*", txn(path = "/v2/users/me"), false),
        Case("path:*/me", txn(path = "/v2/users/me"), true),
        Case("path:*", txn(path = "/v2/users/me"), true),
        Case("path:/V2/USERS/*", txn(path = "/v2/users/me"), true),
        Case("path:/v2/users/*", txn(path = "/v2/users"), false),

        // --- slower -------------------------------------------------------------------------
        Case("slower:100ms", txn(ms = 143), true),
        Case("slower:200ms", txn(ms = 143), false),
        Case("slower:143ms", txn(ms = 143), false),
        Case("slower:1s", txn(ms = 1_500), true),
        Case("slower:2s", txn(ms = 1_500), false),
        Case("slower:500", txn(ms = 600), true),
        Case("slower:100ms", txn(ms = null), false),

        // --- larger -------------------------------------------------------------------------
        Case("larger:1kb", txn(resBytes = 2_841), true),
        Case("larger:10kb", txn(resBytes = 2_841), false),
        Case("larger:100b", txn(reqBytes = 200, resBytes = 0), true),
        Case("larger:1mb", txn(resBytes = 2_841), false),
        Case("larger:1000", txn(resBytes = 2_841), true),

        // --- has:error ----------------------------------------------------------------------
        Case("has:error", txn(status = 500), true),
        Case("has:error", txn(status = 404), true),
        Case("has:error", txn(status = 200), false),
        Case("has:error", txn(status = 302), false),
        Case("has:error", txn(status = null, error = "SocketTimeout"), true),

        // --- text: host + path + query, never bodies ----------------------------------------
        Case("text:users", txn(path = "/v2/users/me"), true),
        Case("text:api", txn(host = "api.example.com"), true),
        Case("text:page", txn(query = "page=2"), true),
        Case("text:USERS", txn(path = "/v2/users/me"), true),
        Case("text:refund", txn(), false),

        // --- attempt ------------------------------------------------------------------------
        Case("attempt>1", txn(attempt = 2), true),
        Case("attempt>1", txn(attempt = 1), false),
        Case("attempt:1", txn(attempt = 1), true),
        Case("attempt>=2", txn(attempt = 3), true),

        // --- implicit AND -------------------------------------------------------------------
        Case("status>=400 method:POST", txn(status = 500, method = "POST"), true),
        Case("status>=400 method:GET", txn(status = 500, method = "POST"), false),
        Case("host:example path:/v2 status:200", txn(), true),

        // --- OR, and its looser binding than AND --------------------------------------------
        Case("status:200 | status:404", txn(status = 200), true),
        Case("status:500 | status:404", txn(status = 404), true),
        Case("status:500 | status:503", txn(status = 404), false),
        Case("method:GET status:200 | method:POST status:500", txn(method = "GET", status = 200), true),
        Case("method:GET status:200 | method:POST status:500", txn(method = "POST", status = 500), true),
        Case("method:GET status:200 | method:POST status:500", txn(method = "GET", status = 500), false),

        // --- since:marker -------------------------------------------------------------------
        Case("since:marker(\"login\")", txn(mono = 600), true, markerCtx),
        Case("since:marker(\"login\")", txn(mono = 400), false, markerCtx),
        Case("since:marker(\"login\")", txn(mono = 500), true, markerCtx),
        Case("since:marker(\"tapped checkout\")", txn(mono = 2_500), true, markerCtx),
        // 1_500 sits after the first "tapped checkout" but before the later one, which wins.
        Case("since:marker(\"tapped checkout\")", txn(mono = 1_500), false, markerCtx),
        Case("since:marker(\"TAPPED CHECKOUT\")", txn(mono = 2_500), true, markerCtx),
        Case("since:marker(\"never happened\")", txn(mono = 9_999), false, markerCtx),
        Case("since:marker(\"login\")", txn(mono = 9_999), false, FilterContext.EMPTY),
        Case("since:marker(\"login\") has:error", txn(mono = 600, status = 500), true, markerCtx),
    )

    @Test
    fun filter_table() {
        val failures = mutableListOf<String>()
        for (case in cases) {
            val filter = FilterParser.parse(case.filter).getOrElse { e ->
                failures += "'${case.filter}' failed to parse: ${e.message}"
                continue
            }
            val actual = filter.matches(case.txn, case.ctx)
            if (actual != case.expected) {
                failures += "'${case.filter}' on ${case.txn.method} ${case.txn.path} " +
                    "status=${case.txn.status} ms=${case.txn.ms} attempt=${case.txn.attempt} " +
                    "→ expected ${case.expected}, got $actual"
            }
        }
        if (failures.isNotEmpty()) {
            fail("${failures.size} of ${cases.size} filter cases failed:\n" + failures.joinToString("\n"))
        }
    }

    @Test
    fun case_table_meets_the_required_size() {
        assertTrue(cases.size >= 40, "plan requires >= 40 filter cases, found ${cases.size}")
    }

    // -----------------------------------------------------------------------------------------
    // Malformed input. Messages surface verbatim in the app filter bar, the web UI, REST 400s
    // and MCP errors — so each one is asserted to name both the problem and the fix.
    // -----------------------------------------------------------------------------------------

    private data class ErrorCase(val filter: String, val mustMention: List<String>)

    private val errorCases = listOf(
        ErrorCase("status", listOf("Missing operator", "key:value")),
        ErrorCase("bogus:1", listOf("Unknown filter key", "status")),
        ErrorCase("status:abc", listOf("expects a number", "status>=400")),
        ErrorCase("attempt:many", listOf("expects a number")),
        ErrorCase("method>=POST", listOf("does not support", "method:POST")),
        ErrorCase("host>example.com", listOf("does not support")),
        ErrorCase("slower:fast", listOf("duration", "500ms")),
        ErrorCase("larger:big", listOf("size", "10kb")),
        ErrorCase("has:body", listOf("only supports", "has:error")),
        ErrorCase("since:login", listOf("marker(")),
        ErrorCase("since:marker(\"\")", listOf("marker label")),
        ErrorCase("| status:200", listOf("Empty side")),
        ErrorCase("status:200 |", listOf("Trailing '|'")),
        ErrorCase("status:200 | | status:404", listOf("Empty side")),
        ErrorCase("since:marker(\"unclosed", listOf("Unclosed quote")),
        ErrorCase(":200", listOf("filter key")),
        ErrorCase("status:", listOf("needs a value")),
    )

    @Test
    fun malformed_filters_fail_with_actionable_messages() {
        val failures = mutableListOf<String>()
        for (case in errorCases) {
            val result = FilterParser.parse(case.filter)
            val error = result.exceptionOrNull()
            if (error == null) {
                failures += "'${case.filter}' should not have parsed, got ${result.getOrNull()}"
                continue
            }
            val message = error.message.orEmpty()
            for (fragment in case.mustMention) {
                if (!message.contains(fragment)) {
                    failures += "'${case.filter}' error should mention '$fragment', was: $message"
                }
            }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    @Test
    fun parse_returns_result_rather_than_throwing_on_the_keystroke_path() {
        assertTrue(FilterParser.parse("status>=400").isSuccess)
        assertTrue(FilterParser.parse("nonsense!!").isFailure)
    }

    @Test
    fun empty_filter_parses_to_match_all() {
        assertEquals(Filter.MatchAll, FilterParser.parseOrThrow(""))
        assertEquals(Filter.MatchAll, FilterParser.parseOrThrow("   \t "))
    }

    @Test
    fun parsed_filters_are_reusable_across_contexts() {
        // Filters are immutable; marker state arrives via FilterContext, not baked in at parse.
        val filter = FilterParser.parseOrThrow("since:marker(\"login\")")
        assertTrue(filter.matches(txn(mono = 600), markerCtx))
        assertEquals(false, filter.matches(txn(mono = 600), FilterContext.EMPTY))
    }

    @Test
    fun glob_suffix_must_not_overlap_material_already_consumed() {
        assertTrue(globMatches("/a*a", "/aXa"))
        assertEquals(false, globMatches("/a*a", "/a"))
        assertTrue(globMatches("/a*", "/a"))
    }
}

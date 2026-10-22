package dev.inspector.daemon

import dev.inspector.daemon.archive.FindingRuleCatalog
import dev.inspector.daemon.archive.FindingSettings
import dev.inspector.daemon.archive.FindingSeverity
import dev.inspector.daemon.archive.PaymentSettings
import dev.inspector.daemon.archive.RuleOverride
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The `findings` key: its defaults, every message, and the canonical form `ruleVersion` hashes. One
 * reader serves `PUT` (the first problem refuses the save) and the live read (each bad entry skipped).
 */
class FindingSettingsTest {

    private fun problem(text: String): String? = FindingSettings.problem(Json.parseToJsonElement(text))

    private fun read(text: String): Pair<FindingSettings, List<String>> {
        val problems = ArrayList<String>()
        return FindingSettings.read(Json.parseToJsonElement(text)) { problems += it } to problems
    }

    @Test
    fun `the defaults are the six generic paths, two headers and the two windows`() {
        val payment = FindingSettings.DEFAULT.payment
        assertEquals(listOf("Idempotency-Key", "X-Idempotency-Key"), payment.idempotencyHeaders)
        assertEquals(listOf("payment", "checkout", "charge", "transfer", "refund", "payout"), payment.paymentPaths)
        assertEquals(emptyList(), payment.ignorePaths)
        assertEquals(3_000L, payment.duplicateWindowMs)
        assertEquals(60_000L, payment.resubmitWindowMs)
        assertEquals(emptyMap(), FindingSettings.DEFAULT.rules)
        assertEquals(FindingSettings.DEFAULT, read("{}").first)
        assertEquals(FindingSettings.DEFAULT, FindingSettings.read(null) {})
    }

    @Test
    fun `the catalogue holds the six payment rules with stable ids and their default severities`() {
        assertEquals(
            listOf(
                "payment.duplicate-mutation" to "high", "payment.key-reused-body-changed" to "high",
                "payment.retry-without-key" to "high", "payment.ambiguous-resubmit" to "medium",
                "payment.key-missing-payment-path" to "medium", "payment.retry-with-key" to "info",
            ),
            FindingRuleCatalog.ALL.map { it.id to it.defaultSeverity.key },
        )
        assertEquals(listOf("payment"), FindingRuleCatalog.CATEGORIES)
    }

    @Test
    fun `a valid key is read whole`() {
        val (settings, problems) = read(
            """{"rules":{"payment.retry-with-key":{"enabled":false},"payment.duplicate-mutation":{"severity":"medium"}},
               "payment":{"idempotencyHeaders":["Idem-Key"],"paymentPaths":["*/charges"],"ignorePaths":["graphql"],
                          "duplicateWindowMs":5000,"resubmitWindowMs":1}}""",
        )
        assertEquals(emptyList(), problems)
        assertEquals(RuleOverride(enabled = false), settings.rules["payment.retry-with-key"])
        assertEquals(RuleOverride(severity = FindingSeverity.MEDIUM), settings.rules["payment.duplicate-mutation"])
        assertEquals(PaymentSettings(listOf("Idem-Key"), listOf("*/charges"), listOf("graphql"), 5_000, 1), settings.payment)
    }

    @Test
    fun `every bad value is named in this file's words`() {
        val cases = mapOf(
            """[]""" to "findings must be an object",
            """{"sensitiv":{}}""" to "findings has an unknown key 'sensitiv'; the keys are rules, payment",
            """{"rules":[]}""" to "findings.rules must be an object of rule id",
            """{"rules":{"payment.nope":{}}}""" to "findings.rules has an unknown rule 'payment.nope'; the rules are payment.duplicate-mutation",
            """{"rules":{"payment.retry-with-key":false}}""" to "findings.rules 'payment.retry-with-key' must be an object",
            """{"rules":{"payment.retry-with-key":{"on":false}}}""" to "findings.rules 'payment.retry-with-key' has an unknown key 'on'",
            """{"rules":{"payment.retry-with-key":{"enabled":"no"}}}""" to "enabled must be true or false",
            """{"rules":{"payment.retry-with-key":{"severity":"critical"}}}""" to "severity must be high, medium, info",
            """{"payment":[]}""" to "findings.payment must be an object",
            """{"payment":{"window":1}}""" to "findings.payment has an unknown key 'window'",
            """{"payment":{"idempotencyHeaders":"Idempotency-Key"}}""" to "findings.payment.idempotencyHeaders must be a list",
            """{"payment":{"idempotencyHeaders":["Idem Key"]}}""" to "entry 'Idem Key' is not a header name",
            """{"payment":{"idempotencyHeaders":[]}}""" to "needs at least one header name",
            """{"payment":{"paymentPaths":["path:/v1/pay"]}}""" to "entry 'path:/v1/pay': write the pattern without path:",
            """{"payment":{"paymentPaths":["  "]}}""" to "a pattern cannot be blank",
            """{"payment":{"paymentPaths":[3]}}""" to "findings.payment.paymentPaths entry 3 must be a string",
            """{"payment":{"ignorePaths":["a|b"]}}""" to "findings.payment.ignorePaths entry 'a|b':",
            """{"payment":{"ignorePaths":["a | path:/b"]}}""" to "a pattern is one path, as path: takes it",
            """{"payment":{"paymentPaths":["/v1 method:POST"]}}""" to "a pattern is one path, as path: takes it",
            """{"payment":{"duplicateWindowMs":0}}""" to "duplicateWindowMs must be a whole number of milliseconds from 1 to 600000",
            """{"payment":{"resubmitWindowMs":600001}}""" to "resubmitWindowMs must be a whole number of milliseconds from 1 to 600000",
            """{"payment":{"duplicateWindowMs":"3000"}}""" to "duplicateWindowMs must be a whole number",
            """{"payment":{"duplicateWindowMs":1.5}}""" to "duplicateWindowMs must be a whole number",
        )
        val wrong = cases.mapNotNull { (text, expected) ->
            val got = problem(text)
            if (got != null && expected in got) null else "$text → $got"
        }
        assertEquals(emptyList(), wrong)
    }

    @Test
    fun `a pattern with a space is refused with the parser's own message`() {
        // `path:a b` reads as two terms, and the parser says what is wrong with the second.
        assertContains(problem("""{"payment":{"paymentPaths":["a b"]}}""")!!, "findings.payment.paymentPaths entry 'a b': Missing operator in 'b'")
    }

    @Test
    fun `the bounds hold at their edges`() {
        assertNull(problem("""{"payment":{"duplicateWindowMs":1,"resubmitWindowMs":600000}}"""))
        assertNull(problem("""{"payment":{"paymentPaths":[]}}"""), "no payment paths is a choice, with a note on the rule")
        assertNull(problem("""{"payment":{"paymentPaths":["${"a".repeat(256)}"]}}"""))
        assertContains(problem("""{"payment":{"paymentPaths":["${"a".repeat(257)}"]}}""")!!, "at most 256 characters")
        val many = (1..101).joinToString(",") { "\"p$it\"" }
        assertContains(problem("""{"payment":{"paymentPaths":[$many]}}""")!!, "holds 101 patterns; the most is 100")
        val headers = (1..21).joinToString(",") { "\"H$it\"" }
        assertContains(problem("""{"payment":{"idempotencyHeaders":[$headers]}}""")!!, "holds 21 names; the most is 20")
    }

    @Test
    fun `a read skips each bad entry, names it, and keeps the rest`() {
        val (settings, problems) = read(
            """{"rules":{"payment.nope":{"enabled":false},"payment.retry-with-key":{"enabled":false}},
               "payment":{"paymentPaths":["path:/x","checkout"],"duplicateWindowMs":0}}""",
        )
        assertEquals(setOf("payment.retry-with-key"), settings.rules.keys)
        assertEquals(listOf("checkout"), settings.payment.paymentPaths)
        assertEquals(3_000L, settings.payment.duplicateWindowMs, "a bad window keeps the default")
        assertEquals(3, problems.size, problems.toString())
    }

    @Test
    fun `canonical is stable under key order and spelled-out defaults`() {
        val a = read("""{"payment":{"resubmitWindowMs":60000,"paymentPaths":["x"]},"rules":{"payment.retry-with-key":{"severity":"high","enabled":false}}}""").first
        val b = read("""{"rules":{"payment.retry-with-key":{"enabled":false,"severity":"high"}},"payment":{"paymentPaths":["x"]}}""").first
        assertEquals(a.canonical(), b.canonical())
        assertEquals(FindingSettings.DEFAULT.canonical(), read("""{"rules":{"payment.retry-with-key":{}}}""").first.canonical())
        val c = read("""{"payment":{"paymentPaths":["y"]}}""").first
        assert(a.canonical() != c.canonical())
    }

    @Test
    fun `a pattern matches exactly the calls path does, glob or substring, in any case`() {
        val settings = read("""{"payment":{"paymentPaths":["*/charges"],"ignorePaths":["graphql"]}}""").first.payment
        assertEquals("*/charges", settings.paymentPathMatch(txn("a", path = "/v1/charges")))
        assertNull(settings.paymentPathMatch(txn("b", path = "/v1/charges/1")))
        assertEquals("payment", PaymentSettings().paymentPathMatch(txn("c", path = "/v1/PAYMENTS")))
        assertEquals(true, settings.ignored(txn("d", path = "/api/GraphQL")))
    }
}

package dev.inspector.daemon.service

import dev.inspector.daemon.archive.FindingSeverity
import dev.inspector.daemon.service.FindingsGate.Verdict
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The CI gate's verdict and its stderr lines, over reports built by hand: it judges on the totals the
 * engine took before the limit with dismissed findings left out, never on the listed findings, and it
 * refuses to say "pass" about a session with no calls or a config.json the read rejected.
 */
class FindingsGateTest {

    private fun finding(severity: FindingSeverity, rule: String = "payment.duplicate-mutation", dismissed: Boolean = false) = Finding(
        id = "f-$rule-${severity.key}",
        ruleId = rule,
        category = rule.substringBefore('.'),
        severity = severity.key,
        route = "POST https://api.example.invalid/v1/payments",
        message = "2 calls sent the same body",
        txnIds = listOf("t1", "t2"),
        callIds = listOf("c1", "c2"),
        txnCount = 2,
        evidence = listOf(Evidence("t1", location = "reqHeader", path = "idempotency-key", masked = "•••• 9f3a")),
        dismissed = if (dismissed) Dismissal("expected in this flow") else null,
    )

    private fun report(
        findings: List<Finding>,
        high: Int = findings.count { it.severity == "high" && it.dismissed == null },
        medium: Int = findings.count { it.severity == "medium" && it.dismissed == null },
        info: Int = findings.count { it.severity == "info" && it.dismissed == null },
        dismissed: Int = 0,
        total: Int = 12,
        configError: String? = null,
        coverage: JsonObject? = null,
    ) = FindingsReport(
        sessionId = "s1", ruleVersion = "abcd1234", configError = configError, total = total,
        findingCount = high + medium + info, dismissedCount = dismissed, high = high, medium = medium, info = info,
        rules = emptyList(), findings = findings, coverage = coverage,
    )

    @Test
    fun `a finding at the threshold fails, one below it passes`() {
        assertEquals(Verdict.Fail(1), FindingsGate.judge(report(listOf(finding(FindingSeverity.HIGH))), FindingSeverity.HIGH))
        val medium = report(listOf(finding(FindingSeverity.MEDIUM)))
        assertEquals(Verdict.Pass(0), FindingsGate.judge(medium, FindingSeverity.HIGH))
        assertEquals(Verdict.Fail(1), FindingsGate.judge(medium, FindingSeverity.MEDIUM))
        val info = report(listOf(finding(FindingSeverity.INFO)))
        assertEquals(Verdict.Pass(0), FindingsGate.judge(info, FindingSeverity.MEDIUM))
        assertEquals(Verdict.Fail(1), FindingsGate.judge(info, FindingSeverity.INFO))
        // At info every severity counts.
        val all = report(listOf(finding(FindingSeverity.HIGH), finding(FindingSeverity.MEDIUM), finding(FindingSeverity.INFO)))
        assertEquals(Verdict.Fail(3), FindingsGate.judge(all, FindingSeverity.INFO))
        assertEquals(Verdict.Fail(2), FindingsGate.judge(all, FindingSeverity.MEDIUM))
    }

    @Test
    fun `the limit cannot hide a finding, and the lines say how many were not listed`() {
        // Two high findings, the report cut to one by --limit 1.
        val cut = report(listOf(finding(FindingSeverity.HIGH)), high = 2)
        val verdict = FindingsGate.judge(cut, FindingSeverity.HIGH)
        assertEquals(Verdict.Fail(2), verdict)
        val lines = FindingsGate.lines(cut, FindingSeverity.HIGH, verdict)
        assertEquals("inspector: 2 findings at or above high in s1 (rules abcd1234)", lines.first())
        assertEquals(
            "  high payment.duplicate-mutation POST https://api.example.invalid/v1/payments — 2 calls sent the same body; " +
                "evidence: reqHeader idempotency-key •••• 9f3a",
            lines[1],
        )
        assertEquals("  and 1 more", lines.last())
    }

    @Test
    fun `dismissed findings are not counted`() {
        val dismissed = report(listOf(finding(FindingSeverity.HIGH, dismissed = true), finding(FindingSeverity.HIGH, "payment.retry-without-key", dismissed = true)), dismissed = 2)
        assertEquals(0, dismissed.high)
        assertEquals(Verdict.Pass(0), FindingsGate.judge(dismissed, FindingSeverity.HIGH))
        assertEquals(listOf("inspector: 0 findings at or above high in s1 (rules abcd1234)"), FindingsGate.lines(dismissed, FindingSeverity.HIGH, Verdict.Pass(0)))
    }

    @Test
    fun `it cannot judge a session with no calls or a config it could not read`() {
        val broken = report(listOf(finding(FindingSeverity.HIGH)), configError = "findings must be an object")
        val verdict = FindingsGate.judge(broken, FindingSeverity.HIGH)
        assertTrue(verdict is Verdict.CannotJudge && "config.json" in verdict.why, "was $verdict")
        // Also with nothing found: the defaults would read green, which is the trap.
        val brokenClean = report(emptyList(), configError = "findings must be an object")
        assertTrue(FindingsGate.judge(brokenClean, FindingSeverity.HIGH) is Verdict.CannotJudge)

        val empty = report(emptyList(), total = 0)
        val none = FindingsGate.judge(empty, FindingSeverity.INFO)
        assertTrue(none is Verdict.CannotJudge && "holds no calls" in none.why, "was $none")
        assertEquals(listOf("inspector: cannot judge s1: ${none.why}"), FindingsGate.lines(empty, FindingSeverity.INFO, none))
    }

    @Test
    fun `the coverage lines are printed, and a bad severity names the three there are`() {
        val coverage = JsonObject(mapOf("payment" to JsonObject(mapOf("line" to JsonPrimitive("2 request bodies were truncated and could not be compared.")))))
        val clean = report(emptyList(), coverage = coverage)
        assertEquals(
            listOf("inspector: 0 findings at or above high in s1 (rules abcd1234)", "  2 request bodies were truncated and could not be compared."),
            FindingsGate.lines(clean, FindingSeverity.HIGH, FindingsGate.judge(clean, FindingSeverity.HIGH)),
        )
        assertEquals(FindingSeverity.MEDIUM, FindingsGate.parse("medium").getOrThrow())
        assertEquals("unknown --fail-on 'critical'. Use one of: high, medium, info.", FindingsGate.parse("critical").exceptionOrNull()?.message)
        assertTrue(FindingsGate.parse(null).isFailure)
    }
}

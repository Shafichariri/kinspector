package dev.inspector.daemon.service

import dev.inspector.daemon.archive.FindingSeverity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `inspector findings --fail-on <severity>`: the verdict a CI step reads from the exit code, judged
 * from a report the CLI already built. Pure, so `FindingsGateTest` holds every rule without a process.
 *
 * It counts the report's `high`/`medium`/`info` totals, never `findings`: those totals are taken
 * before the limit with dismissed findings left out, so `--limit` cannot hide a finding and a
 * dismissal is honoured exactly as the page and `list_findings` honour it.
 */
object FindingsGate {

    sealed interface Verdict {
        /** No undismissed finding at or above the threshold; [counted] is 0. */
        data class Pass(val counted: Int) : Verdict

        /** [counted] undismissed findings at or above the threshold. */
        data class Fail(val counted: Int) : Verdict

        /** The report cannot be trusted to say "none": the CLI exits 2, never 0. */
        data class CannotJudge(val why: String) : Verdict
    }

    fun parse(text: String?): Result<FindingSeverity> =
        FindingSeverity.parse(text)?.let { Result.success(it) }
            ?: Result.failure(
                IllegalArgumentException(
                    "unknown --fail-on '${text.orEmpty()}'. Use one of: " +
                        FindingSeverity.entries.joinToString(", ") { it.key } + ".",
                ),
            )

    fun judge(report: FindingsReport, failOn: FindingSeverity): Verdict {
        // In a one-shot process the "last good settings" a broken config.json falls back to are the
        // defaults, so a team's severity overrides and rules would be silently ignored.
        report.configError?.let {
            return Verdict.CannotJudge("config.json could not be read: $it; the gate does not judge on default settings")
        }
        // An empty session is what a test that stopped its sink before delivery leaves behind, and a
        // gate that passed it would be a detector that cannot detect.
        if (report.total == 0) return Verdict.CannotJudge("the session holds no calls; nothing was judged")
        val counted = counted(report, failOn)
        return if (counted > 0) Verdict.Fail(counted) else Verdict.Pass(0)
    }

    /**
     * What the CLI prints to stderr: a header, the listed findings at or above the threshold, how many
     * the limit left out, and each category's coverage line. Built from the severity, rule id, route,
     * message and evidence's location, path and masked hint only: never anything else on a row.
     */
    fun lines(report: FindingsReport, failOn: FindingSeverity, verdict: Verdict): List<String> {
        if (verdict is Verdict.CannotJudge) return listOf("inspector: cannot judge ${report.sessionId}: ${verdict.why}")
        val counted = counted(report, failOn)
        val noun = if (counted == 1) "finding" else "findings"
        val out = ArrayList<String>()
        out += "inspector: $counted $noun at or above ${failOn.key} in ${report.sessionId} (rules ${report.ruleVersion})"
        val listed = report.findings.filter { it.dismissed == null && atOrAbove(it.severity, failOn) }
        for (finding in listed) out += "  " + line(finding)
        if (counted > listed.size) out += "  and ${counted - listed.size} more"
        report.coverage?.values?.forEach { category ->
            ((category as? JsonObject)?.get("line") as? JsonPrimitive)?.takeIf { it.isString }?.let { out += "  ${it.content}" }
        }
        return out
    }

    private fun counted(report: FindingsReport, failOn: FindingSeverity): Int =
        FindingSeverity.entries.filter { it.ordinal <= failOn.ordinal }.sumOf {
            when (it) {
                FindingSeverity.HIGH -> report.high
                FindingSeverity.MEDIUM -> report.medium
                FindingSeverity.INFO -> report.info
            }
        }

    private fun atOrAbove(severity: String, failOn: FindingSeverity): Boolean =
        FindingSeverity.parse(severity)?.let { it.ordinal <= failOn.ordinal } ?: false

    private fun line(finding: Finding): String {
        val head = listOfNotNull(finding.severity, finding.ruleId, finding.route).joinToString(" ")
        val evidence = finding.evidence
            .map { e -> listOfNotNull(e.location, e.path, e.masked).joinToString(" ") }
            .filter { it.isNotEmpty() }
            .distinct()
        val shown = evidence.take(MAX_EVIDENCE_SHOWN).joinToString(", ")
        val more = if (evidence.size > MAX_EVIDENCE_SHOWN) ", …" else ""
        // Messages are sentences; the evidence follows the last clause, not a full stop.
        return "$head — ${finding.message.removeSuffix(".")}" + if (shown.isEmpty()) "" else "; evidence: $shown$more"
    }

    /** A CI log line per finding, not the page: the report on stdout holds every evidence line. */
    private const val MAX_EVIDENCE_SHOWN = 3
}

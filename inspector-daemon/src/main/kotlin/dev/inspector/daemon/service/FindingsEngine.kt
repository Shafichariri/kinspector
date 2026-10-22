package dev.inspector.daemon.service

import dev.inspector.daemon.archive.FindingRuleCatalog
import dev.inspector.daemon.archive.FindingSettings
import dev.inspector.daemon.archive.FindingSettingsSource
import dev.inspector.daemon.archive.FindingSeverity
import dev.inspector.daemon.archive.RouteTemplates
import dev.inspector.model.NetworkTransaction
import java.nio.file.Path
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Every family of findings rules. */
object FindingSources {
    /** The one registration point. A family adds one line. */
    fun all(settings: FindingSettings): List<FindingSource> = listOf(PaymentSource(settings.payment))
}

/**
 * Everything around a [FindingSource]: which rules are on, the order, the caps, the masking check,
 * the category and the limit. Owned here so no family does them twice or differently, and served by
 * one route, one MCP tool, the CLI and the exports.
 *
 * [sourcesFor] is [FindingSources.all] outside tests; a test passes its own sources through it.
 */
class FindingsEngine internal constructor(
    private val settingsSource: FindingSettingsSource,
    private val routes: RouteTemplates,
    private val sourcesFor: (FindingSettings) -> List<FindingSource>,
    private val log: (String) -> Unit,
) {
    constructor(settingsSource: FindingSettingsSource, routes: RouteTemplates) :
        this(settingsSource, routes, FindingSources::all, { System.err.println("inspector: $it") })

    /** The settings as of one read, the sources built from them, and the version they make. */
    class Rules internal constructor(
        val settings: FindingSettings,
        val configError: String?,
        val sources: List<FindingSource>,
        val ruleVersion: String,
    )

    /** One fold: a session, and an accumulator per source over it. */
    class Fold internal constructor(
        internal val rules: Rules,
        internal val parts: List<Pair<FindingSource, Pair<FindingSession, FindingAccumulator>>>,
    ) {
        fun add(row: NetworkTransaction) = parts.forEach { (_, part) -> part.second.add(row) }
    }

    /** What [collect] keeps between reads: every finding, in order, with per-rule counts and coverage. */
    class Collected internal constructor(
        internal val findings: List<Finding>,
        internal val coverage: Map<String, JsonObject>,
    )

    private var lastRules: Rules? = null

    /** Ids already complained about, so a bad source is logged once per rule, not once per read. */
    private val logged = HashSet<String>()

    /** The rules to use now. Rebuilds the sources only when the settings changed. */
    @Synchronized
    fun current(): Rules {
        val (settings, error) = settingsSource.current()
        val last = lastRules
        if (last != null && last.settings == settings) {
            return if (last.configError == error) last else Rules(settings, error, last.sources, last.ruleVersion)
        }
        val sources = sourcesFor(settings)
        return Rules(settings, error, sources, ruleVersion(sources, settings)).also { lastRules = it }
    }

    /** First 8 hex over the sources' versions, the canonical settings and the route templates. */
    internal fun ruleVersion(sources: List<FindingSource>, settings: FindingSettings): String =
        FindingSession.sha256Hex(
            sources.joinToString("\n") { it.version } + "\n" + settings.canonical() + "\n" + routes.entries.joinToString("\n"),
        ).take(8)

    /** A fresh fold of one session under [rules]. */
    fun fold(rules: Rules, sessionDir: Path, readBody: (String, String) -> ByteArray?): Fold =
        Fold(rules, rules.sources.map { source ->
            val session = FindingSession(sessionDir, routes, rules.settings, readBody)
            source to (session to source.accumulator(session))
        })

    /**
     * Every finding the fold holds, after the checks no source is trusted with: a disabled rule's
     * findings and any under an id the source instance did not register are dropped, a hint that is
     * not masked is replaced, the caps hold, and the order is severity, then the first member's time,
     * then rule id, then id.
     */
    fun collect(fold: Fold): Collected {
        val kept = ArrayList<Pair<Finding, Long>>()
        val coverage = LinkedHashMap<String, JsonObject>()
        for ((source, part) in fold.parts) {
            val (session, accumulator) = part
            val registered = source.rules.mapTo(HashSet()) { it.id }
            for (finding in accumulator.findings()) {
                if (finding.ruleId !in registered) {
                    once("unregistered:${finding.ruleId}") {
                        "dropped a finding under '${finding.ruleId}', which the ${source.category} source did not register"
                    }
                    continue
                }
                if (!session.enabled(finding.ruleId)) continue
                kept += checked(finding) to (session.firstMono[finding.id] ?: Long.MAX_VALUE)
            }
            accumulator.coverage()?.let { cover ->
                if ((cover["line"] as? JsonPrimitive)?.isString == true) {
                    coverage[source.category] = cover
                } else {
                    once("coverage:${source.category}") { "dropped the ${source.category} coverage: it has no string 'line'" }
                }
            }
        }
        val ordered = kept.sortedWith(
            compareBy<Pair<Finding, Long>> { rank(it.first.severity) }
                .thenBy { it.second }
                .thenBy { it.first.ruleId }
                .thenBy { it.first.id },
        ).map { it.first }
        return Collected(ordered, coverage)
    }

    /**
     * The reply: [collected] narrowed to [category] (null is every one) and cut to [limit], with every
     * registered rule listed and counted in the same scope.
     */
    fun report(
        sessionId: String,
        rules: Rules,
        collected: Collected,
        total: Int,
        category: String?,
        limit: Int,
    ): FindingsReport {
        val sources = rules.sources.filter { category == null || it.category == category }
        val inScope = collected.findings.filter { category == null || it.category == category }
        val counts = inScope.groupingBy { it.ruleId }.eachCount()
        val rows = sources.flatMap { source ->
            source.rules.map { rule ->
                val override = rules.settings.rules[rule.id]
                FindingRuleRow(
                    id = rule.id,
                    category = rule.category,
                    categoryTitle = rule.categoryTitle,
                    title = rule.title,
                    summary = rule.summary,
                    severity = (override?.severity ?: rule.defaultSeverity).key,
                    defaultSeverity = rule.defaultSeverity.key,
                    enabled = override?.enabled ?: true,
                    count = counts[rule.id] ?: 0,
                    note = source.note(rule.id),
                )
            }
        }
        val coverage = collected.coverage.filterKeys { category == null || it == category }
        return FindingsReport(
            sessionId = sessionId,
            ruleVersion = rules.ruleVersion,
            configError = rules.configError,
            category = category,
            total = total,
            findingCount = inScope.size,
            high = inScope.count { it.severity == FindingSeverity.HIGH.key },
            medium = inScope.count { it.severity == FindingSeverity.MEDIUM.key },
            info = inScope.count { it.severity == FindingSeverity.INFO.key },
            rules = rows,
            findings = if (limit >= inScope.size) inScope else inScope.subList(0, limit),
            coverage = if (coverage.isEmpty()) null else JsonObject(coverage),
        )
    }

    /**
     * The whole session's report from rows a caller already holds, for the exports: no category, no
     * limit, and the same rules and settings a findings read would use now.
     */
    fun compute(sessionId: String, sessionDir: Path, rows: List<NetworkTransaction>, readBody: (String, String) -> ByteArray?): FindingsReport {
        val rules = current()
        val fold = fold(rules, sessionDir, readBody)
        rows.forEach(fold::add)
        return report(sessionId, rules, collect(fold), rows.size, null, Int.MAX_VALUE)
    }

    /** The finding with the caps applied again and every hint that is not masked replaced. */
    private fun checked(finding: Finding): Finding {
        var replaced = false
        val evidence = finding.evidence.take(Finding.MAX_EVIDENCE).map { line ->
            val hint = line.masked
            if (hint == null || FindingMask.isMasked(hint)) line else line.copy(masked = FindingMask.DOTS).also { replaced = true }
        }
        if (replaced) once("masked:${finding.ruleId}") { "replaced a hint under '${finding.ruleId}' that was not masked" }
        return finding.copy(
            txnIds = finding.txnIds.take(Finding.MAX_TXN_IDS),
            callIds = finding.callIds.take(Finding.MAX_TXN_IDS),
            evidence = evidence,
        )
    }

    private fun once(key: String, message: () -> String) {
        val first = synchronized(logged) { logged.add(key) }
        if (first) log(message())
    }

    companion object {
        /** Absent or blank is every category; anything else unknown is null, for the caller's 400. */
        fun parseCategory(text: String?): Result<String?> = when {
            text.isNullOrBlank() -> Result.success(null)
            text in FindingRuleCatalog.CATEGORIES -> Result.success(text)
            else -> Result.failure(IllegalArgumentException(unknownCategory(text)))
        }

        fun unknownCategory(text: String): String =
            "unknown category '$text'. Use one of: ${FindingRuleCatalog.CATEGORIES.joinToString(", ")}."

        private fun rank(severity: String): Int =
            FindingSeverity.parse(severity)?.ordinal ?: FindingSeverity.entries.size
    }
}

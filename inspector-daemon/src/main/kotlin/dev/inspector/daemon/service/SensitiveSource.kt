package dev.inspector.daemon.service

import dev.inspector.daemon.archive.CustomValueRule
import dev.inspector.daemon.archive.FindingRuleCatalog
import dev.inspector.daemon.archive.FindingRuleInfo
import dev.inspector.daemon.archive.FindingSeverity
import dev.inspector.daemon.archive.RouteKey
import dev.inspector.daemon.archive.SensitiveConfig
import dev.inspector.daemon.archive.SensitiveSettings
import dev.inspector.model.NetworkTransaction
import java.net.URLDecoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Sensitive data in a session's calls, found when findings are read and never on ingest. Detection
 * never changes what capture keeps: redaction stays off by default for the reason `capture.md` gives,
 * and this is a smoke detector beside it.
 *
 * [rules] is per instance: the shipped `sensitive.*` rules and one `sensitive.custom.<id>` per value
 * rule in `findings.sensitive.valueRules`, so the engine keeps a custom rule's findings and the reply
 * lists it as checked.
 */
class SensitiveSource(private val settings: SensitiveSettings) : FindingSource {
    override val category: String = FindingRuleCatalog.SENSITIVE

    override val version: String = "1"

    override val rules: List<FindingRuleInfo> = FindingRuleCatalog.SENSITIVE_RULES + settings.valueRules.map { custom ->
        FindingRuleInfo(
            custom.ruleId, category, FindingRuleCatalog.CARD_NUMBER.categoryTitle, custom.label,
            "your value rule in config.json", custom.severity, custom.enabled,
        )
    }

    override fun accumulator(session: FindingSession): FindingAccumulator = SensitiveAccumulator(session, settings, rules)
}

/**
 * One session's sensitive-data fold. It keeps, per group, light row references and at most
 * [MAX_EVIDENCE] masked evidence lines: never a row, a body or a matched value, so the cache holds
 * nothing the detector found. Each row's bodies are read, scanned and dropped inside [add].
 */
internal class SensitiveAccumulator(
    private val session: FindingSession,
    private val settings: SensitiveSettings,
    rules: List<FindingRuleInfo>,
) : FindingAccumulator {

    private val ruleById = rules.associateBy { it.id }
    private val names = FieldNames(settings)
    private val customs = settings.valueRules.filter { it.enabled }
    private val fielded = customs.filter { it.fields.isNotEmpty() }
    private val groups = LinkedHashMap<String, Group>()

    private var rows = 0
    private var bodies = 0
    private var scanned = 0
    private var notCaptured = 0
    private var truncated = 0
    private var binary = 0
    private var overScanLimit = 0
    private var groupLimitReached = false

    private class Group(
        val rule: FindingRuleInfo,
        val route: RouteKey,
        val location: String,
        val path: String?,
        val key: String,
        val message: String,
        var severity: FindingSeverity,
    ) {
        val rows = ArrayList<FindingRow>()
        val evidence = ArrayList<Pair<FindingRow, Evidence>>()
    }

    /** What one location and path of one row showed: the strongest claim wins. */
    private class Candidate(
        val rule: FindingRuleInfo,
        val priority: Int,
        val masked: String,
        val note: String? = null,
        /** A severity the raises never move: test card numbers, and `http` to loopback. */
        val fixed: FindingSeverity? = null,
        /** The base before the raises, when the rule's own is not it. */
        val base: FindingSeverity? = null,
    )

    /** One row's candidates, by location and concrete path. */
    private class RowScan(val txn: NetworkTransaction, val ref: FindingRow, val route: RouteKey) {
        val best = LinkedHashMap<Pair<String, String?>, Candidate>()
        val transport = LinkedHashMap<Pair<String, String?>, Candidate>()

        fun offer(location: String, path: String?, candidate: Candidate) {
            val key = location to path
            val held = best[key]
            if (held == null || candidate.priority < held.priority) best[key] = candidate
        }
    }

    override fun add(row: NetworkTransaction) {
        rows++
        val scan = RowScan(row, FindingRow.of(row), routeOf(row))
        scanUrl(scan)
        scanQuery(scan)
        scanBody(scan, BodySide.REQ)
        scanBody(scan, BodySide.RES)
        scanTransport(scan)
        for ((where, candidate) in scan.best) record(scan, where.first, where.second, candidate)
        for ((where, candidate) in scan.transport) record(scan, where.first, where.second, candidate)
    }

    /**
     * The row's route, with any literal segment that matches a value rule written `•`. The route
     * heuristic folds digits and long tokens to `{id}`, but a JWT holds dots and an IBAN has no
     * lowercase, so either stays literal, and a finding's route would print the very value it flags.
     * Asked of the route rule directly rather than the session's memo, whose key is the raw path.
     */
    private fun routeOf(row: NetworkTransaction): RouteKey {
        val key = session.routes.keyOf(row)
        val segments = key.route.text.split('/')
        if (segments.none { it.isNotEmpty() && !it.startsWith('{') && SensitiveValues.matchesAny(decode(it, plus = false), customs) }) return key
        val text = segments.joinToString("/") { segment ->
            if (segment.isNotEmpty() && !segment.startsWith('{') && SensitiveValues.matchesAny(decode(segment, plus = false), customs)) {
                SensitiveMask.KEY
            } else {
                segment
            }
        }
        return key.copy(route = key.route.copy(text = text))
    }

    // --- locations ------------------------------------------------------------------------------

    private fun scanUrl(scan: RowScan) {
        for (segment in scan.txn.path.split('/')) {
            if (segment.isEmpty()) continue
            valueCandidate(decode(segment, plus = false), allowJwt = true, key = null)?.let { scan.offer(URL, null, it) }
        }
    }

    private fun scanQuery(scan: RowScan) {
        val query = scan.txn.query ?: return
        for ((name, value) in pairs(query)) {
            if ("query:${name.lowercase()}" in scan.txn.redacted || value == PLACEHOLDER) continue
            pairCandidates(scan, QUERY, name, value, inUrl = true)
        }
    }

    /** A query or form pair: its value by the value rules, its name by the name rules. */
    private fun pairCandidates(scan: RowScan, location: String, name: String, value: String, inUrl: Boolean) {
        if (value.isEmpty()) return
        val path = SensitiveMask.pathKey(name, customs)
        valueCandidate(value, allowJwt = inUrl, key = name)?.let { scan.offer(location, path, it) }
        if (inUrl && SensitiveValues.isSecretName(name) && enabled(FindingRuleCatalog.SECRET_IN_URL)) {
            scan.offer(location, path, Candidate(FindingRuleCatalog.SECRET_IN_URL, PRIORITY_SECRET, SensitiveMask.hint(SensitiveKind.SECRET, value)))
        }
        fieldCandidate(name)?.let { scan.offer(location, path, it) }
    }

    private fun scanBody(scan: RowScan, side: BodySide) {
        val txn = scan.txn
        val location = if (side == BodySide.REQ) REQ_BODY else RES_BODY
        val state = BodyEvidence.state(txn, side)
        if (state == BodyState.EMPTY) return
        bodies++
        if (state == BodyState.OMITTED || state == BodyState.MISSING) {
            notCaptured++
            return
        }
        val raw = session.readBody(txn.id, side.key) ?: run {
            notCaptured++
            return
        }
        // A prefix is scanned for values, never for names: a value found in it is present, while a
        // name not found there proves nothing. The diff's rule is the opposite for the opposite reason.
        if (state == BodyState.TRUNCATED || raw.size > SCAN_LIMIT) {
            if (state == BodyState.TRUNCATED) truncated++ else overScanLimit++
            val note = if (state == BodyState.TRUNCATED) "truncated: path unknown" else "over the 1 MiB scan limit: path unknown"
            scanText(scan, location, String(raw, 0, minOf(raw.size, SCAN_LIMIT), Charsets.UTF_8), note)
            return
        }
        val type = (if (side == BodySide.REQ) txn.reqContentType else txn.resContentType)?.lowercase()
        val text = raw.decodeUtf8OrNull()
        when {
            state == BodyState.COMPLETE -> {
                if (text == null) {
                    binary++
                    return
                }
                scanned++
                val element = runCatching { Json.parseToJsonElement(text) }.getOrNull()
                if (element == null) scanText(scan, location, text, "not valid JSON: path unknown")
                else scanJson(scan, location, element, JsonPaths.redactedPaths(txn, side))
            }
            text == null -> binary++
            type != null && "x-www-form-urlencoded" in type -> {
                scanned++
                for ((name, value) in pairs(text)) pairCandidates(scan, location, name, value, inUrl = false)
            }
            type == null || type.startsWith("text/") || "xml" in type -> {
                scanned++
                scanText(scan, location, text, null)
            }
            else -> binary++
        }
    }

    private fun scanText(scan: RowScan, location: String, text: String, note: String?) {
        valueCandidate(text, allowJwt = false, key = null, note = note)?.let { scan.offer(location, null, it) }
    }

    /**
     * Every leaf of a parsed body. A key that itself matches a value rule is written `•` in the path,
     * worked out from each object as the walk reaches it, because a key holding a JWT holds dots and
     * cannot be found again by splitting the path.
     */
    private fun scanJson(scan: RowScan, location: String, root: JsonElement, redacted: Set<String>) {
        val masked = HashMap<String, String>().apply { put("$", "$") }
        val keyOf = HashMap<String, String>()
        val under = HashMap<String, Set<CustomValueRule>>()
        JsonPaths.walk(root) { path, element ->
            val shown = masked[path] ?: path
            val inherited = under[path].orEmpty()
            when (element) {
                is JsonObject -> for (key in element.keys) {
                    val child = "$path.$key"
                    masked[child] = "$shown.${SensitiveMask.pathKey(key, customs)}"
                    keyOf[child] = key
                    if (fielded.isNotEmpty()) {
                        val normal = SensitiveConfig.normalise(key)
                        under[child] = inherited + fielded.filter { normal in it.fields }
                    }
                }
                is JsonArray -> for (index in element.indices) {
                    val child = "$path[$index]"
                    masked[child] = "$shown[$index]"
                    keyOf[path]?.let { keyOf[child] = it }
                    if (inherited.isNotEmpty()) under[child] = inherited
                }
                JsonNull -> Unit
                is JsonPrimitive -> leaf(scan, location, path, shown, keyOf[path], element, inherited, redacted)
            }
        }
    }

    private fun leaf(
        scan: RowScan,
        location: String,
        path: String,
        shown: String,
        key: String?,
        value: JsonPrimitive,
        fieldsHit: Set<CustomValueRule>,
        redacted: Set<String>,
    ) {
        if (JsonPaths.isAtOrUnder(path, redacted)) return
        val content = value.content
        if (content.isEmpty() || (value.isString && content == PLACEHOLDER)) return
        if (value.isString) {
            valueCandidate(content, allowJwt = false, key = key, fieldsHit = fieldsHit)?.let { scan.offer(location, shown, it) }
        } else if (enabled(FindingRuleCatalog.CARD_NUMBER) && SensitiveValues.cardInJsonNumber(content, key)) {
            scan.offer(location, shown, cardCandidate(content))
        }
        if (key != null) fieldCandidate(key)?.let { scan.offer(location, shown, it) }
    }

    /**
     * Credentials sent over plain `http`: the transport, not the content, so these are reported
     * beside the value and name findings, not instead of them.
     */
    private fun scanTransport(scan: RowScan) {
        val txn = scan.txn
        if (!txn.scheme.equals("http", ignoreCase = true) || !enabled(FindingRuleCatalog.CREDENTIALS_OVER_HTTP)) return
        val rule = FindingRuleCatalog.CREDENTIALS_OVER_HTTP
        val (fixed, base) = when (SensitiveValues.hostClass(txn.host)) {
            HostClass.LOCAL -> FindingSeverity.INFO to null
            HostClass.PRIVATE -> null to lower(session.severity(rule.id))
            HostClass.PUBLIC -> null to null
        }
        fun add(location: String, path: String?, masked: String) {
            scan.transport[location to path] = Candidate(rule, 0, masked, fixed = fixed, base = base)
        }
        for ((name, values) in txn.reqHeaders) {
            val lower = name.lowercase()
            if ("header:$lower" in txn.redacted) continue
            val value = values.firstOrNull().orEmpty()
            when {
                lower == "authorization" || lower == "proxy-authorization" -> add(REQ_HEADER, lower, SensitiveMask.hint(SensitiveKind.AUTHORIZATION, value))
                lower == "cookie" || SensitiveValues.isSecretName(name) -> add(REQ_HEADER, lower, SensitiveMask.hint(SensitiveKind.SECRET, value))
            }
        }
        for ((name, _) in txn.resHeaders) {
            val lower = name.lowercase()
            if (lower == "set-cookie" && "header:$lower" !in txn.redacted) add(RES_HEADER, lower, FindingMask.DOTS)
        }
        val carried = setOf(FindingRuleCatalog.SECRET_IN_URL.id, FindingRuleCatalog.JWT_IN_URL.id, FindingRuleCatalog.FIELD_SECRET.id)
        for ((where, candidate) in scan.best) {
            if (candidate.rule.id in carried && where.first in REQUEST_SIDE) add(where.first, where.second, candidate.masked)
        }
    }

    // --- rules ----------------------------------------------------------------------------------

    /** The strongest value rule that matches in [text], or null. Stops at the first that does. */
    private fun valueCandidate(
        text: String,
        allowJwt: Boolean,
        key: String?,
        note: String? = null,
        fieldsHit: Set<CustomValueRule> = emptySet(),
    ): Candidate? {
        if (enabled(FindingRuleCatalog.CARD_NUMBER)) {
            SensitiveValues.cards(text).firstOrNull()?.let { m ->
                return cardCandidate(text.substring(m.start, m.end), note)
            }
        }
        if (enabled(FindingRuleCatalog.IBAN)) {
            SensitiveValues.ibans(text).firstOrNull()?.let { m ->
                return Candidate(FindingRuleCatalog.IBAN, PRIORITY_VALUE, SensitiveMask.hint(SensitiveKind.IBAN, text.substring(m.start, m.end)), note)
            }
        }
        if (allowJwt && enabled(FindingRuleCatalog.JWT_IN_URL)) {
            SensitiveValues.jwts(text).firstOrNull()?.let { m ->
                return Candidate(FindingRuleCatalog.JWT_IN_URL, PRIORITY_VALUE, SensitiveMask.hint(SensitiveKind.JWT, text.substring(m.start, m.end)), note)
            }
        }
        val keyNormal = key?.let(SensitiveConfig::normalise)
        for (custom in customs) {
            if (custom.fields.isNotEmpty() && custom !in fieldsHit && keyNormal !in custom.fields) continue
            SensitiveValues.shapes(text, custom).firstOrNull()?.let { m ->
                val rule = ruleById.getValue(custom.ruleId)
                return Candidate(rule, PRIORITY_VALUE, SensitiveMask.hint(SensitiveKind.CUSTOM, text.substring(m.start, m.end)), note)
            }
        }
        if (enabled(FindingRuleCatalog.EMAIL)) {
            SensitiveValues.emails(text).firstOrNull()?.let { m ->
                return Candidate(FindingRuleCatalog.EMAIL, PRIORITY_CONTACT, SensitiveMask.hint(SensitiveKind.EMAIL, text.substring(m.start, m.end)), note)
            }
        }
        if (enabled(FindingRuleCatalog.PHONE)) {
            SensitiveValues.phones(text).firstOrNull()?.let { m ->
                return Candidate(FindingRuleCatalog.PHONE, PRIORITY_CONTACT, SensitiveMask.hint(SensitiveKind.PHONE, text.substring(m.start, m.end)), note)
            }
        }
        return null
    }

    private fun cardCandidate(value: String, note: String? = null): Candidate {
        val test = SensitiveValues.isTestCard(value.filter { it in '0'..'9' })
        return Candidate(
            FindingRuleCatalog.CARD_NUMBER, PRIORITY_VALUE, SensitiveMask.hint(SensitiveKind.CARD, value),
            note = if (test) listOfNotNull("a published test card number", note).joinToString("; ") else note,
            fixed = if (test) FindingSeverity.INFO else null,
        )
    }

    private fun fieldCandidate(name: String): Candidate? {
        val rule = when (names.classOf(name) ?: return null) {
            FieldClass.SECRET -> FindingRuleCatalog.FIELD_SECRET
            FieldClass.PAYMENT -> FindingRuleCatalog.FIELD_PAYMENT
            FieldClass.IDENTITY -> FindingRuleCatalog.FIELD_IDENTITY
            FieldClass.NAME -> FindingRuleCatalog.FIELD_NAME
        }
        return if (enabled(rule)) Candidate(rule, PRIORITY_FIELD, FindingMask.DOTS) else null
    }

    private fun enabled(rule: FindingRuleInfo): Boolean = session.settings.rules[rule.id]?.enabled ?: rule.defaultEnabled

    // --- groups ---------------------------------------------------------------------------------

    /**
     * Severity rises one step for a value in the URL, and one for a request to a host outside a
     * non-empty `firstPartyHosts`, capped at high; never for where data came from, because the host
     * of a response sent it to the app. An empty list raises nothing: no rule can tell the backend
     * from an analytics host without guessing.
     */
    private fun record(scan: RowScan, location: String, path: String?, candidate: Candidate) {
        val txn = scan.txn
        val elsewhere = location in REQUEST_SIDE && settings.firstPartyHosts.isNotEmpty() && !settings.firstParty(txn.host)
        val severity = candidate.fixed ?: run {
            var steps = 0
            if (location == URL || location == QUERY) steps++
            if (elsewhere) steps++
            raise(candidate.base ?: baseOf(candidate.rule), steps)
        }
        val groupPath = path?.let(JsonPaths::group)
        val label = session.routeLabel(scan.route)
        val key = "$label\u0000$location\u0000${groupPath.orEmpty()}"
        val groupKey = "${candidate.rule.id}\u0000$key"
        val group = groups[groupKey] ?: run {
            if (groups.size >= MAX_GROUPS) {
                groupLimitReached = true
                return
            }
            val message = "${candidate.rule.title} in the ${WORDS.getValue(location)}" +
                if (elsewhere) ", sent to a host outside firstPartyHosts." else "."
            Group(candidate.rule, scan.route, location, groupPath, key, message, severity).also { groups[groupKey] = it }
        }
        if (severity.ordinal < group.severity.ordinal) group.severity = severity
        if (group.rows.lastOrNull()?.id != scan.ref.id) group.rows += scan.ref
        val line = Evidence(scan.ref.id, location, path, candidate.masked, candidate.note)
        if (group.evidence.size < MAX_EVIDENCE || FindingSession.TIMELINE.compare(scan.ref, group.evidence.last().first) < 0) {
            group.evidence += scan.ref to line
            group.evidence.sortWith { a, b -> FindingSession.TIMELINE.compare(a.first, b.first) }
            while (group.evidence.size > MAX_EVIDENCE) group.evidence.removeAt(group.evidence.size - 1)
        }
    }

    private fun baseOf(rule: FindingRuleInfo): FindingSeverity =
        if (rule.id.startsWith(SensitiveConfig.CUSTOM_PREFIX)) session.settings.rules[rule.id]?.severity ?: rule.defaultSeverity
        else session.severity(rule.id)

    override fun findings(): List<Finding> = groups.values.map { group ->
        session.finding(
            rule = group.rule,
            severity = group.severity,
            groupKey = group.key,
            route = group.route,
            rows = group.rows,
            message = group.message,
            evidence = group.evidence.map { it.second },
            location = group.location,
            path = group.path,
        )
    }

    override fun coverage(): JsonObject = buildJsonObject {
        put("rows", rows)
        put("bodies", bodies)
        put("scanned", scanned)
        put("notCaptured", notCaptured)
        put("truncated", truncated)
        put("binary", binary)
        put("overScanLimit", overScanLimit)
        put("groupLimitReached", groupLimitReached)
        put("firstPartyHosts", settings.firstPartyHosts.size)
        put("line", line())
    }

    /** What could not be scanned, zero counts left out, so "no findings" never stands in for "not looked". */
    private fun line(): String {
        val parts = ArrayList<String>()
        parts += when {
            bodies == 0 -> "No bodies to scan"
            scanned == bodies -> "Scanned all $bodies bodies"
            else -> "Scanned $scanned of $bodies bodies"
        }
        if (notCaptured > 0) parts += "$notCaptured not captured"
        if (truncated > 0) parts += "$truncated truncated (values only)"
        if (binary > 0) parts += "$binary binary"
        if (overScanLimit > 0) parts += "$overScanLimit over the 1 MiB scan limit (values only)"
        if (groupLimitReached) parts += "stopped adding findings at $MAX_GROUPS"
        if (settings.firstPartyHosts.isEmpty()) parts += "no first-party hosts set, so nothing is raised for where data was sent"
        return parts.joinToString(" · ")
    }

    private companion object {
        const val URL = "url"
        const val QUERY = "query"
        const val REQ_BODY = "reqBody"
        const val RES_BODY = "resBody"
        const val REQ_HEADER = "reqHeader"
        const val RES_HEADER = "resHeader"
        val REQUEST_SIDE = setOf(URL, QUERY, REQ_BODY, REQ_HEADER)
        val WORDS = mapOf(
            URL to "URL path", QUERY to "URL query", REQ_BODY to "request body", RES_BODY to "response body",
            REQ_HEADER to "request headers", RES_HEADER to "response headers",
        )

        const val PRIORITY_VALUE = 0
        const val PRIORITY_CONTACT = 1
        const val PRIORITY_SECRET = 2
        const val PRIORITY_FIELD = 3

        const val MAX_GROUPS = 1000
        const val MAX_EVIDENCE = 5
        const val SCAN_LIMIT = 1024 * 1024

        /** The Redactor's placeholder: a value equal to it was replaced at capture. */
        const val PLACEHOLDER = JsonPaths.PLACEHOLDER

        fun raise(base: FindingSeverity, steps: Int): FindingSeverity =
            FindingSeverity.entries[maxOf(0, base.ordinal - steps)]

        fun lower(base: FindingSeverity): FindingSeverity =
            FindingSeverity.entries[minOf(FindingSeverity.entries.size - 1, base.ordinal + 1)]

        /** `name=value` pairs, percent-decoded, `+` as a space, as a form or query encodes them. */
        fun pairs(text: String): List<Pair<String, String>> = text.split('&').mapNotNull { part ->
            if (part.isEmpty()) return@mapNotNull null
            val name = decode(part.substringBefore('='), plus = true)
            val value = if ('=' in part) decode(part.substringAfter('='), plus = true) else ""
            name to value
        }

        fun decode(text: String, plus: Boolean): String {
            val source = if (plus) text else text.replace("+", "%2B")
            return runCatching { URLDecoder.decode(source, Charsets.UTF_8) }.getOrDefault(text)
        }
    }
}

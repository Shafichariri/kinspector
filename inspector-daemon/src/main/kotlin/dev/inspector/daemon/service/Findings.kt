package dev.inspector.daemon.service

import dev.inspector.daemon.archive.FindingRuleCatalog
import dev.inspector.daemon.archive.FindingRuleInfo
import dev.inspector.daemon.archive.FindingSettings
import dev.inspector.daemon.archive.FindingSeverity
import dev.inspector.daemon.archive.RouteKey
import dev.inspector.daemon.archive.RouteTemplates
import dev.inspector.model.NetworkTransaction
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/*
 * The findings model: encode-only types, never decoded and never archived. Strings rather than enums
 * for category, severity and location, so an older page or agent can still print a value it does not
 * know. A null field is absent from the JSON, and no non-null field has a default. The findings JSON
 * only grows: a field is added, never removed or repurposed.
 */

/** One thing a rule flagged, about one or more calls. Nothing in it is a captured value. */
@Serializable
data class Finding(
    /** First 12 hex of SHA-256 over `ruleId NUL groupKey`: stays put when a row joins the group. */
    val id: String,
    val ruleId: String,
    val category: String,
    /** After any `findings.rules` override and any raise by the source. */
    val severity: String,
    /** The `RouteKey` label, `POST https://api.example.invalid/v1/payments`; port only when not the default. */
    val route: String? = null,
    /** Group level: `url`, `query`, `reqHeader`, `resHeader`, `reqBody` or `resBody`. */
    val location: String? = null,
    /** Group level, in the Redactor's syntax: `$.items[*].iban`, a query or form name, a lowercase header name. */
    val path: String? = null,
    /** Never built from a captured value. */
    val message: String,
    /** Timeline order (mono, then seq when both carry it, then id); at most [MAX_TXN_IDS]. */
    val txnIds: List<String>,
    /** Distinct, first seen in that order; at most [MAX_TXN_IDS]. */
    val callIds: List<String>,
    /** Every member, however many [txnIds] lists. */
    val txnCount: Int,
    /** At most [MAX_EVIDENCE]. */
    val evidence: List<Evidence>,
    /** The `findingDismissals` entry that set this finding aside, applied when a reply is built. */
    val dismissed: Dismissal? = null,
) {
    companion object {
        const val MAX_TXN_IDS = 200
        const val MAX_EVIDENCE = 50
    }
}

/** One line of evidence, always about one row. [masked] is the only trace of a value anywhere. */
@Serializable
data class Evidence(
    val txnId: String,
    val location: String? = null,
    val path: String? = null,
    val masked: String? = null,
    val note: String? = null,
)

@Serializable
data class Dismissal(val reason: String, val at: String? = null)

/** Every rule checked, with its count, so a 0 is read as "checked, none found" and never as "safe". */
@Serializable
data class FindingRuleRow(
    val id: String,
    val category: String,
    val categoryTitle: String,
    val title: String,
    val summary: String,
    val severity: String,
    val defaultSeverity: String,
    val enabled: Boolean,
    val count: Int,
    val note: String? = null,
)

/** What `GET …/findings`, `list_findings`, `inspector findings` and the exports return. */
@Serializable
data class FindingsReport(
    /** The resolved folder id, never `latest`. */
    val sessionId: String,
    /** 8 hex: the sources' versions, the effective `findings` settings and the route templates. */
    val ruleVersion: String,
    /** The live read's complaint about `config.json`; the last good settings are in use. */
    val configError: String? = null,
    /** As asked; absent means every category. */
    val category: String? = null,
    /** Rows read, as the traffic list counts them. */
    val total: Int,
    /** Findings in the category, before the limit, dismissed ones left out. */
    val findingCount: Int,
    /** Findings in the category a `findingDismissals` entry set aside; listed only when asked for. */
    val dismissedCount: Int,
    val high: Int,
    val medium: Int,
    val info: Int,
    /** Every rule the sources registered, in the category: source order, then each source's own. */
    val rules: List<FindingRuleRow>,
    val findings: List<Finding>,
    /** Category to what its source could and could not judge; each carries a daemon-built `line`. */
    val coverage: JsonObject? = null,
)

/** Masks for [Evidence.masked], and the predicate the engine holds every hint to. */
object FindingMask {
    const val DOTS = "••••"

    /** The most characters a hint may show that are neither a dot nor a space. */
    const val MAX_SHOWN = 10

    /** `•••• abcd`: the last [n] characters, or the dots alone for a value under 12 characters. */
    fun tail(value: String, n: Int = 4): String = if (value.length < 12) DOTS else "$DOTS ${value.takeLast(n)}"

    /** True when [hint] reads as masked: at least four dots and at most [MAX_SHOWN] other characters. */
    fun isMasked(hint: String): Boolean =
        hint.count { it == '•' } >= 4 && hint.count { it != '•' && it != ' ' } <= MAX_SHOWN
}

/**
 * One family of rules: payment safety, and later sensitive data. A family plugs in here and in
 * `FindingSources.all`, and nowhere else; the engine owns order, caps, masking, the cache and every
 * surface, so no family can get those wrong or do them twice.
 */
interface FindingSource {
    val category: String

    /** Part of `ruleVersion`. Bump it with any change to what the source reports. */
    val version: String

    /**
     * This instance's rules: its shipped catalogue entries plus any built from settings. The engine
     * drops a finding under any other id, and lists exactly these in the reply.
     */
    val rules: List<FindingRuleInfo>

    /** A fresh fold for one session. Called on a cold read and whenever `ruleVersion` changes. */
    fun accumulator(session: FindingSession): FindingAccumulator

    /** A line for [ruleId]'s row when its settings keep it from finding anything, or null. */
    fun note(ruleId: String): String? = null
}

/**
 * One session as a source sees it, and the helpers every source uses so none re-derives a rule.
 *
 * [readBody] is `SessionRepository.readBody` for this session; a body file never changes once its row
 * is written, so a source may read lazily and memoise.
 */
class FindingSession(
    val sessionDir: Path,
    val routes: RouteTemplates,
    val settings: FindingSettings,
    val readBody: (txnId: String, side: String) -> ByteArray?,
) {
    private val keyByOrigin = HashMap<OriginPath, RouteKey>()

    /** The first member's `mono` per finding id, for the engine's order. */
    internal val firstMono = HashMap<String, Long>()

    /** The `findings.rules` override, else the catalogue default (on, but for rules that ship off). */
    fun enabled(ruleId: String): Boolean =
        settings.rules[ruleId]?.enabled ?: FindingRuleCatalog.byId(ruleId)?.defaultEnabled ?: true

    /** The override, else the catalogue default. A source may raise it by its own documented rule. */
    fun severity(ruleId: String): FindingSeverity =
        settings.rules[ruleId]?.severity ?: FindingRuleCatalog.byId(ruleId)?.defaultSeverity ?: FindingSeverity.INFO

    /** [RouteTemplates.keyOf], memoised as route stats memoise it. */
    fun routeOf(txn: NetworkTransaction): RouteKey {
        val origin = OriginPath(txn.method, txn.scheme, txn.host, txn.port, txn.path)
        return keyByOrigin[origin] ?: routes.keyOf(txn).also {
            if (keyByOrigin.size < KEY_MEMO_LIMIT) keyByOrigin[origin] = it
        }
    }

    fun routeLabel(key: RouteKey): String = Companion.routeLabel(key)

    /**
     * The one way a source builds a [Finding]: the id from [groupKey], the route label, members in
     * timeline order, and the caps. [groupKey] must hold no captured value, digest or key: a short
     * prefix of a hash over a short body is still a guessing oracle.
     */
    fun finding(
        rule: FindingRuleInfo,
        severity: FindingSeverity,
        groupKey: String,
        route: RouteKey?,
        rows: List<FindingRow>,
        message: String,
        evidence: List<Evidence>,
        location: String? = null,
        path: String? = null,
    ): Finding {
        val ordered = rows.distinctBy { it.id }.sortedWith(TIMELINE)
        val id = idOf(rule.id, groupKey)
        ordered.firstOrNull()?.let { firstMono[id] = it.mono }
        return Finding(
            id = id,
            ruleId = rule.id,
            category = rule.category,
            severity = severity.key,
            route = route?.let(::routeLabel),
            location = location,
            path = path,
            message = message,
            txnIds = ordered.take(Finding.MAX_TXN_IDS).map { it.id },
            callIds = ordered.map { it.callId }.distinct().take(Finding.MAX_TXN_IDS),
            txnCount = ordered.size,
            evidence = evidence.take(Finding.MAX_EVIDENCE),
        )
    }

    private data class OriginPath(val method: String, val scheme: String, val host: String, val port: Int?, val path: String)

    companion object {
        private const val KEY_MEMO_LIMIT = 50_000

        /** Timeline order: `mono`, then `seq` when both rows carry it, then id. Never `ts`. */
        val TIMELINE: Comparator<FindingRow> = Comparator { a, b ->
            when {
                a.mono != b.mono -> a.mono.compareTo(b.mono)
                a.seq != null && b.seq != null && a.seq != b.seq -> a.seq.compareTo(b.seq)
                else -> a.id.compareTo(b.id)
            }
        }

        fun idOf(ruleId: String, groupKey: String): String = sha256Hex("$ruleId\u0000$groupKey").take(12)

        /** Scheme, host (an IPv6 literal bracketed again), the port only when not the default, route. */
        fun routeLabel(key: RouteKey): String {
            val host = if (':' in key.host) "[${key.host}]" else key.host
            val port = key.port?.let { ":$it" }.orEmpty()
            return "${key.method} ${key.scheme}://$host$port${key.route.text}"
        }

        internal fun sha256Hex(text: String): String = sha256Hex(text.toByteArray())

        internal fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

/**
 * All the builder needs of a member row: order, caps and `callIds`. Never a whole row, so a group
 * spanning a session keeps four fields per member in the cache, not its headers.
 */
data class FindingRow(val id: String, val callId: String, val mono: Long, val seq: Long?) {
    companion object {
        fun of(txn: NetworkTransaction) = FindingRow(txn.id, txn.callId, txn.mono, txn.seq)
    }
}

/** One source's fold over one session. Holds no captured value: digests and masked hints only. */
interface FindingAccumulator {
    /** Every row, in file order. */
    fun add(row: NetworkTransaction)

    /** What the rows so far show. `dismissed` is null; the framework applies dismissals. */
    fun findings(): List<Finding>

    /** `reply.coverage[category]`: what the source could not judge. Must carry a string `line`. */
    fun coverage(): JsonObject? = null
}

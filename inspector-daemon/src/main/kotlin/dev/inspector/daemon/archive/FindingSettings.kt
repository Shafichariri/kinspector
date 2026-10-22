package dev.inspector.daemon.archive

import dev.inspector.model.FilterParseException
import dev.inspector.model.FilterParser
import dev.inspector.model.NetworkTransaction
import dev.inspector.model.PathTerm
import dev.inspector.model.matches
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** A finding's severity. Declaration order is rank: the first is the most severe. */
enum class FindingSeverity(val key: String) {
    HIGH("high"),
    MEDIUM("medium"),
    INFO("info");

    companion object {
        fun parse(text: String?): FindingSeverity? = entries.firstOrNull { it.key == text }
    }
}

/**
 * One rule, as every surface names it. [id] is `<category>.<kebab-name>` and is never renamed once
 * shipped: `findings.rules` in a user's `config.json`, and the dismissals the sensitive-data item
 * adds, refer to it, and a rename would silently turn a setting off.
 */
data class FindingRuleInfo(
    val id: String,
    val category: String,
    val categoryTitle: String,
    val title: String,
    /** One sentence: what the rule flags and why it matters. */
    val summary: String,
    val defaultSeverity: FindingSeverity,
)

/**
 * The shipped rules. What `findings.rules` validates against, and so here in `archive/` beside the
 * validator. A source may also build rules from settings; those reach the engine through the source
 * instance, never through this list.
 */
object FindingRuleCatalog {
    const val PAYMENT = "payment"
    private const val PAYMENT_TITLE = "Payment safety"

    val DUPLICATE_MUTATION = FindingRuleInfo(
        "payment.duplicate-mutation", PAYMENT, PAYMENT_TITLE, "Duplicate mutation",
        "Separate calls sent the same mutation within the window with no idempotency key; a server " +
            "without one cannot tell them apart.",
        FindingSeverity.HIGH,
    )
    val KEY_REUSED_BODY_CHANGED = FindingRuleInfo(
        "payment.key-reused-body-changed", PAYMENT, PAYMENT_TITLE, "Key reused, body changed",
        "One idempotency key was sent with different request bodies; a server that honours the key " +
            "answers the second operation with the first one's result.",
        FindingSeverity.HIGH,
    )
    val RETRY_WITHOUT_KEY = FindingRuleInfo(
        "payment.retry-without-key", PAYMENT, PAYMENT_TITLE, "Automatic retry without a key",
        "A mutation was retried automatically without the same idempotency key on every attempt, so " +
            "the server cannot tell the retry from a new request.",
        FindingSeverity.HIGH,
    )
    val AMBIGUOUS_RESUBMIT = FindingRuleInfo(
        "payment.ambiguous-resubmit", PAYMENT, PAYMENT_TITLE, "Ambiguous resubmit",
        "A mutation ended in a timeout or transport failure, so the server may have processed it, " +
            "and was sent again as a new call without the same idempotency key.",
        FindingSeverity.MEDIUM,
    )
    val KEY_MISSING_PAYMENT_PATH = FindingRuleInfo(
        "payment.key-missing-payment-path", PAYMENT, PAYMENT_TITLE, "Key missing on a payment path",
        "A mutation on a path matching a configured payment pattern carried no idempotency key.",
        FindingSeverity.MEDIUM,
    )
    val RETRY_WITH_KEY = FindingRuleInfo(
        "payment.retry-with-key", PAYMENT, PAYMENT_TITLE, "Retry with a key",
        "A mutation was retried automatically with the same idempotency key on every attempt: the " +
            "safe case, shown for context.",
        FindingSeverity.INFO,
    )

    val PAYMENT_RULES: List<FindingRuleInfo> = listOf(
        DUPLICATE_MUTATION,
        KEY_REUSED_BODY_CHANGED,
        RETRY_WITHOUT_KEY,
        AMBIGUOUS_RESUBMIT,
        KEY_MISSING_PAYMENT_PATH,
        RETRY_WITH_KEY,
    )

    /** Built-ins only. Each family appends its shipped list here. */
    val ALL: List<FindingRuleInfo> = PAYMENT_RULES

    val CATEGORIES: List<String> = ALL.map { it.category }.distinct()

    private val BY_ID = ALL.associateBy { it.id }

    fun byId(id: String): FindingRuleInfo? = BY_ID[id]
}

/** One `findings.rules` entry. A null field keeps the rule's default. */
data class RuleOverride(val enabled: Boolean? = null, val severity: FindingSeverity? = null)

/**
 * `findings.payment`. A field present in the file replaces its default; [paymentPaths] and
 * [ignorePaths] are `path:` patterns, matched exactly as the Traffic filter's `path:` term matches,
 * so `path:<pattern>` there finds the same calls.
 */
data class PaymentSettings(
    val idempotencyHeaders: List<String> = DEFAULT_HEADERS,
    val paymentPaths: List<String> = DEFAULT_PAYMENT_PATHS,
    val ignorePaths: List<String> = emptyList(),
    val duplicateWindowMs: Long = DEFAULT_DUPLICATE_WINDOW_MS,
    val resubmitWindowMs: Long = DEFAULT_RESUBMIT_WINDOW_MS,
) {
    private val paymentTerms = paymentPaths.map { it to termOf(it) }
    private val ignoreTerms = ignorePaths.map(::termOf)

    /** The first payment pattern [txn]'s path matches, or null. */
    fun paymentPathMatch(txn: NetworkTransaction): String? =
        paymentTerms.firstOrNull { (_, term) -> term.matches(txn) }?.first

    fun ignored(txn: NetworkTransaction): Boolean = ignoreTerms.any { it.matches(txn) }

    companion object {
        /** Through the parser, as the Traffic filter reads `path:<pattern>`, quotes included. */
        private fun termOf(pattern: String): PathTerm =
            FilterParser.parse("path:$pattern").getOrNull() as? PathTerm ?: PathTerm(pattern)

        val DEFAULT_HEADERS = listOf("Idempotency-Key", "X-Idempotency-Key")

        /**
         * Generic words, never one backend's paths: "the defaults are generic conventions". Known
         * over-match: `charge` also matches `/recharge` and `/chargers`; `ignorePaths` is the fix.
         */
        val DEFAULT_PAYMENT_PATHS = listOf("payment", "checkout", "charge", "transfer", "refund", "payout")
        const val DEFAULT_DUPLICATE_WINDOW_MS = 3_000L

        /** A person reacting to an error takes seconds to minutes, not the duplicate window's 3 s. */
        const val DEFAULT_RESUBMIT_WINDOW_MS = 60_000L
    }
}

/**
 * The parsed `findings` key of `config.json`: which rules are on, at what severity, the payment
 * rules' settings and the sensitive-data rules' ([SensitiveConfig]).
 *
 * One reader serves both callers, so a `PUT` and a hand edit are judged by the same words:
 * `ConfigFile` refuses a value whose [problem] is not null, and the live read ([FindingSettingsSource])
 * skips each bad entry, names it, and keeps the rest. The messages are this file's own, never a
 * serializer's.
 */
data class FindingSettings(
    val rules: Map<String, RuleOverride> = emptyMap(),
    val payment: PaymentSettings = PaymentSettings(),
    val sensitive: SensitiveSettings = SensitiveSettings.DEFAULT,
) {
    /**
     * The effective settings as JSON with sorted keys and every default written out, so two files
     * that mean the same thing give the same text whatever order or spelling they used. Part of
     * `ruleVersion`.
     */
    fun canonical(): String = buildJsonObject {
        putJsonObject("payment") {
            put("duplicateWindowMs", payment.duplicateWindowMs)
            put("idempotencyHeaders", strings(payment.idempotencyHeaders))
            put("ignorePaths", strings(payment.ignorePaths))
            put("paymentPaths", strings(payment.paymentPaths))
            put("resubmitWindowMs", payment.resubmitWindowMs)
        }
        putJsonObject("rules") {
            for ((id, override) in rules.toSortedMap()) {
                if (override.enabled == null && override.severity == null) continue
                putJsonObject(id) {
                    override.enabled?.let { put("enabled", it) }
                    override.severity?.let { put("severity", it.key) }
                }
            }
        }
        put("sensitive", sensitive.canonical())
    }.toString()

    companion object {
        val DEFAULT = FindingSettings()

        private val KEYS = listOf("rules", "payment", "sensitive")
        private val RULE_KEYS = listOf("enabled", "severity")
        private val PAYMENT_KEYS =
            listOf("idempotencyHeaders", "paymentPaths", "ignorePaths", "duplicateWindowMs", "resubmitWindowMs")
        const val MAX_HEADERS = 20
        const val MAX_PATTERNS = 100
        const val MAX_PATTERN_LENGTH = 256
        const val MAX_WINDOW_MS = 600_000L
        private val TOKEN = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")

        /** What is wrong with [value] as the `findings` key, in words a person can act on, or null. */
        fun problem(value: JsonElement): String? {
            var first: String? = null
            read(value) { if (first == null) first = it }
            return first
        }

        /**
         * [value] read as the `findings` key, each bad entry passed to [report] and skipped, so the
         * rest still apply. Null or `null` is every default.
         */
        fun read(value: JsonElement?, report: (String) -> Unit): FindingSettings {
            if (value == null || value is JsonNull) return DEFAULT
            val obj = value as? JsonObject ?: run {
                report("findings must be an object with rules, payment and sensitive, for example {\"payment\": {\"paymentPaths\": [\"payment\"]}}")
                return DEFAULT
            }
            for (key in obj.keys) {
                if (key !in KEYS) report("findings has an unknown key '$key'; the keys are ${KEYS.joinToString()}")
            }
            return FindingSettings(
                rules = obj["rules"]?.let { readRules(it, report) } ?: emptyMap(),
                payment = obj["payment"]?.let { readPayment(it, report) } ?: PaymentSettings(),
                sensitive = SensitiveConfig.read(obj["sensitive"], report),
            )
        }

        private fun readRules(value: JsonElement, report: (String) -> Unit): Map<String, RuleOverride> {
            if (value is JsonNull) return emptyMap()
            val obj = value as? JsonObject ?: run {
                report("findings.rules must be an object of rule id to {\"enabled\": …, \"severity\": …}")
                return emptyMap()
            }
            val rules = LinkedHashMap<String, RuleOverride>()
            for ((id, entry) in obj) {
                if (id.startsWith(SensitiveConfig.CUSTOM_PREFIX)) {
                    // A custom rule exists only while its entry does, and its own enabled and severity
                    // are the one place it is switched and graded, so this map never validates against another.
                    report("findings.rules cannot take the custom rule '$id'; set enabled and severity on its entry in findings.sensitive.valueRules")
                    continue
                }
                if (FindingRuleCatalog.byId(id) == null) {
                    report("findings.rules has an unknown rule '$id'; the rules are ${FindingRuleCatalog.ALL.joinToString { it.id }}")
                    continue
                }
                val fields = entry as? JsonObject ?: run {
                    report("findings.rules '$id' must be an object such as {\"enabled\": false} or {\"severity\": \"medium\"}")
                    null
                } ?: continue
                for (key in fields.keys) {
                    if (key !in RULE_KEYS) report("findings.rules '$id' has an unknown key '$key'; a rule takes ${RULE_KEYS.joinToString(" and ")}")
                }
                val enabled = fields["enabled"]?.takeUnless { it is JsonNull }?.let { element ->
                    (element as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
                        ?: null.also { report("findings.rules '$id' enabled must be true or false") }
                }
                val severity = fields["severity"]?.takeUnless { it is JsonNull }?.let { element ->
                    FindingSeverity.parse((element as? JsonPrimitive)?.takeIf { it.isString }?.content)
                        ?: null.also { report("findings.rules '$id' severity must be ${FindingSeverity.entries.joinToString(", ") { it.key }}") }
                }
                rules[id] = RuleOverride(enabled, severity)
            }
            return rules
        }

        private fun readPayment(value: JsonElement, report: (String) -> Unit): PaymentSettings {
            if (value is JsonNull) return PaymentSettings()
            val obj = value as? JsonObject ?: run {
                report("findings.payment must be an object of ${PAYMENT_KEYS.joinToString()}")
                return PaymentSettings()
            }
            for (key in obj.keys) {
                if (key !in PAYMENT_KEYS) report("findings.payment has an unknown key '$key'; the keys are ${PAYMENT_KEYS.joinToString()}")
            }
            val defaults = PaymentSettings()
            return PaymentSettings(
                idempotencyHeaders = obj["idempotencyHeaders"]?.let { readHeaders(it, report) } ?: defaults.idempotencyHeaders,
                paymentPaths = obj["paymentPaths"]?.let { readPatterns("paymentPaths", it, report) } ?: defaults.paymentPaths,
                ignorePaths = obj["ignorePaths"]?.let { readPatterns("ignorePaths", it, report) } ?: defaults.ignorePaths,
                duplicateWindowMs = obj["duplicateWindowMs"]?.let { readWindow("duplicateWindowMs", it, report) }
                    ?: defaults.duplicateWindowMs,
                resubmitWindowMs = obj["resubmitWindowMs"]?.let { readWindow("resubmitWindowMs", it, report) }
                    ?: defaults.resubmitWindowMs,
            )
        }

        private fun readHeaders(value: JsonElement, report: (String) -> Unit): List<String>? {
            val name = "findings.payment.idempotencyHeaders"
            val strings = readStrings(name, value, "header names, for example [\"Idempotency-Key\"]", report) ?: return null
            if (strings.size > MAX_HEADERS) report("$name holds ${strings.size} names; the most is $MAX_HEADERS")
            val valid = strings.take(MAX_HEADERS).filter { header ->
                TOKEN.matches(header).also { ok ->
                    if (!ok) report("$name entry '$header' is not a header name (letters, digits and !#\$%&'*+.^_`|~-)")
                }
            }
            if (valid.isEmpty()) {
                report("$name needs at least one header name; without one no call can carry a key")
                return null
            }
            return valid
        }

        private fun readPatterns(field: String, value: JsonElement, report: (String) -> Unit): List<String>? {
            val name = "findings.payment.$field"
            val strings = readStrings(name, value, "path patterns, for example [\"payment\", \"/v1/*/charges\"]", report) ?: return null
            if (strings.size > MAX_PATTERNS) report("$name holds ${strings.size} patterns; the most is $MAX_PATTERNS")
            return strings.take(MAX_PATTERNS).filter { pattern ->
                val problem = patternProblem(pattern)
                if (problem != null) report("$name entry '$pattern': $problem")
                problem == null
            }
        }

        /**
         * A pattern is what `path:` takes, written without `path:`, and it must parse to exactly one
         * path term. Asked of the parser, so a pattern here and in the Traffic filter cannot differ.
         */
        private fun patternProblem(pattern: String): String? {
            if (pattern.isBlank()) return "a pattern cannot be blank"
            if (pattern.length > MAX_PATTERN_LENGTH) return "a pattern is at most $MAX_PATTERN_LENGTH characters"
            if (pattern.startsWith("path:", ignoreCase = true)) return "write the pattern without path:"
            val parsed = try {
                FilterParser.parseOrThrow("path:$pattern")
            } catch (e: FilterParseException) {
                return e.message ?: "not a path pattern"
            }
            return if (parsed is PathTerm) null else "a pattern is one path, as path: takes it, with no spaces or |"
        }

        private fun readWindow(field: String, value: JsonElement, report: (String) -> Unit): Long? {
            val primitive = value as? JsonPrimitive
            val number = primitive?.takeIf { !it.isString }?.longOrNull
            if (number == null || number !in 1..MAX_WINDOW_MS) {
                report("findings.payment.$field must be a whole number of milliseconds from 1 to $MAX_WINDOW_MS")
                return null
            }
            return number
        }

        private fun readStrings(name: String, value: JsonElement, example: String, report: (String) -> Unit): List<String>? {
            val array = value as? JsonArray ?: run {
                report("$name must be a list of $example")
                return null
            }
            return array.mapNotNull { element ->
                (element as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: null.also { report("$name entry $element must be a string") }
            }
        }

        private fun strings(values: List<String>) = buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }
    }
}

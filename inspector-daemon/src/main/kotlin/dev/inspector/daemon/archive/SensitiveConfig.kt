package dev.inspector.daemon.archive

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One character class of a [ValueShape]. */
enum class ShapeToken { DIGIT, LETTER, EITHER, LITERAL }

/**
 * A custom value rule's `shape`, compiled: `#` a digit, `A` a letter, `X` either, anything else
 * literal. A fixed length and no alternation, so matching it at every position of a body is linear in
 * the body, which is why a shape and not a regular expression: `routeTemplates` chose the same for
 * the same reason, and a user's pattern over every body of every session is that risk with more input.
 */
data class ValueShape(val text: String) {
    val tokens: List<Pair<ShapeToken, Char>> = text.map { c ->
        when (c) {
            '#' -> ShapeToken.DIGIT to c
            'A' -> ShapeToken.LETTER to c
            'X' -> ShapeToken.EITHER to c
            else -> ShapeToken.LITERAL to c
        }
    }

    companion object {
        const val MIN_LENGTH = 4
        const val MAX_LENGTH = 40

        /**
         * Characters that only mean something in a regular expression. Refused rather than taken as
         * literals, because `\d{4}` accepted as five literal characters is a rule that silently never
         * matches.
         */
        private const val REGEX_CHARACTERS = "\\^$*+?()[]{}|"

        /** What is wrong with [shape], or null. */
        fun problem(shape: String): String? = when {
            shape.length !in MIN_LENGTH..MAX_LENGTH -> "a shape is $MIN_LENGTH to $MAX_LENGTH characters"
            shape.any { it in REGEX_CHARACTERS } ->
                "a shape is not a regular expression: write # for a digit, A for a letter, X for either, anything else as itself"
            shape.none { it == '#' || it == 'A' || it == 'X' } ->
                "a shape needs at least one # (digit), A (letter) or X (either); a fixed text is not a shape"
            else -> null
        }
    }
}

/** The checksum a custom value rule's match must also pass. */
enum class ShapeCheck(val key: String) {
    NONE("none"),
    LUHN("luhn"),
    MOD97("mod97");

    companion object {
        fun parse(text: String?): ShapeCheck? = entries.firstOrNull { it.key == text }
    }
}

/** One `findings.sensitive.valueRules` entry. Reported as `sensitive.custom.<id>`. */
data class CustomValueRule(
    val id: String,
    val label: String,
    val shape: ValueShape,
    val check: ShapeCheck = ShapeCheck.NONE,
    /** Its own, because `findings.rules` takes built-in ids only. */
    val severity: FindingSeverity = FindingSeverity.MEDIUM,
    val enabled: Boolean = true,
    /** Normalised field names; empty means any value anywhere. */
    val fields: List<String> = emptyList(),
) {
    val ruleId: String get() = SensitiveConfig.CUSTOM_PREFIX + id
}

/**
 * `findings.sensitive`: which hosts are the app's own, the field-name list's edits, and the team's
 * own value rules. Hosts are lowercased and names normalised as read, so two files that mean the same
 * thing compare equal.
 */
data class SensitiveSettings(
    val firstPartyHosts: List<String> = emptyList(),
    val addFieldNames: List<String> = emptyList(),
    val removeFieldNames: List<String> = emptyList(),
    val valueRules: List<CustomValueRule> = emptyList(),
) {
    /**
     * True when [host] is one of [firstPartyHosts]: an exact host, or under a leading `*.`, which
     * does not match the bare domain. No pattern language beyond that, so no entry can be slow.
     */
    fun firstParty(host: String): Boolean {
        val name = host.lowercase().removePrefix("[").removeSuffix("]")
        return firstPartyHosts.any { pattern ->
            if (pattern.startsWith("*.")) name.endsWith(pattern.substring(1)) else name == pattern
        }
    }

    internal fun canonical(): JsonObject = buildJsonObject {
        put("addFieldNames", strings(addFieldNames.sorted()))
        put("firstPartyHosts", strings(firstPartyHosts.sorted()))
        put("removeFieldNames", strings(removeFieldNames.sorted()))
        put("valueRules", buildJsonArray {
            valueRules.sortedBy { it.id }.forEach { rule ->
                add(buildJsonObject {
                    put("check", rule.check.key)
                    put("enabled", rule.enabled)
                    put("fields", strings(rule.fields.sorted()))
                    put("id", rule.id)
                    put("label", rule.label)
                    put("severity", rule.severity.key)
                    put("shape", rule.shape.text)
                })
            }
        })
    }

    companion object {
        val DEFAULT = SensitiveSettings()
    }
}

/**
 * One `findingDismissals` entry: a finding set aside on purpose, with the reason. Matched on rule,
 * route, location and path exactly, never on anything that exists on one machine only (a session,
 * a txn id), so the list is portable between machines and survives retention.
 */
data class DismissalEntry(
    val ruleId: String,
    val route: String,
    val location: String?,
    val path: String?,
    val reason: String,
    val at: String?,
)

/**
 * Reads and validates `findings.sensitive` and the top-level `findingDismissals`, in this file's own
 * words. One reader per key serves both callers, as [FindingSettings] does: `ConfigFile` refuses a
 * `PUT` whose first problem is not null, and the live read skips each bad entry, names it, and keeps
 * the rest.
 */
object SensitiveConfig {
    const val CUSTOM_PREFIX = "sensitive.custom."

    /** Where a finding was seen; a dismissal's `location` is one of these or null. */
    val LOCATIONS = listOf("url", "query", "reqHeader", "resHeader", "reqBody", "resBody")

    const val MAX_HOSTS = 200
    const val MAX_NAMES = 200
    const val MAX_VALUE_RULES = 50
    const val MAX_LABEL = 80
    const val MAX_DISMISSALS = 1000
    const val MAX_REASON = 200

    private val KEYS = listOf("firstPartyHosts", "fieldNames", "valueRules")
    private val FIELD_NAME_KEYS = listOf("add", "remove")
    private val RULE_KEYS = listOf("id", "label", "shape", "check", "severity", "enabled", "fields")
    private val DISMISSAL_KEYS = listOf("ruleId", "route", "location", "path", "reason", "at")
    private val RULE_ID = Regex("[a-z0-9-]{1,40}")

    /** An exact host or a leading `*.`; an IPv6 literal with or without brackets. No port, no scheme. */
    private val HOST = Regex("(\\*\\.)?[a-z0-9]([a-z0-9.-]{0,251}[a-z0-9])?|\\[?[0-9a-f:.]+]?")

    /**
     * A field name as every rule compares it: lowercased, with `_`, `-`, `.` and spaces removed, so
     * `firstName`, `first-name` and `FIRST_NAME` are one name.
     */
    fun normalise(name: String): String = buildString(name.length) {
        for (c in name) if (c != '_' && c != '-' && c != '.' && c != ' ') append(c.lowercaseChar())
    }

    /** What is wrong with [value] as `findings.sensitive`, or null. */
    fun problem(value: JsonElement): String? {
        var first: String? = null
        read(value) { if (first == null) first = it }
        return first
    }

    /** [value] read as `findings.sensitive`, each bad entry passed to [report] and skipped. */
    fun read(value: JsonElement?, report: (String) -> Unit): SensitiveSettings {
        if (value == null || value is JsonNull) return SensitiveSettings.DEFAULT
        val obj = value as? JsonObject ?: run {
            report("findings.sensitive must be an object of ${KEYS.joinToString()}, for example {\"firstPartyHosts\": [\"api.example.com\"]}")
            return SensitiveSettings.DEFAULT
        }
        for (key in obj.keys) {
            if (key !in KEYS) report("findings.sensitive has an unknown key '$key'; the keys are ${KEYS.joinToString()}")
        }
        val (add, remove) = obj["fieldNames"]?.let { readFieldNames(it, report) } ?: (emptyList<String>() to emptyList())
        return SensitiveSettings(
            firstPartyHosts = obj["firstPartyHosts"]?.let { readHosts(it, report) } ?: emptyList(),
            addFieldNames = add,
            removeFieldNames = remove,
            valueRules = obj["valueRules"]?.let { readValueRules(it, report) } ?: emptyList(),
        )
    }

    private fun readHosts(value: JsonElement, report: (String) -> Unit): List<String> {
        val name = "findings.sensitive.firstPartyHosts"
        val strings = readStrings(name, value, "hosts, for example [\"api.example.com\", \"*.example.com\"]", report)
            ?: return emptyList()
        if (strings.size > MAX_HOSTS) report("$name holds ${strings.size} hosts; the most is $MAX_HOSTS")
        return strings.take(MAX_HOSTS).mapNotNull { host ->
            val lower = host.trim().lowercase()
            if (HOST.matches(lower)) lower else null.also {
                report("$name entry '$host' is not a host: write a host name or a leading *. for its subdomains, with no scheme, port or path")
            }
        }.distinct()
    }

    private fun readFieldNames(value: JsonElement, report: (String) -> Unit): Pair<List<String>, List<String>> {
        val obj = value as? JsonObject ?: run {
            report("findings.sensitive.fieldNames must be an object such as {\"add\": [\"staffNumber\"], \"remove\": [\"firstName\"]}")
            return emptyList<String>() to emptyList()
        }
        for (key in obj.keys) {
            if (key !in FIELD_NAME_KEYS) report("findings.sensitive.fieldNames has an unknown key '$key'; the keys are add, remove")
        }
        fun names(key: String): List<String> {
            val name = "findings.sensitive.fieldNames.$key"
            val strings = obj[key]?.let { readStrings(name, it, "field names, for example [\"staffNumber\"]", report) }
                ?: return emptyList()
            if (strings.size > MAX_NAMES) report("$name holds ${strings.size} names; the most is $MAX_NAMES")
            return strings.take(MAX_NAMES).mapNotNull { raw ->
                normalise(raw).takeIf { it.isNotEmpty() } ?: null.also { report("$name entry '$raw' has no letters or digits") }
            }.distinct()
        }
        return names("add") to names("remove")
    }

    private fun readValueRules(value: JsonElement, report: (String) -> Unit): List<CustomValueRule> {
        val name = "findings.sensitive.valueRules"
        val array = value as? JsonArray ?: run {
            report("$name must be a list of rules such as {\"id\": \"member-id\", \"label\": \"Member number\", \"shape\": \"AA-######\"}")
            return emptyList()
        }
        if (array.size > MAX_VALUE_RULES) report("$name holds ${array.size} rules; the most is $MAX_VALUE_RULES")
        val seen = HashSet<String>()
        return array.take(MAX_VALUE_RULES).mapNotNull { element ->
            readValueRule(element) { report("$name $it") }?.takeIf { rule ->
                seen.add(rule.id).also { fresh -> if (!fresh) report("$name has two rules with the id '${rule.id}'") }
            }
        }
    }

    /** One rule, or null when it cannot be used; every problem goes to [report], already prefixed. */
    private fun readValueRule(element: JsonElement, report: (String) -> Unit): CustomValueRule? {
        val obj = element as? JsonObject ?: run {
            report("entry $element must be an object with id, label and shape")
            return null
        }
        val id = obj.string("id")
        if (id == null || !RULE_ID.matches(id)) {
            report("entry ${obj["id"] ?: "without an id"}: id must be 1 to 40 lowercase letters, digits and -")
            return null
        }
        val where = "'$id'"
        var usable = true
        for (key in obj.keys) {
            if (key !in RULE_KEYS) report("$where has an unknown key '$key'; a rule takes ${RULE_KEYS.joinToString()}")
        }
        val label = obj.string("label")?.trim()
        if (label.isNullOrEmpty() || label.length > MAX_LABEL) {
            report("$where label must be 1 to $MAX_LABEL characters, the name the Findings page shows")
            usable = false
        }
        val shape = obj.string("shape")
        val shapeProblem = if (shape == null) "a shape is a string such as \"AA-######-#\"" else ValueShape.problem(shape)
        if (shapeProblem != null) {
            report("$where shape: $shapeProblem")
            usable = false
        }
        val check = obj["check"]?.takeUnless { it is JsonNull }?.let { element ->
            ShapeCheck.parse((element as? JsonPrimitive)?.takeIf { it.isString }?.content)
                ?: null.also { report("$where check must be ${ShapeCheck.entries.joinToString(", ") { it.key }}"); usable = false }
        } ?: ShapeCheck.NONE
        val severity = obj["severity"]?.takeUnless { it is JsonNull }?.let { element ->
            FindingSeverity.parse((element as? JsonPrimitive)?.takeIf { it.isString }?.content)
                ?: null.also { report("$where severity must be ${FindingSeverity.entries.joinToString(", ") { it.key }}"); usable = false }
        } ?: FindingSeverity.MEDIUM
        val enabled = obj["enabled"]?.takeUnless { it is JsonNull }?.let { element ->
            (element as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
                ?: null.also { report("$where enabled must be true or false"); usable = false }
        } ?: true
        val fields = obj["fields"]?.takeUnless { it is JsonNull }?.let { element ->
            readStrings("$where fields", element, "field names, for example [\"memberId\"]", report)
                ?.map(::normalise)?.filter { it.isNotEmpty() }?.distinct()
                ?: null.also { usable = false }
        } ?: emptyList()
        if (!usable) return null
        return CustomValueRule(id, label!!, ValueShape(shape!!), check, severity, enabled, fields)
    }

    /** What is wrong with [value] as `findingDismissals`, or null. A `PUT` refuses the list whole. */
    fun dismissalsProblem(value: JsonElement): String? {
        var first: String? = null
        readDismissals(value) { if (first == null) first = it }
        return first
    }

    /** [value] read as `findingDismissals`, each bad entry passed to [report] and skipped. */
    fun readDismissals(value: JsonElement?, report: (String) -> Unit): List<DismissalEntry> {
        if (value == null || value is JsonNull) return emptyList()
        val array = value as? JsonArray ?: run {
            report("findingDismissals must be a list of {\"ruleId\", \"route\", \"location\", \"path\", \"reason\"}")
            return emptyList()
        }
        if (array.size > MAX_DISMISSALS) report("findingDismissals holds ${array.size} entries; the most is $MAX_DISMISSALS")
        return array.take(MAX_DISMISSALS).mapIndexedNotNull { index, element ->
            readDismissal(element) { report("findingDismissals entry ${index + 1} $it") }
        }
    }

    private fun readDismissal(element: JsonElement, report: (String) -> Unit): DismissalEntry? {
        val obj = element as? JsonObject ?: run {
            report("must be an object with ruleId, route and reason")
            return null
        }
        var usable = true
        for (key in obj.keys) {
            if (key !in DISMISSAL_KEYS) {
                report("has an unknown key '$key'; an entry takes ${DISMISSAL_KEYS.joinToString()}")
                usable = false
            }
        }
        val ruleId = obj.string("ruleId")?.takeIf { it.isNotBlank() }
            ?: null.also { report("needs a ruleId, the rule the finding is under"); usable = false }
        val route = obj.string("route")?.takeIf { it.isNotBlank() }
            ?: null.also { report("needs a route, as the finding names it"); usable = false }
        val location = nullableString(obj, "location", report) { usable = false }
        if (location != null && location !in LOCATIONS) {
            report("location must be ${LOCATIONS.joinToString()} or null")
            usable = false
        }
        val path = nullableString(obj, "path", report) { usable = false }
        val reason = obj.string("reason")?.trim()
        if (reason.isNullOrEmpty() || reason.length > MAX_REASON) {
            report("needs a reason of 1 to $MAX_REASON characters: why the finding is expected")
            usable = false
        }
        val at = nullableString(obj, "at", report) { usable = false }
        if (!usable) return null
        return DismissalEntry(ruleId!!, route!!, location, path, reason!!, at)
    }

    private fun nullableString(obj: JsonObject, key: String, report: (String) -> Unit, bad: () -> Unit): String? {
        val element = obj[key] ?: return null
        if (element is JsonNull) return null
        return (element as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: null.also { report("$key must be a string or null"); bad() }
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

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
}

private fun strings(values: List<String>) = buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }

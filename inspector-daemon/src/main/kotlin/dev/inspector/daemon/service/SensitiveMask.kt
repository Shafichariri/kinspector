package dev.inspector.daemon.service

import dev.inspector.daemon.archive.CustomValueRule
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The one place a matched value is read after the match, and what it turns into: a hint that names
 * the kind of thing found and never the thing. The archive already holds the value unredacted, so
 * the danger is not the archive; it is a findings list pasted into a chat, a ticket or an export,
 * naming a secret in plain text next to the word "secret".
 *
 * Every hint is bounded to pass [FindingMask.isMasked] (at least four `•`, at most ten other
 * characters), which is the engine's second line, never the first: `SensitiveValuesTest` holds each
 * kind at its longest against that predicate, so the guard never has to blank a sensitive hint.
 */
object SensitiveMask {
    private const val DOTS = FindingMask.DOTS

    /** A path segment that itself matched a value rule, as a key or a parameter name. */
    const val KEY = "•"

    /** Scheme words shown before an `Authorization` credential; any other scheme could be anything. */
    private val SCHEMES = listOf("Basic", "Bearer", "Digest", "Negotiate", "NTLM")

    private val ALG = Regex("[A-Za-z0-9]{1,6}")

    /**
     * [value] masked as [kind] says. A card keeps its last four digits, an IBAN its country, check
     * digits and last four, a JWT its `alg` when that is 1 to 6 letters and digits, an email its
     * top-level domain when that is 2 to 6 letters, a phone its last two digits, an `Authorization`
     * value its scheme word from a fixed list of five. Everything else is the dots alone, with no
     * length, because a password's length is part of the password.
     */
    fun hint(kind: SensitiveKind, value: String): String = when (kind) {
        SensitiveKind.CARD -> value.filter { it in '0'..'9' }.let { "$DOTS ${it.takeLast(4)}" }
        SensitiveKind.IBAN -> value.filter { it != ' ' }.uppercase().let { "${it.take(4)} $DOTS ${it.takeLast(4)}" }
        SensitiveKind.JWT -> alg(value)?.takeIf { ALG.matches(it) }?.let { "$DOTS $it" } ?: DOTS
        SensitiveKind.EMAIL -> value.substringAfterLast('.', "").let { tld ->
            if (tld.length in 2..6 && tld.all { it in 'a'..'z' || it in 'A'..'Z' }) "•••@•••.${tld.lowercase()}" else "•••@•••"
        }
        SensitiveKind.PHONE -> value.filter { it in '0'..'9' }.let { "$DOTS ${it.takeLast(2)}" }
        SensitiveKind.AUTHORIZATION -> value.trim().substringBefore(' ').let { word ->
            SCHEMES.firstOrNull { it.equals(word, ignoreCase = true) }?.let { "$it $DOTS" } ?: DOTS
        }
        SensitiveKind.CUSTOM, SensitiveKind.SECRET -> DOTS
    }

    /**
     * A JSON key or a query name as a finding's path shows it: [KEY] when it matches a value rule
     * itself, as in a response keyed by session tokens, because a path is not masked otherwise.
     */
    fun pathKey(name: String, custom: List<CustomValueRule> = emptyList()): String =
        if (SensitiveValues.matchesAny(name, custom)) KEY else name

    private fun alg(token: String): String? {
        val header = token.substringBefore('.')
        val text = runCatching { String(Base64.getUrlDecoder().decode(header), Charsets.UTF_8) }.getOrNull() ?: return null
        val json = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
        return (json["alg"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}

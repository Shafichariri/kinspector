package dev.inspector.daemon.service

import dev.inspector.daemon.archive.CustomValueRule
import dev.inspector.daemon.archive.SensitiveConfig
import dev.inspector.daemon.archive.SensitiveSettings
import dev.inspector.daemon.archive.ShapeCheck
import dev.inspector.daemon.archive.ShapeToken
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What a value rule found, and so how [SensitiveMask.hint] masks it. */
enum class SensitiveKind { CARD, IBAN, JWT, EMAIL, PHONE, CUSTOM, AUTHORIZATION, SECRET }

/**
 * Where a value rule matched in a text: the range, never the value. Only [SensitiveMask.hint] reads
 * the characters in it after the match, so nothing else can carry a value further. [testNumber]
 * marks a published test card number; [rule] is the custom rule that matched.
 */
class ValueMatch(
    val kind: SensitiveKind,
    val start: Int,
    val end: Int,
    val testNumber: Boolean = false,
    val rule: CustomValueRule? = null,
)

/**
 * The value rules, each validated, never a bare pattern: a 16-digit string is a card only when it
 * could be one and passes Luhn, an IBAN only at its country's registry length and with mod-97, a JWT
 * only when its header decodes to JSON with an `alg`. Every finder is one pass over the text with
 * fixed-size lookups, so no body can make a scan slow.
 */
object SensitiveValues {

    /**
     * Card numbers: 13 to 19 digits, single spaces or dashes between them, bounded by non-digits,
     * first digit 2 to 6 (the ISO/IEC 7812 major industry identifiers for card issuers), and Luhn.
     * A run of digits is taken whole: digits in a longer run are an id or a reference, not a card.
     */
    fun cards(text: String): List<ValueMatch> = digitRuns(text).mapNotNull { (start, end) ->
        val digits = digitsOf(text, start, end)
        if (isCard(digits)) ValueMatch(SensitiveKind.CARD, start, end, testNumber = digits in TEST_CARDS) else null
    }

    /** [digits], digits only, could be a card and passes Luhn. */
    fun isCard(digits: String): Boolean =
        digits.length in 13..19 && digits.all { it in '0'..'9' } && digits[0] in '2'..'6' && luhn(digits)

    /**
     * A JSON number is a card only under a card field name. Integers of 13 to 19 digits in JSON are
     * ids and timestamps (a millisecond timestamp is 13 digits), Luhn passes one in ten, and the
     * issuer digit does not exclude ids, so a number elsewhere is never read as one.
     */
    fun cardInJsonNumber(number: String, key: String?): Boolean {
        if (key == null) return false
        val name = SensitiveConfig.normalise(key)
        if (CARD_FIELD_NAMES.none { FieldNames.nameMatches(name, it) }) return false
        return isCard(number)
    }

    /** A published test card number: reported as info and never raised. */
    fun isTestCard(digits: String): Boolean = digits in TEST_CARDS

    /**
     * IBANs: two letters, two digits, then letters and digits, optionally in groups of four split by
     * one space, case-insensitive, exactly as long as the registry says for that country, and mod-97.
     */
    fun ibans(text: String): List<ValueMatch> {
        val found = ArrayList<ValueMatch>()
        var i = 0
        while (i + 4 <= text.length) {
            if (!startsIban(text, i)) {
                i++
                continue
            }
            val length = IBAN_LENGTHS[text.substring(i, i + 2).uppercase()]
            val end = length?.let { ibanEnd(text, i, it) }
            if (end != null && isIban(compact(text, i, end))) {
                found += ValueMatch(SensitiveKind.IBAN, i, end)
                i = end
            } else {
                i++
            }
        }
        return found
    }

    /** [compact], no spaces, is a registered country's length and passes mod-97. */
    fun isIban(compact: String): Boolean {
        val upper = compact.uppercase()
        if (upper.length < 5 || IBAN_LENGTHS[upper.take(2)] != upper.length) return false
        if (!upper[2].isAsciiDigit() || !upper[3].isAsciiDigit()) return false
        if (!upper.all { it.isAsciiDigit() || it in 'A'..'Z' }) return false
        return mod97(upper.substring(4) + upper.take(4))
    }

    /**
     * JWTs: three base64url segments joined by `.`, whose first decodes to a JSON object with a
     * string `alg`. Only `url` and `query` look for them; in a header a token is where it belongs.
     */
    fun jwts(text: String): List<ValueMatch> {
        val found = ArrayList<ValueMatch>()
        var i = 0
        while (i < text.length) {
            if (!isTokenChar(text[i])) {
                i++
                continue
            }
            var end = i
            while (end < text.length && isTokenChar(text[end])) end++
            var trimmed = end
            while (trimmed > i && text[trimmed - 1] == '.') trimmed--
            if (jwtAlg(text.substring(i, trimmed)) != null) found += ValueMatch(SensitiveKind.JWT, i, trimmed)
            i = end
        }
        return found
    }

    /** True when [token] is a JWT as [jwts] defines one. */
    fun isJwt(token: String): Boolean = jwtAlg(token) != null

    /** Email addresses: ASCII `local@domain.tld`, the top-level domain at least two letters. */
    fun emails(text: String): List<ValueMatch> {
        val found = ArrayList<ValueMatch>()
        var from = 0
        while (true) {
            val at = text.indexOf('@', from)
            if (at < 0) break
            from = at + 1
            var start = at
            while (start > 0 && at - start < MAX_LOCAL && isLocalChar(text[start - 1])) start--
            if (start == at || (start > 0 && isLocalChar(text[start - 1]))) continue
            var end = at + 1
            while (end < text.length && end - at <= MAX_DOMAIN && isDomainChar(text[end])) end++
            while (end > at + 1 && (text[end - 1] == '.' || text[end - 1] == '-')) end--
            val domain = text.substring(at + 1, end)
            val tld = domain.substringAfterLast('.', "")
            if ('.' in domain && tld.length >= 2 && tld.all { it in 'a'..'z' || it in 'A'..'Z' } && !domain.startsWith('.')) {
                found += ValueMatch(SensitiveKind.EMAIL, start, end)
                from = end
            }
        }
        return found
    }

    /** Phone numbers in international form: `+`, then 8 to 15 digits, single spaces or dashes allowed. */
    fun phones(text: String): List<ValueMatch> {
        val found = ArrayList<ValueMatch>()
        var from = 0
        while (true) {
            val plus = text.indexOf('+', from)
            if (plus < 0) break
            from = plus + 1
            if (plus > 0 && text[plus - 1].isLetterOrDigit()) continue
            if (plus + 1 >= text.length || !text[plus + 1].isAsciiDigit()) continue
            val end = runEnd(text, plus + 1)
            val digits = digitsOf(text, plus + 1, end)
            if (digits.length in 8..15) {
                found += ValueMatch(SensitiveKind.PHONE, plus, end)
                from = end
            }
        }
        return found
    }

    /**
     * A custom rule's [CustomValueRule.shape], bounded by characters that are not letters or digits
     * and passing its check. The shape has a fixed length of at most 40, so this is at most 40
     * comparisons per position.
     */
    fun shapes(text: String, rule: CustomValueRule): List<ValueMatch> {
        val tokens = rule.shape.tokens
        val length = tokens.size
        val found = ArrayList<ValueMatch>()
        var i = 0
        while (i + length <= text.length) {
            val bounded = (i == 0 || !text[i - 1].isLetterOrDigit()) &&
                (i + length == text.length || !text[i + length].isLetterOrDigit())
            if (bounded && fits(text, i, tokens) && passes(rule.check, text, i, i + length)) {
                found += ValueMatch(SensitiveKind.CUSTOM, i, i + length, rule = rule)
                i += length
            } else {
                i++
            }
        }
        return found
    }

    /** True when any value rule, built-in or [custom], matches inside [text]: such a key is written `•`. */
    fun matchesAny(text: String, custom: List<CustomValueRule> = emptyList()): Boolean =
        cards(text).isNotEmpty() || ibans(text).isNotEmpty() || jwts(text).isNotEmpty() ||
            emails(text).isNotEmpty() || phones(text).isNotEmpty() ||
            custom.any { it.enabled && shapes(text, it).isNotEmpty() }

    /**
     * A parameter or header name that says it carries a secret, compared normalised and whole, so
     * `api_key` and `Api-Key` are `apikey` while `monkey` is not. `xapikey` is listed on its own
     * because `X-Api-Key` is the usual header for an API key; no other prefix is stripped.
     */
    fun isSecretName(name: String): Boolean = SensitiveConfig.normalise(name) in SECRET_NAMES

    /** The Luhn check over [digits], digits only. */
    fun luhn(digits: String): Boolean {
        var sum = 0
        var double = false
        for (i in digits.indices.reversed()) {
            var d = digits[i] - '0'
            if (d !in 0..9) return false
            if (double) {
                d *= 2
                if (d > 9) d -= 9
            }
            sum += d
            double = !double
        }
        return digits.isNotEmpty() && sum % 10 == 0
    }

    /**
     * ISO 7064 MOD 97-10 over letters and digits in order, a letter counting as 10 to 35: true when
     * the remainder is 1. An IBAN is checked after moving its first four characters to the end.
     */
    fun mod97(text: String): Boolean {
        var remainder = 0
        var any = false
        for (c in text.uppercase()) {
            when (c) {
                in '0'..'9' -> remainder = (remainder * 10 + (c - '0')) % 97
                in 'A'..'Z' -> remainder = (remainder * 100 + (c - 'A' + 10)) % 97
                else -> continue
            }
            any = true
        }
        return any && remainder == 1
    }

    /** The class of [host] that decides whether credentials sent to it over `http` are raised. */
    fun hostClass(host: String): HostClass {
        val name = host.lowercase().removePrefix("[").removeSuffix("]").removeSuffix(".")
        if (name == "localhost" || name == "::1" || name == "0:0:0:0:0:0:0:1") return HostClass.LOCAL
        // The Android emulator's and Genymotion's alias for the host machine: loopback in practice.
        if (name == "10.0.2.2" || name == "10.0.3.2") return HostClass.LOCAL
        val ipv4 = ipv4(name)
        if (ipv4 != null) {
            val (a, b) = ipv4
            return when {
                a == 127 -> HostClass.LOCAL
                a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) -> HostClass.PRIVATE
                else -> HostClass.PUBLIC
            }
        }
        if (':' in name) {
            val first = name.substringBefore(':').padStart(4, '0')
            return if (first.length == 4 && (first.startsWith("fc") || first.startsWith("fd"))) HostClass.PRIVATE else HostClass.PUBLIC
        }
        return if (name.endsWith(".local")) HostClass.PRIVATE else HostClass.PUBLIC
    }

    /** The first two octets of a dotted IPv4 literal, or null. */
    private fun ipv4(name: String): Pair<Int, Int>? {
        val parts = name.split('.')
        if (parts.size != 4) return null
        val octets = parts.map { part -> part.toIntOrNull()?.takeIf { part.isNotEmpty() && part.length <= 3 && it in 0..255 } ?: return null }
        return octets[0] to octets[1]
    }

    private fun jwtAlg(token: String): String? {
        val parts = token.split('.')
        if (parts.size != 3 || parts[0].isEmpty() || parts[1].isEmpty() || parts[0].length > MAX_JWT_HEADER) return null
        if (!parts.all { part -> part.all(::isBase64UrlChar) }) return null
        val header = runCatching { String(Base64.getUrlDecoder().decode(parts[0]), Charsets.UTF_8) }.getOrNull() ?: return null
        val json = runCatching { Json.parseToJsonElement(header) }.getOrNull() as? JsonObject ?: return null
        return (json["alg"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }

    /** Where a run of digits that may hold single spaces or dashes between them ends. */
    private fun runEnd(text: String, start: Int): Int {
        var end = start
        while (end < text.length) {
            val c = text[end]
            end = when {
                c.isAsciiDigit() -> end + 1
                (c == ' ' || c == '-') && end + 1 < text.length && text[end + 1].isAsciiDigit() && text[end - 1].isAsciiDigit() -> end + 1
                else -> return end
            }
        }
        return end
    }

    /** Every maximal digit run in [text], as `[start, end)`. */
    private fun digitRuns(text: String): List<Pair<Int, Int>> {
        val runs = ArrayList<Pair<Int, Int>>()
        var i = 0
        while (i < text.length) {
            if (text[i].isAsciiDigit() && (i == 0 || !text[i - 1].isAsciiDigit())) {
                val end = runEnd(text, i)
                runs += i to end
                i = end
            } else {
                i++
            }
        }
        return runs
    }

    private fun digitsOf(text: String, start: Int, end: Int): String = buildString {
        for (i in start until end) if (text[i].isAsciiDigit()) append(text[i])
    }

    private fun startsIban(text: String, i: Int): Boolean =
        (i == 0 || !text[i - 1].isLetterOrDigit()) &&
            text[i].isAsciiLetter() && text[i + 1].isAsciiLetter() && text[i + 2].isAsciiDigit() && text[i + 3].isAsciiDigit()

    /**
     * Where an IBAN of [length] characters starting at [start] ends, a single space allowed after
     * each group of four, or null when the text does not hold that many or runs on past them.
     */
    private fun ibanEnd(text: String, start: Int, length: Int): Int? {
        var count = 0
        var i = start
        while (count < length) {
            if (i >= text.length) return null
            val c = text[i]
            when {
                c.isAsciiDigit() || c.isAsciiLetter() -> count++
                c == ' ' && count % 4 == 0 && count > 0 && text[i - 1] != ' ' -> Unit
                else -> return null
            }
            i++
        }
        return if (i < text.length && text[i].isLetterOrDigit()) null else i
    }

    private fun compact(text: String, start: Int, end: Int): String = buildString {
        for (i in start until end) if (text[i] != ' ') append(text[i])
    }

    private fun fits(text: String, at: Int, tokens: List<Pair<ShapeToken, Char>>): Boolean {
        for ((k, token) in tokens.withIndex()) {
            val c = text[at + k]
            val ok = when (token.first) {
                ShapeToken.DIGIT -> c.isAsciiDigit()
                ShapeToken.LETTER -> c.isAsciiLetter()
                ShapeToken.EITHER -> c.isAsciiDigit() || c.isAsciiLetter()
                ShapeToken.LITERAL -> c == token.second
            }
            if (!ok) return false
        }
        return true
    }

    private fun passes(check: ShapeCheck, text: String, start: Int, end: Int): Boolean = when (check) {
        ShapeCheck.NONE -> true
        ShapeCheck.LUHN -> luhn(digitsOf(text, start, end))
        ShapeCheck.MOD97 -> mod97(text.substring(start, end))
    }

    private fun Char.isAsciiDigit() = this in '0'..'9'
    private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'
    private fun isBase64UrlChar(c: Char) = c.isAsciiDigit() || c.isAsciiLetter() || c == '-' || c == '_'
    private fun isTokenChar(c: Char) = isBase64UrlChar(c) || c == '.'
    private fun isLocalChar(c: Char) = c.isAsciiDigit() || c.isAsciiLetter() || c in "._%+-"
    private fun isDomainChar(c: Char) = c.isAsciiDigit() || c.isAsciiLetter() || c == '.' || c == '-'

    private const val MAX_LOCAL = 64
    private const val MAX_DOMAIN = 253
    private const val MAX_JWT_HEADER = 4096

    /** Normalised names a card number may be a JSON number under. */
    private val CARD_FIELD_NAMES = listOf("cardnumber", "pan")

    private val SECRET_NAMES = setOf(
        "token", "accesstoken", "idtoken", "refreshtoken", "auth", "authorization", "password", "passwd",
        "pwd", "secret", "clientsecret", "apikey", "xapikey", "accesskey", "secretkey", "privatekey", "sessionid",
    )

    /** Widely published test card numbers: real-looking, Luhn-valid, and never anybody's card. */
    val TEST_CARDS: Set<String> = setOf(
        "4242424242424242", "4111111111111111", "4012888888881881", "5555555555554444", "5105105105105100",
        "378282246310005", "371449635398431", "6011111111111117", "3530111333300000", "30569309025904",
        "38520000023237",
    )

    /**
     * IBAN length per country code, from the SWIFT IBAN Registry (ISO 13616), countries with the
     * full IBAN format only. Each entry has the registry's own example in `SensitiveValuesTest`,
     * which checks that example's length against this table and its mod-97, so a typo in either fails.
     */
    val IBAN_LENGTHS: Map<String, Int> = mapOf(
        "AD" to 24, "AE" to 23, "AL" to 28, "AT" to 20, "AZ" to 28, "BA" to 20, "BE" to 16, "BG" to 22,
        "BH" to 22, "BI" to 27, "BR" to 29, "BY" to 28, "CH" to 21, "CR" to 22, "CY" to 28, "CZ" to 24,
        "DE" to 22, "DJ" to 27, "DK" to 18, "DO" to 28, "EE" to 20, "EG" to 29, "ES" to 24, "FI" to 18,
        "FK" to 18, "FO" to 18, "FR" to 27, "GB" to 22, "GE" to 22, "GI" to 23, "GL" to 18, "GR" to 27,
        "GT" to 28, "HR" to 21, "HU" to 28, "IE" to 22, "IL" to 23, "IQ" to 23, "IS" to 26, "IT" to 27,
        "JO" to 30, "KW" to 30, "KZ" to 20, "LB" to 28, "LC" to 32, "LI" to 21, "LT" to 20, "LU" to 20,
        "LV" to 21, "LY" to 25, "MC" to 27, "MD" to 24, "ME" to 22, "MK" to 19, "MN" to 20, "MR" to 27,
        "MT" to 31, "MU" to 30, "NI" to 28, "NL" to 18, "NO" to 15, "OM" to 23, "PK" to 24, "PL" to 28,
        "PS" to 29, "PT" to 25, "QA" to 29, "RO" to 24, "RS" to 22, "RU" to 33, "SA" to 24, "SC" to 31,
        "SD" to 18, "SE" to 24, "SI" to 19, "SK" to 24, "SM" to 27, "SO" to 23, "ST" to 25, "SV" to 28,
        "TL" to 23, "TN" to 24, "TR" to 26, "UA" to 29, "VA" to 22, "VG" to 24, "XK" to 20, "YE" to 30,
    )
}

/** Where a request went, as far as credentials sent over plain `http` are concerned. */
enum class HostClass {
    /** Loopback, `localhost`, or an emulator's alias for the host machine: never raised. */
    LOCAL,

    /** RFC 1918, `fc00::/7` or `*.local`: one step lower than a public host. */
    PRIVATE,
    PUBLIC,
}

/** The four classes of sensitive field name, so a class's severity is one override. */
enum class FieldClass { SECRET, PAYMENT, IDENTITY, NAME }

/**
 * Field-name rules: a JSON key or a query or form name, whatever its value. Compared normalised
 * ([SensitiveConfig.normalise]). A listed name of six or more characters also matches as a suffix
 * (`newPassword`, `creditCardNumber`); a shorter one only whole, so `pin` never matches `shipping`
 * and `otp` never `footprint`. `fieldNames.add` joins the identity class, the one a team's own
 * identifier most often is; `fieldNames.remove` drops a shipped name.
 */
class FieldNames(settings: SensitiveSettings = SensitiveSettings.DEFAULT) {
    private val byClass: List<Pair<FieldClass, List<String>>> = SHIPPED.map { (cls, names) ->
        cls to names.filter { it !in settings.removeFieldNames } +
            if (cls == FieldClass.IDENTITY) settings.addFieldNames.filter { added -> SHIPPED.none { added in it.second } } else emptyList()
    }

    /** The class [name] belongs to, or null. A whole match wins over a suffix, then the longer suffix. */
    fun classOf(name: String): FieldClass? {
        val normal = SensitiveConfig.normalise(name)
        if (normal.isEmpty()) return null
        byClass.firstOrNull { (_, names) -> normal in names }?.let { return it.first }
        return byClass.flatMap { (cls, names) -> names.filter { nameMatches(normal, it) }.map { cls to it.length } }
            .maxByOrNull { it.second }?.first
    }

    companion object {
        /** Names shorter than this match only whole. */
        const val SUFFIX_FROM = 6

        /** [normal], already normalised, is [listed] or ends with it and [listed] is long enough. */
        fun nameMatches(normal: String, listed: String): Boolean =
            normal == listed || (listed.length >= SUFFIX_FROM && normal.endsWith(listed))

        /** A neutral spread, not one country's: no national acronym stands in for a class of number. */
        val SHIPPED: List<Pair<FieldClass, List<String>>> = listOf(
            FieldClass.SECRET to listOf("password", "passwd", "pwd", "passcode", "pin", "otp", "secret"),
            FieldClass.PAYMENT to listOf("cardnumber", "pan", "cvv", "cvc", "cvv2", "securitycode", "iban", "accountnumber"),
            FieldClass.IDENTITY to listOf(
                "nationalid", "nationalidnumber", "idnumber", "identitynumber", "personalid", "taxid", "taxnumber",
                "passportnumber", "passportno", "socialsecuritynumber", "driverlicensenumber", "licensenumber",
                "dateofbirth", "dob", "birthdate",
            ),
            FieldClass.NAME to listOf("firstname", "lastname", "fullname", "surname", "givenname", "familyname", "middlename"),
        )
    }
}

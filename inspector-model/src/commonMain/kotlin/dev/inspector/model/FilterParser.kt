package dev.inspector.model

/**
 * Thrown for malformed filter input. The message is written to be shown to a human or an agent
 * verbatim — the in-app filter bar, the web UI, the REST 400 body and the MCP tool error all
 * surface it unchanged, so it should name the problem and the fix.
 */
class FilterParseException(message: String) : IllegalArgumentException(message)

/**
 * Parser for the filter grammar documented on [Filter].
 *
 * Returns [Result] rather than throwing, because every caller has a UI affordance for the error
 * and none of them want a try/catch on the keystroke path.
 */
object FilterParser {

    private val VALID_KEYS = listOf(
        "status", "method", "host", "path", "slower", "larger", "has", "text", "since", "attempt",
        // Grammar v2. A term whose field does not exist on a row type excludes that row type, so
        // these two match signals only — see the rule on `Filter`.
        "tag", "name",
    )

    private val keyList = VALID_KEYS.joinToString(", ")

    fun parse(input: String): Result<Filter> = runCatching { parseOrThrow(input) }

    fun parseOrThrow(input: String): Filter {
        val tokens = tokenize(input)
        if (tokens.isEmpty()) return Filter.MatchAll

        // Split on top-level '|' into OR branches; each branch ANDs its terms.
        val branches = mutableListOf<List<String>>()
        var current = mutableListOf<String>()
        for (token in tokens) {
            when (token) {
                is Token.Pipe -> {
                    if (current.isEmpty()) {
                        throw FilterParseException(
                            "Empty side around '|'. Write it as 'status>=400 | has:error'."
                        )
                    }
                    branches += current
                    current = mutableListOf()
                }
                is Token.Term -> current += token.text
            }
        }
        if (current.isEmpty()) {
            throw FilterParseException(
                "Trailing '|' with nothing after it. Write it as 'status>=400 | has:error'."
            )
        }
        branches += current

        val parsedBranches = branches.map { terms ->
            val filters = terms.map { parseTerm(it) }
            if (filters.size == 1) filters.single() else Filter.And(filters)
        }
        return if (parsedBranches.size == 1) parsedBranches.single() else Filter.Or(parsedBranches)
    }

    // -----------------------------------------------------------------------------------------
    // Tokenizer
    // -----------------------------------------------------------------------------------------

    private sealed interface Token {
        data object Pipe : Token
        data class Term(val text: String) : Token
    }

    /**
     * Splits on whitespace and `|`, except inside double quotes — quotes exist so that
     * `since:marker("tapped checkout")` can carry a space. Quote characters are kept in the
     * token because [parseMarkerRef] matches on them.
     */
    private fun tokenize(input: String): List<Token> {
        val tokens = mutableListOf<Token>()
        val sb = StringBuilder()
        var inQuotes = false

        fun flush() {
            if (sb.isNotEmpty()) {
                tokens += Token.Term(sb.toString())
                sb.clear()
            }
        }

        for (ch in input) {
            when {
                ch == '"' -> {
                    inQuotes = !inQuotes
                    sb.append(ch)
                }
                inQuotes -> sb.append(ch)
                ch.isWhitespace() -> flush()
                ch == '|' -> {
                    flush()
                    tokens += Token.Pipe
                }
                else -> sb.append(ch)
            }
        }
        if (inQuotes) {
            throw FilterParseException(
                "Unclosed quote. Write it as 'since:marker(\"tapped checkout\")'."
            )
        }
        flush()
        return tokens
    }

    // -----------------------------------------------------------------------------------------
    // Terms
    // -----------------------------------------------------------------------------------------

    private fun parseTerm(raw: String): Filter {
        var i = 0
        while (i < raw.length && raw[i].isLetter()) i++
        if (i == 0) {
            throw FilterParseException(
                "Could not read a filter key in '$raw'. Valid keys: $keyList."
            )
        }
        val key = raw.substring(0, i).lowercase()

        val op = when {
            raw.startsWith(">=", i) -> CompareOp.GTE.also { i += 2 }
            raw.startsWith("<=", i) -> CompareOp.LTE.also { i += 2 }
            raw.startsWith(">", i) -> CompareOp.GT.also { i += 1 }
            raw.startsWith("<", i) -> CompareOp.LT.also { i += 1 }
            raw.startsWith(":", i) -> CompareOp.EQ.also { i += 1 }
            else -> throw FilterParseException(
                "Missing operator in '$raw'. Use 'key:value', or 'key>=value' for status and attempt."
            )
        }

        val value = raw.substring(i)
        if (value.isEmpty()) {
            throw FilterParseException("'$key' needs a value, as in '${example(key)}'.")
        }

        if (key !in VALID_KEYS) {
            throw FilterParseException("Unknown filter key '$key'. Valid keys: $keyList.")
        }

        return when (key) {
            "status" -> StatusTerm(op, requireInt(key, value))
            "attempt" -> AttemptTerm(op, requireInt(key, value))
            "method" -> MethodTerm(requireEq(key, op, value))
            "host" -> HostTerm(requireEq(key, op, value))
            "path" -> PathTerm(requireEq(key, op, value))
            "text" -> TextTerm(requireEq(key, op, value))
            "has" -> parseHas(requireEq(key, op, value))
            "slower" -> SlowerTerm(parseDuration(requireEq(key, op, value)))
            "larger" -> LargerTerm(parseSize(requireEq(key, op, value)))
            "since" -> SinceMarkerTerm(parseMarkerRef(requireEq(key, op, value)))
            "tag" -> TagTerm(requireEq(key, op, value))
            "name" -> NameTerm(requireEq(key, op, value))
            else -> error("unreachable: key '$key' passed validation but has no branch")
        }
    }

    private fun example(key: String): String = when (key) {
        "status" -> "status>=400"
        "method" -> "method:POST"
        "host" -> "host:api.example.com"
        "path" -> "path:/v2/users/*"
        "slower" -> "slower:500ms"
        "larger" -> "larger:10kb"
        "has" -> "has:error"
        "text" -> "text:refund"
        "since" -> "since:marker(\"tapped checkout\")"
        "attempt" -> "attempt>1"
        "tag" -> "tag:screen"
        "name" -> "name:Checkout"
        else -> "status>=400"
    }

    private fun requireEq(key: String, op: CompareOp, value: String): String {
        if (op != CompareOp.EQ) {
            throw FilterParseException(
                "'$key' does not support '${op.symbol}'. Use '$key:$value'."
            )
        }
        return value
    }

    private fun requireInt(key: String, value: String): Int =
        value.toIntOrNull() ?: throw FilterParseException(
            "'$key' expects a number, got '$value'. Try '${example(key)}'."
        )

    private fun parseHas(value: String): Filter = when (value.lowercase()) {
        "error" -> HasErrorTerm
        else -> throw FilterParseException("'has' only supports 'has:error', got 'has:$value'.")
    }

    /** `500ms`, `2s`, or a bare number read as milliseconds. */
    private fun parseDuration(value: String): Long {
        val v = value.lowercase()
        val (numberPart, multiplier) = when {
            v.endsWith("ms") -> v.dropLast(2) to 1L
            v.endsWith("s") -> v.dropLast(1) to 1000L
            else -> v to 1L
        }
        val n = numberPart.toLongOrNull() ?: throw FilterParseException(
            "'slower' expects a duration like '500ms' or '2s', got '$value'."
        )
        if (n < 0) throw FilterParseException("'slower' expects a positive duration, got '$value'.")
        return n * multiplier
    }

    /** `100b`, `10kb`, `2mb`, or a bare number read as bytes. */
    private fun parseSize(value: String): Long {
        val v = value.lowercase()
        val (numberPart, multiplier) = when {
            v.endsWith("kb") -> v.dropLast(2) to 1024L
            v.endsWith("mb") -> v.dropLast(2) to 1024L * 1024
            v.endsWith("gb") -> v.dropLast(2) to 1024L * 1024 * 1024
            v.endsWith("b") -> v.dropLast(1) to 1L
            else -> v to 1L
        }
        val n = numberPart.toLongOrNull() ?: throw FilterParseException(
            "'larger' expects a size like '10kb' or '2mb', got '$value'."
        )
        if (n < 0) throw FilterParseException("'larger' expects a positive size, got '$value'.")
        return n * multiplier
    }

    /** `marker("label")` — the quotes are required so labels may contain spaces. */
    private fun parseMarkerRef(value: String): String {
        val prefix = "marker(\""
        val suffix = "\")"
        if (!value.startsWith(prefix) || !value.endsWith(suffix) ||
            value.length < prefix.length + suffix.length
        ) {
            throw FilterParseException(
                "'since' expects 'since:marker(\"label\")', got 'since:$value'."
            )
        }
        val label = value.substring(prefix.length, value.length - suffix.length)
        if (label.isBlank()) {
            throw FilterParseException("'since' needs a marker label, as in 'since:marker(\"login\")'.")
        }
        return label
    }
}

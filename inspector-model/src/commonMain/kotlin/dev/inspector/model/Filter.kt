package dev.inspector.model

/**
 * A parsed filter expression.
 *
 * One grammar, evaluated in three places: the in-app Compose UI (against the device ring
 * buffer), the daemon (for the web UI, REST, CLI and MCP), and tests. A filter string typed in
 * the app is the same string you hand an agent — that equivalence is the whole point of keeping
 * this in `:inspector-model`.
 *
 * The grammar is frozen for v1. It is deliberately not a query language; resist extending it.
 *
 * ```
 * status:404      status>=400     status<500
 * method:POST
 * host:api.example.com            substring, case-insensitive
 * path:/v2/users&#42;             glob when it contains a star, else substring
 * slower:500ms                    duration greater-than
 * larger:10kb                     max(reqBytes, resBytes) greater-than
 * has:error                       status >= 400 or a transport failure
 * text:refund                     substring of host + path + query — never bodies
 * since:marker("tapped checkout") at or after the last marker with that label
 * attempt>1                       retried or redirected attempts only
 * ```
 *
 * Terms separated by whitespace are ANDed. `|` ORs, and binds more loosely than the implicit
 * AND, so `a b | c d` reads as `(a AND b) OR (c AND d)`.
 *
 * Bodies are never scanned. Body search would require an index; keeping filters to metadata is
 * what lets the web UI stay smooth at 10k rows.
 */
sealed interface Filter {
    fun matches(txn: NetworkTransaction, ctx: FilterContext = FilterContext.EMPTY): Boolean

    /** The empty filter. Matches everything. */
    data object MatchAll : Filter {
        override fun matches(txn: NetworkTransaction, ctx: FilterContext) = true
    }

    data class And(val terms: List<Filter>) : Filter {
        override fun matches(txn: NetworkTransaction, ctx: FilterContext) =
            terms.all { it.matches(txn, ctx) }
    }

    data class Or(val branches: List<Filter>) : Filter {
        override fun matches(txn: NetworkTransaction, ctx: FilterContext) =
            branches.any { it.matches(txn, ctx) }
    }
}

/**
 * Ambient data a filter may need beyond the transaction itself.
 *
 * Currently only markers, for `since:marker("…")`. Passing this explicitly rather than baking
 * marker state into the [Filter] keeps parsed filters immutable and reusable across sessions.
 */
class FilterContext(val markers: List<Marker> = emptyList()) {

    private val lastMonoByLabel: Map<String, Long> by lazy {
        buildMap {
            for (m in markers) {
                val key = m.label.lowercase()
                val existing = get(key)
                if (existing == null || m.mono > existing) put(key, m.mono)
            }
        }
    }

    /** Monotonic timestamp of the most recent marker with [label], or null if there is none. */
    fun lastMarkerMono(label: String): Long? = lastMonoByLabel[label.lowercase()]

    companion object {
        val EMPTY = FilterContext()
    }
}

/** Comparison operators. `:` means equality for numeric keys. */
enum class CompareOp(val symbol: String) {
    EQ(":"),
    GT(">"),
    GTE(">="),
    LT("<"),
    LTE("<=");

    fun test(actual: Long, expected: Long): Boolean = when (this) {
        EQ -> actual == expected
        GT -> actual > expected
        GTE -> actual >= expected
        LT -> actual < expected
        LTE -> actual <= expected
    }
}

// ---------------------------------------------------------------------------------------------
// Terms
// ---------------------------------------------------------------------------------------------

/** `status:404`, `status>=400`. A transport failure has no status and never matches. */
data class StatusTerm(val op: CompareOp, val value: Int) : Filter {
    override fun matches(txn: NetworkTransaction, ctx: FilterContext): Boolean {
        val s = txn.status ?: return false
        return op.test(s.toLong(), value.toLong())
    }
}

/** `attempt>1` — retried or redirected attempts. */
data class AttemptTerm(val op: CompareOp, val value: Int) : Filter {
    override fun matches(txn: NetworkTransaction, ctx: FilterContext) =
        op.test(txn.attempt.toLong(), value.toLong())
}

/** `method:POST`. Exact, case-insensitive. */
data class MethodTerm(val method: String) : Filter {
    private val upper = method.uppercase()
    override fun matches(txn: NetworkTransaction, ctx: FilterContext) =
        txn.method.uppercase() == upper
}

/** `host:api.example.com`. Substring, case-insensitive. */
data class HostTerm(val needle: String) : Filter {
    private val lower = needle.lowercase()
    override fun matches(txn: NetworkTransaction, ctx: FilterContext) =
        txn.host.lowercase().contains(lower)
}

/**
 * Path matching, e.g. `path:/v2/users&#42;`.
 *
 * Glob (with a star as the only wildcard) when the pattern contains one, otherwise a plain
 * substring match. The dual behaviour is intentional: a bare `path:/v2/users` typed in a hurry
 * should find `/v2/users/me`, which a strict glob would not.
 */
data class PathTerm(val pattern: String) : Filter {
    private val lower = pattern.lowercase()
    private val isGlob = lower.contains('*')
    override fun matches(txn: NetworkTransaction, ctx: FilterContext): Boolean {
        val path = txn.path.lowercase()
        return if (isGlob) globMatches(lower, path) else path.contains(lower)
    }
}

/** `slower:500ms`. Strictly greater than. In-flight transactions never match. */
data class SlowerTerm(val ms: Long) : Filter {
    override fun matches(txn: NetworkTransaction, ctx: FilterContext): Boolean {
        val d = txn.ms ?: return false
        return d > ms
    }
}

/** `larger:10kb`. Compares the larger of request and response body size. */
data class LargerTerm(val bytes: Long) : Filter {
    override fun matches(txn: NetworkTransaction, ctx: FilterContext) =
        maxOf(txn.reqBytes, txn.resBytes) > bytes
}

/** `has:error` — 4xx, 5xx, or a transport failure. */
data object HasErrorTerm : Filter {
    override fun matches(txn: NetworkTransaction, ctx: FilterContext) = txn.isError
}

/** `text:refund`. Searches host, path and query. Never bodies. */
data class TextTerm(val needle: String) : Filter {
    private val lower = needle.lowercase()
    override fun matches(txn: NetworkTransaction, ctx: FilterContext): Boolean {
        if (txn.host.lowercase().contains(lower)) return true
        if (txn.path.lowercase().contains(lower)) return true
        return txn.query?.lowercase()?.contains(lower) == true
    }
}

/**
 * `since:marker("tapped checkout")` — at or after the last marker carrying that label.
 *
 * When no such marker exists this matches nothing. Matching everything would silently turn a
 * typo'd label into "no filter at all", which is the more dangerous failure while debugging.
 */
data class SinceMarkerTerm(val label: String) : Filter {
    override fun matches(txn: NetworkTransaction, ctx: FilterContext): Boolean {
        val mono = ctx.lastMarkerMono(label) ?: return false
        return txn.mono >= mono
    }
}

/**
 * Glob match supporting `*` as the only wildcard, matching any run of characters including `/`.
 * Both arguments are expected to already be lowercased by the caller.
 */
internal fun globMatches(pattern: String, value: String): Boolean {
    if (!pattern.contains('*')) return value == pattern
    val parts = pattern.split('*')

    val first = parts.first()
    if (!value.startsWith(first)) return false
    var idx = first.length

    val last = parts.last()
    for (i in 1 until parts.size - 1) {
        val part = parts[i]
        if (part.isEmpty()) continue
        val found = value.indexOf(part, idx)
        if (found < 0) return false
        idx = found + part.length
    }

    if (last.isEmpty()) return true
    // The suffix must not overlap material already consumed by earlier parts.
    if (value.length - idx < last.length) return false
    return value.endsWith(last)
}

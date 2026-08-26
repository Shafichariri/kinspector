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
 * Bodies and signal payloads are never scanned. Content search would require an index; keeping
 * filters to metadata is what lets the web UI stay smooth at 10k rows.
 *
 * ## Two row types, one grammar
 *
 * Grammar v2 adds `tag:` and `name:`, and with them the rule that makes one grammar work across
 * both streams:
 *
 * > **A term whose field does not exist on a row type excludes that row type.**
 *
 * So `status>=400` matches transactions only, `tag:screen` matches signals only, and
 * `since:marker(…)` matches both. Because terms are ANDed, `status:500 tag:screen` therefore
 * matches **nothing at all** — correct and consistent rather than a bug. Use `|` to span types:
 * `status:500 | tag:screen`.
 *
 * One grammar across both streams is what makes a merged timeline query expressible at all.
 */
sealed interface Filter {
    fun matches(row: Row, ctx: FilterContext = FilterContext.EMPTY): Boolean

    /** The empty filter. Matches everything, of either row type. */
    data object MatchAll : Filter {
        override fun matches(row: Row, ctx: FilterContext) = true
    }

    data class And(val terms: List<Filter>) : Filter {
        override fun matches(row: Row, ctx: FilterContext) = terms.all { it.matches(row, ctx) }
    }

    data class Or(val branches: List<Filter>) : Filter {
        override fun matches(row: Row, ctx: FilterContext) = branches.any { it.matches(row, ctx) }
    }
}

/**
 * A row a filter can be evaluated against.
 *
 * Introduced so one grammar spans both streams. The alternative — a second `matches` overload on
 * every term — would have let a term silently answer for a row type nobody thought about, which is
 * exactly what the exclusion rule exists to prevent.
 */
sealed interface Row {
    /** Device monotonic milliseconds. The only field both types share, and what merges them. */
    val mono: Long

    data class Txn(val txn: NetworkTransaction) : Row {
        override val mono: Long get() = txn.mono
    }

    data class Sig(val signal: Signal) : Row {
        override val mono: Long get() = signal.mono
    }

    /**
     * A marker, so a merged timeline can carry its landmarks through a filter.
     *
     * It has only `mono` and a label, so by the exclusion rule every typed term drops it — while
     * `since:marker(…)` and `text:` still reach it. Without this a timeline cut at a marker would
     * lose the very marker it was cut at, and `status:500 tag:screen` would still return markers
     * rather than the documented nothing.
     */
    data class Mark(val marker: Marker) : Row {
        override val mono: Long get() = marker.mono
    }
}

/** Evaluates against a transaction. The overload every pre-signals call site already uses. */
fun Filter.matches(txn: NetworkTransaction, ctx: FilterContext = FilterContext.EMPTY): Boolean =
    matches(Row.Txn(txn), ctx)

/** Evaluates against a signal. */
fun Filter.matches(signal: Signal, ctx: FilterContext = FilterContext.EMPTY): Boolean =
    matches(Row.Sig(signal), ctx)

/** Evaluates against a marker. */
fun Filter.matches(marker: Marker, ctx: FilterContext = FilterContext.EMPTY): Boolean =
    matches(Row.Mark(marker), ctx)

/**
 * A term whose field exists only on transactions. Excludes signal rows by construction, so the
 * exclusion rule cannot be forgotten when a term is added.
 */
sealed class TransactionTerm : Filter {
    final override fun matches(row: Row, ctx: FilterContext): Boolean =
        row is Row.Txn && matchesTxn(row.txn, ctx)

    protected abstract fun matchesTxn(txn: NetworkTransaction, ctx: FilterContext): Boolean
}

/** A term whose field exists only on signals. Excludes transaction rows by construction. */
sealed class SignalTerm : Filter {
    final override fun matches(row: Row, ctx: FilterContext): Boolean =
        row is Row.Sig && matchesSignal(row.signal, ctx)

    protected abstract fun matchesSignal(signal: Signal, ctx: FilterContext): Boolean
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
data class StatusTerm(val op: CompareOp, val value: Int) : TransactionTerm() {
    override fun matchesTxn(txn: NetworkTransaction, ctx: FilterContext): Boolean {
        val s = txn.status ?: return false
        return op.test(s.toLong(), value.toLong())
    }
}

/** `attempt>1` — retried or redirected attempts. */
data class AttemptTerm(val op: CompareOp, val value: Int) : TransactionTerm() {
    override fun matchesTxn(txn: NetworkTransaction, ctx: FilterContext) =
        op.test(txn.attempt.toLong(), value.toLong())
}

/** `method:POST`. Exact, case-insensitive. */
data class MethodTerm(val method: String) : TransactionTerm() {
    private val upper = method.uppercase()
    override fun matchesTxn(txn: NetworkTransaction, ctx: FilterContext) =
        txn.method.uppercase() == upper
}

/** `host:api.example.com`. Substring, case-insensitive. */
data class HostTerm(val needle: String) : TransactionTerm() {
    private val lower = needle.lowercase()
    override fun matchesTxn(txn: NetworkTransaction, ctx: FilterContext) =
        txn.host.lowercase().contains(lower)
}

/**
 * Path matching, e.g. `path:/v2/users&#42;`.
 *
 * Glob (with a star as the only wildcard) when the pattern contains one, otherwise a plain
 * substring match. The dual behaviour is intentional: a bare `path:/v2/users` typed in a hurry
 * should find `/v2/users/me`, which a strict glob would not.
 */
data class PathTerm(val pattern: String) : TransactionTerm() {
    private val lower = pattern.lowercase()
    private val isGlob = lower.contains('*')
    override fun matchesTxn(txn: NetworkTransaction, ctx: FilterContext): Boolean {
        val path = txn.path.lowercase()
        return if (isGlob) globMatches(lower, path) else path.contains(lower)
    }
}

/** `slower:500ms`. Strictly greater than. In-flight transactions never match. */
data class SlowerTerm(val ms: Long) : TransactionTerm() {
    override fun matchesTxn(txn: NetworkTransaction, ctx: FilterContext): Boolean {
        val d = txn.ms ?: return false
        return d > ms
    }
}

/** `larger:10kb`. Compares the larger of request and response body size. */
data class LargerTerm(val bytes: Long) : TransactionTerm() {
    override fun matchesTxn(txn: NetworkTransaction, ctx: FilterContext) =
        maxOf(txn.reqBytes, txn.resBytes) > bytes
}

/** `has:error` — 4xx, 5xx, or a transport failure. */
data object HasErrorTerm : Filter {
    override fun matches(row: Row, ctx: FilterContext) = row is Row.Txn && row.txn.isError
}

/**
 * `text:refund`. Matches **both** row types: host, path and query on a transaction; tag and name
 * on a signal.
 *
 * Never bodies, and never signal payloads — the same rule for the same reason. A payload search
 * would need an index, and a filter that sometimes reads megabytes is not the filter the web UI
 * runs on every keystroke.
 */
data class TextTerm(val needle: String) : Filter {
    private val lower = needle.lowercase()
    override fun matches(row: Row, ctx: FilterContext): Boolean = when (row) {
        is Row.Txn -> {
            val txn = row.txn
            txn.host.lowercase().contains(lower) ||
                txn.path.lowercase().contains(lower) ||
                txn.query?.lowercase()?.contains(lower) == true
        }
        is Row.Sig -> {
            val signal = row.signal
            signal.tag.lowercase().contains(lower) || signal.name.lowercase().contains(lower)
        }
        is Row.Mark -> row.marker.label.lowercase().contains(lower)
    }
}

/** `tag:screen`. Exact, case-insensitive. Signals only. */
data class TagTerm(val tag: String) : SignalTerm() {
    private val lower = tag.lowercase()
    override fun matchesSignal(signal: Signal, ctx: FilterContext) = signal.tag.lowercase() == lower
}

/** `name:portfolios`. Substring, case-insensitive. Signals only. */
data class NameTerm(val needle: String) : SignalTerm() {
    private val lower = needle.lowercase()
    override fun matchesSignal(signal: Signal, ctx: FilterContext) =
        signal.name.lowercase().contains(lower)
}

/**
 * `since:marker("tapped checkout")` — at or after the last marker carrying that label.
 *
 * When no such marker exists this matches nothing. Matching everything would silently turn a
 * typo'd label into "no filter at all", which is the more dangerous failure while debugging.
 */
data class SinceMarkerTerm(val label: String) : Filter {
    override fun matches(row: Row, ctx: FilterContext): Boolean {
        val mono = ctx.lastMarkerMono(label) ?: return false
        // Both row types carry `mono`, which is exactly why a merged timeline can be cut here.
        return row.mono >= mono
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

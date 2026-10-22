package dev.inspector.daemon.service

import dev.inspector.daemon.archive.RouteKey
import dev.inspector.daemon.archive.RouteTemplates
import dev.inspector.model.Filter
import dev.inspector.model.FilterContext
import dev.inspector.model.NetworkTransaction
import dev.inspector.model.matches
import kotlinx.serialization.Serializable

/**
 * One session's route table: what `GET …/stats`, `route_stats`, `inspector stats` and the exports
 * return. A null field is absent from the JSON (`InspectorJson` writes no explicit nulls), and no
 * non-null field has a default, so a zero is always written rather than read as missing.
 */
@Serializable
data class RouteStatsReport(
    /** The resolved folder's id, never `latest`. */
    val sessionId: String,
    val filter: String,
    /** False when the filter has a top-level `|` and so could not be put in front of each row's term. */
    val filterCarried: Boolean,
    val sort: String,
    /** Rows in the session: every attempt, as the traffic list counts them. */
    val total: Int,
    /** Rows the filter kept. */
    val matched: Int,
    /** Routes among [matched], before the limit cut [routes]. */
    val routeCount: Int,
    val routes: List<RouteStatsRow>,
)

/** One method, origin and route. The field meanings are in `docs/DAEMON.md` §7, "Route stats". */
@Serializable
data class RouteStatsRow(
    val method: String,
    val scheme: String,
    val host: String,
    /** Present only when it is not the scheme's default. */
    val port: Int? = null,
    val route: String,
    val routeSource: String,
    /** A superset of this row's calls; see [RouteTemplates.filterTerm]. */
    val filterTerm: String,
    val calls: Int,
    val attempts: Int,
    val retries: Int,
    val redirects: Int,
    val retried: Boolean,
    val count2xx: Int,
    val count3xx: Int,
    val count4xx: Int,
    val count5xx: Int,
    val transportFailures: Int,
    val inFlight: Int,
    val outcomes: Int,
    val rate4xx: Double? = null,
    val rate5xx: Double? = null,
    val transportFailureRate: Double? = null,
    val errorRate: Double? = null,
    val latencySamples: Int,
    val p50Ms: Long? = null,
    val p90Ms: Long? = null,
    val p95Ms: Long? = null,
    val p99Ms: Long? = null,
    val maxMs: Long? = null,
    /** `n<20` when p95 and p99 are withheld. The page prints it rather than compare against 20 itself. */
    val tailWithheld: String? = null,
    val meanReqBytes: Long? = null,
    val meanResBytes: Long? = null,
)

/** [RouteStatsAccumulator.rowsByKey]'s answer: each route's row, and whether the filter reached every term. */
data class RouteRows(val rows: Map<RouteKey, RouteStatsRow>, val carried: Boolean)

/**
 * The order of a route table. In the daemon, not the page, so `limit` cuts the right rows and REST,
 * MCP and the page share one order. Every key is descending with nulls (withheld or unmeasurable)
 * last. `errors` orders by `errorRate`, the share of attempts with an outcome that failed, so a
 * route failing half its calls ranks above a busier one failing a tenth; ties go to the busier.
 */
enum class StatsSort(val key: String, val metric: (RouteStatsRow) -> Number?) {
    ATTEMPTS("attempts", { it.attempts }),
    P50("p50", { it.p50Ms }),
    P90("p90", { it.p90Ms }),
    P95("p95", { it.p95Ms }),
    P99("p99", { it.p99Ms }),
    MAX("max", { it.maxMs }),
    ERRORS("errors", { it.errorRate });

    companion object {
        /** Absent or blank is the default; anything else unknown is null, for the caller's 400. */
        fun parse(text: String?): StatsSort? =
            if (text.isNullOrBlank()) ATTEMPTS else entries.firstOrNull { it.key == text }

        fun unknown(text: String): String =
            "unknown sort '$text'. Use one of: ${entries.joinToString(", ") { it.key }}."
    }
}

/**
 * Folds rows into per-route counts and latencies, and snapshots them as a [RouteStatsReport].
 *
 * Pure: it reads nothing. [RouteStatsService] feeds it from `index.jsonl` and keeps it between reads;
 * the exports feed it the rows they already hold. One accumulator for both is what keeps a page and
 * an export of the same session from disagreeing.
 *
 * It keeps per route only counts, a growable array of latencies, the set of `callId`s and the few
 * attempts after a first, and session-wide the 3xx attempts in [RedirectHops]; never the rows.
 * Retries and redirects are told apart at [rowsByKey], not as rows arrive, so a fold that met an
 * attempt before the one it follows still classifies it right.
 */
class RouteStatsAccumulator(
    private val templates: RouteTemplates,
    private val filter: Filter,
    private val context: FilterContext,
) {
    /** Rows folded so far, every attempt: what a report calls `total`. */
    var total = 0
        private set

    /** Rows the filter kept: what a report calls `matched`. */
    var matched = 0
        private set

    private val routes = HashMap<RouteKey, RouteTally>()

    /** Every row in the session, filtered or not: see [RedirectHops] for why. */
    private val redirectHops = RedirectHops()

    /** Most sessions call a few hundred paths many times; this saves templating each again. */
    private val keyByOrigin = HashMap<OriginPath, RouteKey>()

    /**
     * Counts [txn], and returns the route it was counted under, or null when the filter dropped it.
     * The session diff folds the same rows into its own tallies, and keys them by what this returns,
     * so the two cannot disagree about which rows a route holds or which route a row is on.
     */
    fun add(txn: NetworkTransaction): RouteKey? {
        total++
        redirectHops.add(txn)
        if (filter != Filter.MatchAll && !filter.matches(txn, context)) return null
        matched++
        val origin = OriginPath(txn.method, txn.scheme, txn.host, txn.port, txn.path)
        val key = keyByOrigin[origin] ?: templates.keyOf(txn).also {
            if (keyByOrigin.size < KEY_MEMO_LIMIT) keyByOrigin[origin] = it
        }
        routes.getOrPut(key) { RouteTally() }.add(txn)
        return key
    }

    /** The report as of the rows folded so far. [filterText] must be the text [filter] was parsed from. */
    fun snapshot(sessionId: String, filterText: String, sort: StatsSort, limit: Int): RouteStatsReport {
        val (byKey, carriedAll) = rowsByKey(filterText, filter)
        val ordered = byKey.values.sortedWith(order(sort))
        return RouteStatsReport(
            sessionId = sessionId,
            filter = filterText,
            filterCarried = carriedAll,
            sort = sort.key,
            total = total,
            matched = matched,
            routeCount = ordered.size,
            routes = if (limit >= ordered.size) ordered else ordered.subList(0, limit),
        )
    }

    /**
     * Every route's row as of the rows folded so far, unordered, and whether [termText] was carried
     * into every row's filter term. [termParsed] is what decides an `Or` (see
     * [RouteTemplates.compose]), so a caller whose rows were cut by more than its text says, as the
     * diff's `since` cut is, passes the filter the text came from rather than the whole cut.
     */
    fun rowsByKey(termText: String, termParsed: Filter): RouteRows {
        var carriedAll = true
        val rows = LinkedHashMap<RouteKey, RouteStatsRow>(routes.size * 2)
        for ((key, tally) in routes) {
            val (term, carried) = RouteTemplates.compose(termText, termParsed, RouteTemplates.filterTerm(key))
            if (!carried) carriedAll = false
            rows[key] = tally.row(key, term)
        }
        return RouteRows(rows, carriedAll)
    }

    /** An attempt after a first; a retry or a hop, decided at [rowsByKey]. */
    private class LaterAttempt(val callId: String, val attempt: Int)

    private data class OriginPath(val method: String, val scheme: String, val host: String, val port: Int?, val path: String)

    private inner class RouteTally {
        var attempts = 0
        val calls = HashSet<String>()
        /** Each attempt > 1: a retry unless the one before it was a 3xx. */
        val laterAttempts = ArrayList<LaterAttempt>()
        val byClass = IntArray(4)
        var transportFailures = 0
        var inFlight = 0
        var latencies = LongArray(8)
        var samples = 0
        var sorted = true
        var reqBytes = 0L
        var resBytes = 0L
        var withStatus = 0

        fun add(txn: NetworkTransaction) {
            attempts++
            calls += txn.callId
            if (txn.attempt > 1) laterAttempts += LaterAttempt(txn.callId, txn.attempt)
            reqBytes += txn.reqBytes
            val status = txn.status
            when {
                status != null -> {
                    withStatus++
                    resBytes += txn.resBytes
                    if (status in 200..599) byClass[status / 100 - 2]++
                    // A transport failure never has a status, so it never reaches here: a timeout is
                    // not a response time. A 3xx hop does, because somebody waited for it.
                    txn.ms?.let(::sample)
                }
                txn.error != null -> transportFailures++
                else -> inFlight++
            }
        }

        private fun sample(ms: Long) {
            if (samples == latencies.size) latencies = latencies.copyOf(samples * 2)
            if (samples > 0 && ms < latencies[samples - 1]) sorted = false
            latencies[samples++] = ms
        }

        fun row(key: RouteKey, term: String): RouteStatsRow {
            if (!sorted) {
                // Only a route that took new samples out of order since the last snapshot sorts again.
                latencies.sort(0, samples)
                sorted = true
            }
            val redirects = laterAttempts.count { redirectHops.isRedirect(it.callId, it.attempt) }
            val retries = laterAttempts.size - redirects
            val outcomes = attempts - inFlight
            val failed = byClass[2] + byClass[3] + transportFailures
            val withheld = samples < TAIL_MIN_SAMPLES
            return RouteStatsRow(
                method = key.method,
                scheme = key.scheme,
                host = key.host,
                port = key.port,
                route = key.route.text,
                routeSource = key.route.source.label,
                filterTerm = term,
                calls = calls.size,
                attempts = attempts,
                retries = retries,
                redirects = redirects,
                retried = retries > 0,
                count2xx = byClass[0],
                count3xx = byClass[1],
                count4xx = byClass[2],
                count5xx = byClass[3],
                transportFailures = transportFailures,
                inFlight = inFlight,
                outcomes = outcomes,
                rate4xx = rate(byClass[2], outcomes),
                rate5xx = rate(byClass[3], outcomes),
                transportFailureRate = rate(transportFailures, outcomes),
                errorRate = rate(failed, outcomes),
                latencySamples = samples,
                p50Ms = percentile(50),
                p90Ms = percentile(90),
                p95Ms = if (withheld) null else percentile(95),
                p99Ms = if (withheld) null else percentile(99),
                maxMs = if (samples == 0) null else latencies[samples - 1],
                tailWithheld = if (withheld) TAIL_WITHHELD else null,
                meanReqBytes = mean(reqBytes, attempts),
                meanResBytes = mean(resBytes, withStatus),
            )
        }

        /**
         * Nearest rank: rank = ⌈p·n/100⌉, the sample at that rank. Not interpolated, so it is always
         * a latency somebody actually waited, it is exact in integers, and two implementations
         * cannot round it differently.
         */
        private fun percentile(p: Int): Long? {
            if (samples == 0) return null
            val rank = (p * samples + 99) / 100
            return latencies[rank - 1]
        }
    }

    private companion object {
        /** Below this many samples p95 and p99 are a confident-looking guess, so they are withheld. */
        const val TAIL_MIN_SAMPLES = 20
        const val TAIL_WITHHELD = "n<20"
        const val KEY_MEMO_LIMIT = 50_000

        fun rate(count: Int, of: Int): Double? =
            if (of == 0) null else Math.round(count * 10_000.0 / of) / 10_000.0

        /** Rounded half up. */
        fun mean(sum: Long, of: Int): Long? = if (of == 0) null else (2 * sum + of) / (2L * of)

        /** Descending on the sort's metric, nulls last; then busier first, then by name, by char code. */
        fun order(sort: StatsSort): Comparator<RouteStatsRow> = Comparator<RouteStatsRow> { a, b ->
            val x = sort.metric(a)?.toDouble()
            val y = sort.metric(b)?.toDouble()
            when {
                x == null && y == null -> 0
                x == null -> 1
                y == null -> -1
                else -> y.compareTo(x)
            }
        }
            .thenByDescending { it.attempts }
            .thenBy { it.method }
            .thenBy { it.host }
            .thenComparator { a, b ->
                when {
                    a.port == b.port -> 0
                    a.port == null -> -1
                    b.port == null -> 1
                    else -> a.port.compareTo(b.port)
                }
            }
            .thenBy { it.route }
            .thenBy { it.scheme }
    }
}

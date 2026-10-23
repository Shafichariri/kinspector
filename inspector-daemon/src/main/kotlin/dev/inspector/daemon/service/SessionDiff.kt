package dev.inspector.daemon.service

import dev.inspector.daemon.archive.RouteKey
import kotlinx.serialization.Serializable

/**
 * Two sessions compared route by route: what `GET /api/diff`, `diff_sessions`, `inspector diff`
 * and the Diff page return. A null field is absent from the JSON (`InspectorJson` writes no explicit
 * nulls), and no non-null field has a default, so a zero is always written rather than read as
 * missing. Field meanings are in `docs/DAEMON.md` §7, "Comparing two sessions".
 */
@Serializable
data class DiffReport(
    val a: DiffSideInfo,
    val b: DiffSideInfo,
    /** `previousRun` when the daemon chose A, `given` when the caller named it. */
    val aChosen: String,
    /** Whether A and B are one app; absent when either has no metadata to say. */
    val sameApp: Boolean? = null,
    val filter: String,
    /** Routes on either side, before `limit` and `unchanged` cut [routes]. */
    val routeCount: Int,
    val counts: DiffCounts,
    val shapeBudget: ShapeBudget,
    val routes: List<DiffRoute>,
)

/** One side of a diff: the session, and the rows its cut kept. */
@Serializable
data class DiffSideInfo(
    /** The resolved folder's id, never `latest`. */
    val sessionId: String,
    val appId: String? = null,
    val appVersion: String? = null,
    val device: String? = null,
    val startedAt: String? = null,
    /** Absent while the session is still recording, or when it never recorded an end. */
    val endedAt: String? = null,
    /** The marker label this side was cut at. */
    val since: String? = null,
    /** Rows in the session: every attempt. */
    val total: Int,
    /** Rows the filter and the `since` cut kept. */
    val matched: Int,
    /** False when some part of this side's cut could not be put in front of its rows' filter terms. */
    val filterCarried: Boolean,
    /** The session's distinct marker labels, in file order, so a reader can offer `since` choices. */
    val markers: List<String>,
)

/**
 * Routes by what the diff can say of them, disjoint, so the five sum to [DiffReport.routeCount].
 * [unsure] counts routes with no change whose [DiffRoute.unsure] is set; [unchanged] the rest of
 * those with no change. A route that changed is [changed] whether or not it is also unsure.
 */
@Serializable
data class DiffCounts(val added: Int, val removed: Int, val changed: Int, val unsure: Int, val unchanged: Int)

@Serializable
data class ShapeBudget(val bodiesRead: Int, val bytesRead: Long, val limitBytes: Long, val exhausted: Boolean)

/** One route, on one side or both. `a` and every field that needs both sides are absent for a route B added. */
@Serializable
data class DiffRoute(
    val method: String,
    val scheme: String,
    val host: String,
    /** Present only when it is not the scheme's default. */
    val port: Int? = null,
    val route: String,
    val routeSource: String,
    /** `both`, `added` (in B only) or `removed` (in A only). */
    val presence: String,
    val mutation: Boolean,
    /** A mutation added, removed or called a different number of times. An order and a mark, never a severity. */
    val loud: Boolean,
    /** The change kinds, in rank order; empty for a route that did not change. */
    val changes: List<String>,
    /**
     * True when a body capture did not keep may hide a change on this route: a direction lists an
     * unsure key, or one side kept whole JSON there and the other no whole body. Absent otherwise.
     * Sets no change kind (§6.4), but such a route is never counted or dropped as unchanged.
     */
    val unsure: Boolean? = null,
    val a: RouteStatsRow? = null,
    val b: RouteStatsRow? = null,
    val status: StatusMix,
    val latency: LatencyDiff? = null,
    val requestSize: SizeDiff? = null,
    val responseSize: SizeDiff? = null,
    val requestHeaders: HeaderDiff? = null,
    val responseHeaders: HeaderDiff? = null,
    val requestShape: ShapeDiff? = null,
    val responseShape: ShapeDiff? = null,
)

/** Attempts per exact status code, and `transport`, per side. */
@Serializable
data class StatusMix(val a: Map<String, Int>? = null, val b: Map<String, Int>? = null)

@Serializable
data class LatencyDiff(
    /** `slower`, `faster`, `same` or `notJudged`. */
    val verdict: String,
    /** Why it was not judged, verbatim for a reader: `n<5`. */
    val reason: String? = null,
    val p50A: Long? = null,
    val p50B: Long? = null,
    val deltaMs: Long? = null,
    /** `round(100·Δ / p50A)`; absent when p50A is 0. */
    val deltaPct: Long? = null,
)

@Serializable
data class SizeDiff(
    /** `grew`, `shrank`, `same` or `notJudged`. */
    val verdict: String,
    val reason: String? = null,
    val meanA: Long? = null,
    val meanB: Long? = null,
)

/** Header names carried on one side and by no attempt on the other. Never a value. */
@Serializable
data class HeaderDiff(
    val added: List<String>,
    val removed: List<String>,
    /** How many each list left out, when one was cut. */
    val more: Map<String, Int>? = null,
)

/** One direction's body shape on both sides. */
@Serializable
data class ShapeDiff(
    /** False when either side has no complete sample, or a side's JSON is past what the diff walks. */
    val compared: Boolean,
    /** Why not, for a reader, verbatim: `A kept no complete JSON body here: 1 truncated`. */
    val why: String? = null,
    val a: BodyTally,
    val b: BodyTally,
    val added: List<ShapePath>,
    val removed: List<ShapePath>,
    val typeChanged: List<ShapeTypeChange>,
    /** What would have been added, removed or a type change had the side lacking it kept every body. */
    val unsure: List<ShapeUnsure>,
    /** Paths at or under one `redacted[]` names, or that hold the placeholder: never compared. */
    val notCompared: List<String>,
    val more: Map<String, Int>? = null,
)

/** Every attempt of one direction on one side, by what capture kept of its body. */
@Serializable
data class BodyTally(
    /** `json` (a complete sample), `empty` (every attempt), `notJson`, or `unknown`. */
    val kind: String,
    val complete: Int,
    val empty: Int,
    val truncated: Int,
    val omitted: Int,
    val missing: Int,
    val notJson: Int,
    val unparsed: Int,
    val unread: Int,
)

/** A path on one side only: in [in] of that side's [of] complete samples. */
@Serializable
data class ShapePath(val path: String, val types: List<String>, val `in`: Int, val of: Int)

@Serializable
data class ShapeTypeChange(val path: String, val typesA: List<String>, val typesB: List<String>)

@Serializable
data class ShapeUnsure(
    val path: String,
    /** `removed`, `added` or `typeChanged`. */
    val would: String,
    val typesA: List<String>? = null,
    val typesB: List<String>? = null,
    val inA: Int,
    val ofA: Int,
    val inB: Int,
    val ofB: Int,
)

/**
 * The comparison rules: what changed on a route, its order, and the caps on every list.
 *
 * Pure: it reads nothing. The rules are integers throughout, so no float rounds a verdict across a
 * threshold. Thresholds are constants, not config: one more `config.json` key would need a validator
 * and a Settings editor, and nobody has asked to tune them.
 */
object SessionDiff {

    /** Fewer samples than this on either side and latency or size is `notJudged`. */
    const val MIN_SAMPLES = 5
    const val NOT_JUDGED = "n<5"
    const val MIN_LATENCY_MS = 50L
    const val MIN_CHANGE_PCT = 25L
    const val MIN_SIZE_BYTES = 1024L
    const val MAX_PATHS = 200
    const val MAX_HEADERS = 100

    /** The kinds a route can have, in rank order: the first a row has is what it sorts by. */
    val KINDS = listOf(
        "removed", "added", "statusMix", "slower", "responseShape", "requestShape",
        "responseHeaders", "requestHeaders", "responseSize", "requestSize", "calls", "faster",
    )

    /** What one side holds of one route: its stats row and its diff tallies. */
    class Side(val row: RouteStatsRow, val tally: DiffSideAccumulator.RouteDiffTally)

    fun route(key: RouteKey, a: Side?, b: Side?): DiffRoute {
        val common = DiffRoute(
            method = key.method, scheme = key.scheme, host = key.host, port = key.port, route = key.route.text,
            routeSource = key.route.source.label, presence = "both", mutation = key.mutation, loud = false,
            changes = emptyList(), a = a?.row, b = b?.row,
            status = StatusMix(a?.tally?.let { outcomes(it.outcomes) }, b?.tally?.let { outcomes(it.outcomes) }),
        )
        if (a == null || b == null) {
            val presence = if (a == null) "added" else "removed"
            return common.copy(presence = presence, loud = key.mutation, changes = listOf(presence))
        }
        val latency = latency(a.row, b.row)
        val requestSize = size(a.row.meanReqBytes, b.row.meanReqBytes, a.row.attempts, b.row.attempts)
        val responseSize = size(
            a.row.meanResBytes, b.row.meanResBytes,
            a.row.outcomes - a.row.transportFailures, b.row.outcomes - b.row.transportFailures,
        )
        val requestHeaders = headers(a.tally.requestHeaders, b.tally.requestHeaders)
        val responseHeaders = headers(a.tally.responseHeaders, b.tally.responseHeaders)
        val requestShape = ShapeCompare.compare(a.tally.request, b.tally.request)
        val responseShape = ShapeCompare.compare(a.tally.response, b.tally.response)

        val kinds = HashSet<String>()
        if (a.tally.outcomes.keys != b.tally.outcomes.keys) kinds += "statusMix"
        if (latency.verdict == "slower" || latency.verdict == "faster") kinds += latency.verdict
        if (responseShape.changed) kinds += "responseShape"
        if (requestShape.changed) kinds += "requestShape"
        if (responseHeaders.added.isNotEmpty() || responseHeaders.removed.isNotEmpty()) kinds += "responseHeaders"
        if (requestHeaders.added.isNotEmpty() || requestHeaders.removed.isNotEmpty()) kinds += "requestHeaders"
        if (responseSize.verdict == "grew" || responseSize.verdict == "shrank") kinds += "responseSize"
        if (requestSize.verdict == "grew" || requestSize.verdict == "shrank") kinds += "requestSize"
        // Distinct callIds: a retry is not a second call.
        if (a.row.calls != b.row.calls) kinds += "calls"
        return common.copy(
            loud = key.mutation && "calls" in kinds,
            changes = KINDS.filter { it in kinds },
            unsure = (unsure(requestShape.diff) || unsure(responseShape.diff)).takeIf { it },
            latency = latency, requestSize = requestSize, responseSize = responseSize,
            requestHeaders = requestHeaders, responseHeaders = responseHeaders,
            requestShape = requestShape.diff, responseShape = responseShape.diff,
        )
    }

    /**
     * Whether [shape] holds something the diff would have claimed had every body been kept: an
     * unsure key, or, where nothing was compared, whole JSON on one side against no whole body on
     * the other — the feed whose prefix was all A kept. A cut `unsure` list keeps [MAX_PATHS]
     * entries, so `more` needs no reading. Both sides unknown (images omitted by content type), both
     * empty and JSON too wide on both sides are left out: nothing there was lost to capture on one
     * side only, and listing them would bury the routes that matter.
     */
    private fun unsure(shape: ShapeDiff): Boolean =
        shape.unsure.isNotEmpty() || (!shape.compared && setOf(shape.a.kind, shape.b.kind) == setOf("json", "unknown"))

    /** The routes `counts.unchanged` counts and `unchanged=false` drops: no change, and nothing unsure. */
    fun unchanged(route: DiffRoute): Boolean = route.changes.isEmpty() && route.unsure != true

    /**
     * p50, judged only with [MIN_SAMPLES] responses on both sides and a change of at least
     * [MIN_LATENCY_MS] and [MIN_CHANGE_PCT]% of A's. p50 because with 5 to 19 samples every higher
     * nearest-rank percentile is the maximum or near it.
     */
    fun latency(a: RouteStatsRow, b: RouteStatsRow): LatencyDiff {
        val p50A = a.p50Ms
        val p50B = b.p50Ms
        val delta = if (p50A != null && p50B != null) p50B - p50A else null
        val pct = if (delta != null && p50A != null && p50A != 0L) Math.round(100.0 * delta / p50A) else null
        if (a.latencySamples < MIN_SAMPLES || b.latencySamples < MIN_SAMPLES || delta == null || p50A == null) {
            return LatencyDiff("notJudged", NOT_JUDGED, p50A, p50B, delta, pct)
        }
        val verdict = when {
            delta >= MIN_LATENCY_MS && 100 * delta >= MIN_CHANGE_PCT * p50A -> "slower"
            -delta >= MIN_LATENCY_MS && 100 * -delta >= MIN_CHANGE_PCT * p50A -> "faster"
            else -> "same"
        }
        return LatencyDiff(verdict, null, p50A, p50B, delta, pct)
    }

    /** Mean bytes, on the latency rule with [MIN_SIZE_BYTES] for 50 ms. Byte counts are true even for bodies not kept. */
    fun size(meanA: Long?, meanB: Long?, nA: Int, nB: Int): SizeDiff {
        if (nA < MIN_SAMPLES || nB < MIN_SAMPLES || meanA == null || meanB == null) {
            return SizeDiff("notJudged", NOT_JUDGED, meanA, meanB)
        }
        val delta = meanB - meanA
        val magnitude = if (delta < 0) -delta else delta
        val verdict = when {
            magnitude < MIN_SIZE_BYTES || 100 * magnitude < MIN_CHANGE_PCT * meanA -> "same"
            delta > 0 -> "grew"
            else -> "shrank"
        }
        return SizeDiff(verdict, null, meanA, meanB)
    }

    fun headers(a: HeaderNames, b: HeaderNames): HeaderDiff {
        val added = (b.counts.keys - a.counts.keys).sorted()
        val removed = (a.counts.keys - b.counts.keys).sorted()
        val more = cutCounts("added" to added.size, "removed" to removed.size, max = MAX_HEADERS)
        return HeaderDiff(added.take(MAX_HEADERS), removed.take(MAX_HEADERS), more)
    }

    /** Codes ascending, `transport` last. */
    private fun outcomes(counts: Map<String, Int>): Map<String, Int> =
        counts.entries.sortedWith(compareBy({ it.key.toIntOrNull() ?: Int.MAX_VALUE }, { it.key }))
            .associateTo(LinkedHashMap()) { it.key to it.value }

    /** How many entries each list loses to [max], or null when none was cut. */
    fun cutCounts(vararg sizes: Pair<String, Int>, max: Int): Map<String, Int>? =
        sizes.filter { it.second > max }.associate { it.first to it.second - max }.ifEmpty { null }

    /**
     * Loud rows first; then by the highest-ranked kind a row has; then rows with no change that are
     * unsure, then the unchanged; ties by method, host, port (none first), route and scheme, by char
     * code, as route stats break them. In the daemon, so `limit` cuts the right rows and every
     * adapter agrees.
     */
    val ORDER: Comparator<DiffRoute> = compareBy<DiffRoute> { if (it.loud) 0 else 1 }
        .thenBy { route -> route.changes.firstOrNull()?.let(KINDS::indexOf) ?: if (route.unsure == true) KINDS.size else KINDS.size + 1 }
        .thenBy { it.method }
        .thenBy { it.host }
        .thenComparator { x, y ->
            when {
                x.port == y.port -> 0
                x.port == null -> -1
                y.port == null -> 1
                else -> x.port.compareTo(y.port)
            }
        }
        .thenBy { it.route }
        .thenBy { it.scheme }
}

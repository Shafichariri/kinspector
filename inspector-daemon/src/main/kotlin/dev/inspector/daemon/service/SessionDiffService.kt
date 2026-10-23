package dev.inspector.daemon.service

import dev.inspector.daemon.archive.IndexTail
import dev.inspector.daemon.archive.RouteKey
import dev.inspector.daemon.archive.RouteTemplates
import dev.inspector.daemon.archive.SessionLayout
import dev.inspector.daemon.archive.SessionRepository
import dev.inspector.daemon.archive.SessionStamp
import dev.inspector.model.Filter
import dev.inspector.model.FilterContext
import dev.inspector.model.FilterParseException
import dev.inspector.model.FilterParser
import dev.inspector.model.Marker
import dev.inspector.model.SessionMeta
import dev.inspector.model.SinceMarkerTerm
import java.nio.file.Path

/** What one diff was asked: `a` and `b` are session ids or `latest`, and a null `a` means "B's previous run". */
data class DiffRequest(
    val a: String? = null,
    val b: String? = null,
    val filter: String = "",
    val sinceA: String? = null,
    val sinceB: String? = null,
    val limit: Int = 1000,
    val unchanged: Boolean = true,
)

/** The service's answer; each adapter maps it, so the words are the same on every surface. */
sealed interface DiffOutcome {
    data class Ok(val report: DiffReport) : DiffOutcome
    data class NotFound(val message: String) : DiffOutcome
    data class Invalid(val message: String) : DiffOutcome
}

/**
 * Two sessions compared route by route, for REST, MCP and the CLI.
 *
 * Each side is read whole through [IndexTail] from offset 0, never `queryTransactions`, which clamps
 * to 1000 — the reason `HarTest` exists. Every row goes to the side's [RouteStatsAccumulator], so a
 * route's latency and sizes are the Stats page's numbers, and each row it keeps goes on to a
 * [DiffSideAccumulator] under the same key, for what stats do not keep.
 *
 * Cached whole, not folded: which shape claims are sure depends on every body of a route, so an
 * appended row cannot be added to a finished report. [capacity] reports are kept, keyed by the two
 * folders, the filter and the two `since` labels, and each stands while both sessions'
 * [SessionStamp] does. Two identical reads at once compute once; the second waits on the entry.
 * Memory only: a deleted session's entry is unreachable, because resolving it fails first, and it
 * ages out. The templates are fixed for the life of the process, as for route stats.
 */
class SessionDiffService(
    private val repository: SessionRepository,
    private val templates: RouteTemplates,
    private val caps: DiffCaps = DiffCaps(),
    private val capacity: Int = 4,
    /** Called once per computed report; a test counts it to tell a cached read from a recompute. */
    private val onCompute: () -> Unit = {},
) {
    private data class CacheKey(val a: String, val b: String, val filter: String, val sinceA: String?, val sinceB: String?)

    private class Entry {
        var report: DiffReport? = null
        var stamps: Pair<SessionStamp, SessionStamp>? = null
    }

    private val entries = object : LinkedHashMap<CacheKey, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, Entry>) = size > capacity
    }

    fun diff(request: DiffRequest): DiffOutcome {
        val bId = request.b?.takeIf { it.isNotBlank() } ?: LATEST
        val bDir = repository.resolve(bId) ?: return DiffOutcome.NotFound("no session '$bId' (b)")
        val bMeta = repository.readMeta(bDir)
        val aGiven = request.a?.takeIf { it.isNotBlank() }
        val aDir = if (aGiven != null) {
            repository.resolve(aGiven) ?: return DiffOutcome.NotFound("no session '$aGiven' (a)")
        } else {
            val b = idOf(bDir)
            if (bMeta == null) {
                return DiffOutcome.NotFound("session '$b' has no metadata, so its previous run cannot be found; name one with a=")
            }
            previousRun(bDir, bMeta)
                ?: return DiffOutcome.NotFound("no earlier session of app '${bMeta.appId}' before '$b'; name one with a=")
        }
        if (aDir == bDir) return DiffOutcome.Invalid("a and b are the same session '${idOf(bDir)}'")

        val filter = request.filter.trim()
        val parsed = try {
            if (filter.isEmpty()) Filter.MatchAll else FilterParser.parseOrThrow(filter)
        } catch (e: FilterParseException) {
            return DiffOutcome.Invalid(e.message ?: "invalid filter")
        }
        // Measured before anything is read, so a row or marker written during the read makes the next
        // read recompute rather than serve a report older than its stamp.
        val stamps = SessionStamp.of(aDir) to SessionStamp.of(bDir)
        val sinceA = request.sinceA?.takeIf { it.isNotBlank() }
        val sinceB = request.sinceB?.takeIf { it.isNotBlank() }
        val markersA = repository.readMarkers(aDir)
        val markersB = repository.readMarkers(bDir)
        missingMarker("A", sinceA, markersA)?.let { return DiffOutcome.Invalid(it) }
        missingMarker("B", sinceB, markersB)?.let { return DiffOutcome.Invalid(it) }

        val full = cached(CacheKey(aDir.toString(), bDir.toString(), filter, sinceA, sinceB), stamps) {
            compute(Cut(aDir, sinceA, markersA), Cut(bDir, sinceB, markersB), filter, parsed)
        }
        return DiffOutcome.Ok(view(full, if (aGiven != null) GIVEN else PREVIOUS_RUN, request.limit, request.unchanged))
    }

    /**
     * [full] as a caller asked for it: unchanged routes dropped on request, then cut to [limit], with
     * counts whole. An unsure route is never dropped: "no change" is the one answer the diff must not
     * give falsely, and the default of MCP and the CLI is to drop. [aChosen] is set here, not cached:
     * A named and A found as the previous run are the same comparison and share an entry, but not the
     * same answer to "who chose A".
     */
    private fun view(full: DiffReport, aChosen: String, limit: Int, unchanged: Boolean): DiffReport {
        val kept = if (unchanged) full.routes else full.routes.filterNot(SessionDiff::unchanged)
        if (kept === full.routes && limit >= kept.size && aChosen == full.aChosen) return full
        return full.copy(aChosen = aChosen, routes = kept.take(limit.coerceAtLeast(1)))
    }

    private fun cached(key: CacheKey, stamps: Pair<SessionStamp, SessionStamp>, compute: () -> DiffReport): DiffReport {
        val entry = synchronized(entries) { entries.getOrPut(key) { Entry() } }
        synchronized(entry) {
            entry.report?.let { if (entry.stamps == stamps) return it }
            return compute().also {
                entry.report = it
                entry.stamps = stamps
            }
        }
    }

    /** The newest session of [bMeta]'s app below B by `(startedAt, sessionId)`, compared as strings. */
    private fun previousRun(bDir: Path, bMeta: SessionMeta): Path? {
        val bKey = bMeta.startedAt to idOf(bDir)
        return repository.sessionDirs()
            .mapNotNull { dir -> repository.readMeta(dir)?.takeIf { it.appId == bMeta.appId }?.let { (it.startedAt to idOf(dir)) to dir } }
            .filter { (key, _) -> compareKeys(key, bKey) < 0 }
            .maxWithOrNull { x, y -> compareKeys(x.first, y.first) }
            ?.second
            ?.toRealPath()
    }

    private fun compareKeys(x: Pair<String, String>, y: Pair<String, String>): Int =
        x.first.compareTo(y.first).takeIf { it != 0 } ?: x.second.compareTo(y.second)

    /**
     * Refuses a `since` label the side does not hold, by the grammar's own matching (case-insensitive).
     * An empty side would read as every route removed, which is worse than a typo said out loud.
     */
    private fun missingMarker(side: String, label: String?, markers: List<Marker>): String? {
        if (label == null || FilterContext(markers).lastMarkerMono(label) != null) return null
        val labels = markers.map { it.label }.distinct()
        val held = if (labels.isEmpty()) "It has none." else "Its markers: ${labels.joinToString(", ")}."
        return "session $side has no marker '$label'. $held"
    }

    private class Cut(val dir: Path, val since: String?, val markers: List<Marker>)

    private fun compute(a: Cut, b: Cut, filter: String, parsed: Filter): DiffReport {
        onCompute()
        val budget = DiffBudget(caps.bytesBudget)
        val sideA = fold(a, parsed, budget)
        val sideB = fold(b, parsed, budget)
        val rowsA = sideA.stats.rowsByKey(termText(filter, a.since), parsed)
        val rowsB = sideB.stats.rowsByKey(termText(filter, b.since), parsed)

        val keys = LinkedHashSet<RouteKey>(rowsA.rows.keys).apply { addAll(rowsB.rows.keys) }
        val routes = keys.map { key ->
            val left = rowsA.rows[key]?.let { SessionDiff.Side(it, sideA.diff.routes.getValue(key)) }
            val right = rowsB.rows[key]?.let { SessionDiff.Side(it, sideB.diff.routes.getValue(key)) }
            SessionDiff.route(key, left, right)
        }.sortedWith(SessionDiff.ORDER)

        val metaA = repository.readMeta(a.dir)
        val metaB = repository.readMeta(b.dir)
        return DiffReport(
            a = info(a, metaA, sideA, carried(a.since, rowsA.carried)),
            b = info(b, metaB, sideB, carried(b.since, rowsB.carried)),
            // Replaced on the way out by the request's own; see [view].
            aChosen = GIVEN,
            sameApp = if (metaA != null && metaB != null) metaA.appId == metaB.appId else null,
            filter = filter,
            routeCount = routes.size,
            counts = DiffCounts(
                added = routes.count { it.presence == "added" },
                removed = routes.count { it.presence == "removed" },
                changed = routes.count { it.presence == "both" && it.changes.isNotEmpty() },
                unsure = routes.count { it.changes.isEmpty() && it.unsure == true },
                unchanged = routes.count(SessionDiff::unchanged),
            ),
            shapeBudget = ShapeBudget(budget.bodiesRead, budget.bytesRead, budget.limitBytes, budget.exhausted),
            routes = routes,
        )
    }

    private class Folded(val stats: RouteStatsAccumulator, val diff: DiffSideAccumulator)

    /**
     * One side, every row from offset 0. The `since` cut is a constructed [SinceMarkerTerm] ANDed in,
     * never pasted into the filter text, so a label holding `"` or `|` cannot rewrite the query.
     */
    private fun fold(cut: Cut, parsed: Filter, budget: DiffBudget): Folded {
        val filter = when {
            cut.since == null -> parsed
            parsed == Filter.MatchAll -> SinceMarkerTerm(cut.since)
            else -> Filter.And(listOf(parsed, SinceMarkerTerm(cut.since)))
        }
        val stats = RouteStatsAccumulator(templates, filter, FilterContext(cut.markers))
        val diff = DiffSideAccumulator(caps, budget) { txn, side -> repository.readBody(cut.dir, txn.id, side.key) }
        IndexTail.read(SessionLayout.indexFile(cut.dir), 0) { txn ->
            stats.add(txn)?.let { key -> diff.add(key, txn) }
        }
        return Folded(stats, diff)
    }

    private fun info(cut: Cut, meta: SessionMeta?, side: Folded, carried: Boolean) = DiffSideInfo(
        sessionId = idOf(cut.dir),
        appId = meta?.appId,
        appVersion = meta?.appVersion,
        device = meta?.device,
        startedAt = meta?.startedAt,
        endedAt = meta?.endedAt,
        since = cut.since,
        total = side.stats.total,
        matched = side.stats.matched,
        filterCarried = carried,
        markers = cut.markers.map { it.label }.distinct(),
    )

    private fun idOf(dir: Path): String = dir.fileName.toString()

    companion object {
        const val LATEST = "latest"
        const val GIVEN = "given"
        const val PREVIOUS_RUN = "previousRun"

        /**
         * The filter text each of a side's rows' terms starts with: the filter, then the `since` cut
         * when the tokenizer can carry its label back unchanged. Whether a label is expressible turns
         * on the parity of its quote count, so it is asked of the parser, not of the label.
         */
        fun termText(filter: String, since: String?): String {
            val term = since?.let(::sinceTerm) ?: return filter
            return if (filter.isEmpty()) term else "$filter $term"
        }

        /** Whether every part of a side's cut reached its rows' terms. */
        fun carried(since: String?, rowsCarried: Boolean): Boolean = rowsCarried && (since == null || sinceTerm(since) != null)

        private fun sinceTerm(label: String): String? {
            val text = "since:marker(\"$label\")"
            return text.takeIf { FilterParser.parse(text).getOrNull() == SinceMarkerTerm(label) }
        }
    }
}

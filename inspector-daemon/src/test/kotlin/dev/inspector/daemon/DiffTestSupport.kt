package dev.inspector.daemon

import dev.inspector.daemon.archive.RouteKey
import dev.inspector.daemon.archive.RouteTemplates
import dev.inspector.daemon.service.BodySide
import dev.inspector.daemon.service.DiffBudget
import dev.inspector.daemon.service.DiffCaps
import dev.inspector.daemon.service.DiffRoute
import dev.inspector.daemon.service.DiffSideAccumulator
import dev.inspector.daemon.service.RouteStatsAccumulator
import dev.inspector.daemon.service.SessionDiff
import dev.inspector.model.Filter
import dev.inspector.model.FilterContext
import dev.inspector.model.NetworkTransaction

/**
 * One side of a diff built in memory: rows, and the bodies a ref points at. [call] writes a row the
 * way capture does, so a body with no entry in [bodies] reads as a ref whose file is gone.
 */
class DiffSideRows {
    val rows = ArrayList<NetworkTransaction>()
    val bodies = HashMap<Pair<String, BodySide>, ByteArray>()
    private var next = 0

    fun call(
        path: String = "/v1/items",
        method: String = "GET",
        status: Int? = 200,
        ms: Long = 100,
        callId: String? = null,
        attempt: Int = 1,
        error: String? = null,
        host: String = "api.example.invalid",
        port: Int? = null,
        scheme: String = "https",
        reqHeaders: Map<String, List<String>> = emptyMap(),
        resHeaders: Map<String, List<String>> = emptyMap(),
        req: String? = null,
        res: String? = null,
        resType: String? = res?.let { "application/json" },
        resBytes: Long? = null,
        resTruncated: Boolean = false,
        resOmitted: String? = null,
        resFileGone: Boolean = false,
        resRaw: ByteArray? = null,
        redacted: List<String> = emptyList(),
    ): NetworkTransaction {
        val id = "t" + (++next).toString(16).padStart(7, '0')
        val reqBytes = req?.toByteArray()
        val resBody = resRaw ?: res?.toByteArray()
        val row = NetworkTransaction(
            id = id, ts = "2026-01-01T00:00:00.000Z", mono = next * 10L, method = method, scheme = scheme,
            host = host, port = port, path = path, status = status, error = error, ms = ms, attempt = attempt,
            callId = callId ?: "c-$id", reqBytes = reqBytes?.size?.toLong() ?: 0,
            resBytes = resBytes ?: resBody?.size?.toLong() ?: 0,
            reqHeaders = reqHeaders, resHeaders = resHeaders,
            reqBodyRef = reqBytes?.let { "bodies/$id.req" },
            resBodyRef = resBody?.takeIf { resOmitted == null }?.let { "bodies/$id.res" },
            reqContentType = reqBytes?.let { "application/json" }, resContentType = resType,
            resBodyTruncated = resTruncated, resBodyOmitted = resOmitted, redacted = redacted,
        )
        rows += row
        reqBytes?.let { bodies[id to BodySide.REQ] = it }
        if (resBody != null && !resFileGone) bodies[id to BodySide.RES] = resBody
        return row
    }
}

/** Both sides folded as `SessionDiffService` folds them, every route compared, in the daemon's order. */
fun diffInMemory(a: DiffSideRows, b: DiffSideRows, caps: DiffCaps = DiffCaps(), budget: DiffBudget = DiffBudget(caps.bytesBudget)): List<DiffRoute> {
    fun fold(side: DiffSideRows): Map<RouteKey, SessionDiff.Side> {
        val stats = RouteStatsAccumulator(RouteTemplates.NONE, Filter.MatchAll, FilterContext.EMPTY)
        val diff = DiffSideAccumulator(caps, budget) { txn, s -> side.bodies[txn.id to s] }
        for (row in side.rows) stats.add(row)?.let { diff.add(it, row) }
        val rows = stats.rowsByKey("", Filter.MatchAll).rows
        return rows.mapValues { (key, row) -> SessionDiff.Side(row, diff.routes.getValue(key)) }
    }
    val left = fold(a)
    val right = fold(b)
    return (left.keys + right.keys).map { SessionDiff.route(it, left[it], right[it]) }.sortedWith(SessionDiff.ORDER)
}

/** The one route on [path] (any method). */
fun List<DiffRoute>.on(path: String, method: String? = null): DiffRoute =
    single { it.route == path && (method == null || it.method == method) }

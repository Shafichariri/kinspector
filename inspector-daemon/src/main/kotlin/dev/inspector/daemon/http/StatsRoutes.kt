package dev.inspector.daemon.http

import dev.inspector.daemon.archive.SessionRepository
import dev.inspector.daemon.service.RouteStatsReport
import dev.inspector.daemon.service.RouteStatsService
import dev.inspector.daemon.service.StatsSort
import dev.inspector.model.FilterParseException
import dev.inspector.model.InspectorJson
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The default and the most rows one reply carries; `routeCount` says how many there were. */
private const val DEFAULT_LIMIT = 1000
private const val MAX_LIMIT = 5000

/**
 * Per-route latency and failure stats for one session. Read-only, so open like every `GET`.
 *
 * The rules are [RouteStatsService]'s and `route_stats` serves the same ones; this holds only the
 * parameters and the error replies.
 */
internal fun Route.statsRoutes(repository: SessionRepository, stats: RouteStatsService) {
    get("/api/sessions/{id}/stats") {
        val dir = call.resolveSession(repository) ?: return@get
        val parameters = call.request.queryParameters
        val sortText = parameters["sort"]
        val sort = StatsSort.parse(sortText)
            ?: return@get call.respondError(HttpStatusCode.BadRequest, StatsSort.unknown(sortText.orEmpty()))
        val limit = (parameters["limit"]?.toIntOrNull() ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        val report = try {
            // A cold read of a large session decodes every row; off the CIO workers the live socket
            // and /ingest share.
            withContext(Dispatchers.IO) { stats.report(dir, parameters["filter"].orEmpty(), sort, limit) }
        } catch (e: FilterParseException) {
            return@get call.respondError(HttpStatusCode.BadRequest, e.message ?: "invalid filter")
        }
        call.respondJson(InspectorJson.encodeToString(RouteStatsReport.serializer(), report))
    }
}

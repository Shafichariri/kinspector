package dev.inspector.daemon.http

import dev.inspector.daemon.archive.SessionRepository
import dev.inspector.daemon.service.FindingsEngine
import dev.inspector.daemon.service.FindingsReport
import dev.inspector.daemon.service.FindingsService
import dev.inspector.model.InspectorJson
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The default and the most findings one reply carries; `findingCount` says how many there were. */
private const val DEFAULT_LIMIT = 1000
private const val MAX_LIMIT = 5000

/**
 * What the findings rules flag in one session. Read-only, so open like every `GET`.
 *
 * There is no `filter=`: a finding is about calls in relation to each other, and a filter that hid
 * one member would make the other look safe. The rules are [FindingsService]'s and `list_findings`
 * serves the same ones; this holds only the parameters and the error replies.
 */
internal fun Route.findingsRoutes(repository: SessionRepository, findings: FindingsService) {
    get("/api/sessions/{id}/findings") {
        val dir = call.resolveSession(repository) ?: return@get
        val parameters = call.request.queryParameters
        val category = FindingsEngine.parseCategory(parameters["category"]).getOrElse {
            return@get call.respondError(HttpStatusCode.BadRequest, it.message.orEmpty())
        }
        val limit = (parameters["limit"]?.toIntOrNull() ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        val includeDismissed = when (parameters["includeDismissed"]) {
            null, "false" -> false
            "true" -> true
            else -> return@get call.respondError(HttpStatusCode.BadRequest, "includeDismissed must be true or false")
        }
        // A cold read of a large session decodes every row; off the CIO workers the live socket and
        // /ingest share.
        val report = withContext(Dispatchers.IO) { findings.report(dir, category, limit, includeDismissed) }
        call.respondJson(InspectorJson.encodeToString(FindingsReport.serializer(), report))
    }
}

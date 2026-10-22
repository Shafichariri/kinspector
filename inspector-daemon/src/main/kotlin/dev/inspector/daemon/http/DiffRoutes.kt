package dev.inspector.daemon.http

import dev.inspector.daemon.service.DiffMarkdown
import dev.inspector.daemon.service.DiffOutcome
import dev.inspector.daemon.service.DiffReport
import dev.inspector.daemon.service.DiffRequest
import dev.inspector.daemon.service.SessionDiffService
import dev.inspector.model.InspectorJson
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.http.Parameters
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The default and the most routes one reply carries; `routeCount` says how many there were. */
private const val DEFAULT_LIMIT = 1000
private const val MAX_LIMIT = 5000

/**
 * Two sessions compared route by route, as JSON and as Markdown. Read-only, so open like every
 * `GET`, and it carries less than `GET …/transactions`: no header or body value, no query string.
 *
 * The rules are [SessionDiffService]'s, which `diff_sessions` and `inspector diff` serve too; this
 * holds only the parameters and the mapping of the service's outcome to a status.
 */
internal fun Route.diffRoutes(diffs: SessionDiffService) {
    get("/api/diff") {
        val report = call.diffReport(diffs) ?: return@get
        call.respondJson(InspectorJson.encodeToString(DiffReport.serializer(), report))
    }
    // The same report as a file to hand on; errors stay JSON, as every route answers them.
    get("/api/diff.md") {
        val report = call.diffReport(diffs) ?: return@get
        val name = "diff-${report.a.sessionId}-vs-${report.b.sessionId}.md"
        call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"$name\"")
        call.respondText(DiffMarkdown.render(report), ContentType("text", "markdown").withCharset(Charsets.UTF_8))
    }
}

/** The report the request asks for, or null after answering its 400 or 404. */
internal suspend fun ApplicationCall.diffReport(diffs: SessionDiffService): DiffReport? {
    val request = diffRequest(request.queryParameters)
        ?: return null.also { respondError(HttpStatusCode.BadRequest, "unchanged is true or false") }
    // A cold diff reads every row of two sessions and their bodies; off the CIO workers the live socket
    // and /ingest share.
    return when (val outcome = withContext(Dispatchers.IO) { diffs.diff(request) }) {
        is DiffOutcome.Ok -> outcome.report
        is DiffOutcome.NotFound -> null.also { respondError(HttpStatusCode.NotFound, outcome.message) }
        is DiffOutcome.Invalid -> null.also { respondError(HttpStatusCode.BadRequest, outcome.message) }
    }
}

/** The query as a [DiffRequest], or null when `unchanged` is neither `true` nor `false`. */
private fun diffRequest(parameters: Parameters): DiffRequest? {
    val unchanged = when (parameters["unchanged"]) {
        null, "true" -> true
        "false" -> false
        else -> return null
    }
    return DiffRequest(
        a = parameters["a"],
        b = parameters["b"],
        filter = parameters["filter"].orEmpty(),
        sinceA = parameters["sinceA"],
        sinceB = parameters["sinceB"],
        limit = (parameters["limit"]?.toIntOrNull() ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT),
        unchanged = unchanged,
    )
}

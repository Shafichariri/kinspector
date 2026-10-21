package dev.inspector.daemon.http

import dev.inspector.daemon.archive.DaemonConfig
import dev.inspector.daemon.archive.SessionRepository
import dev.inspector.daemon.service.ExportMarkdown
import dev.inspector.daemon.service.Har
import dev.inspector.daemon.service.HtmlExport
import dev.inspector.daemon.service.SessionExport
import dev.inspector.daemon.service.SessionExportDocument
import dev.inspector.daemon.service.daemonVersion
import dev.inspector.model.FilterParseException
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

internal fun Route.exportRoutes(repository: SessionRepository, config: DaemonConfig) {
    /**
     * HAR 1.2, for sharing a session with a tool that is not Inspector.
     *
     * Takes the same `filter` as the transactions endpoint, because "export the four calls
     * that failed" is the reason to export at all; a whole session is the default. The
     * attachment header is what makes a browser save it rather than render a megabyte of JSON.
     */
    get("/api/sessions/{id}/har") {
        val dir = call.resolveSession(repository) ?: return@get
        val filter = call.request.queryParameters["filter"].orEmpty()
        val transactions = try {
            // The cap is the session, not a page: a HAR missing rows because of an unmentioned
            // default limit would be a quietly incomplete export. Not `queryTransactions`,
            // which clamps any limit to 1000 and did exactly that to sessions above it.
            repository.filterTransactions(dir, filter)
        } catch (e: FilterParseException) {
            return@get call.respondError(HttpStatusCode.BadRequest, e.message ?: "invalid filter")
        }
        val meta = repository.readMeta(dir)
        val har = Har.export(
            meta = meta,
            transactions = transactions,
            bodyOf = { txnId, side -> repository.readBody(dir, txnId, side) },
            creatorVersion = daemonVersion(),
        )
        val name = meta?.sessionId ?: dir.fileName.toString()
        call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"$name.har\"")
        call.respondText(har, ContentType.Application.Json)
    }

    /*
     * One whole session as one file: JSON for agents and tools, Markdown for a person or a
     * ticket, and HTML that is the web UI itself working from disk. See `SessionExport`.
     *
     * No `filter`, unlike HAR: the unit here is the session, and "everything that happened"
     * is the question these answer. Read-only, so no control header.
     */
    get("/api/sessions/{id}/export.json") {
        call.respondExport(repository, config, "json", ContentType.Application.Json) { SessionExport.toJson(it) }
    }
    get("/api/sessions/{id}/export.md") {
        call.respondExport(repository, config, "md", ContentType("text", "markdown").withCharset(Charsets.UTF_8)) {
            ExportMarkdown.render(it)
        }
    }
    get("/api/sessions/{id}/export.html") {
        call.respondExport(repository, config, "html", ContentType.Text.Html.withCharset(Charsets.UTF_8)) { HtmlExport.render(it) }
    }
}

/** Builds the export document once and renders it as an attachment named for the session. */
private suspend fun ApplicationCall.respondExport(
    repository: SessionRepository,
    config: DaemonConfig,
    extension: String,
    contentType: ContentType,
    render: (SessionExportDocument) -> String,
) {
    val dir = resolveSession(repository) ?: return
    val document = SessionExport.build(dir, repository, config.routeTemplates, daemonVersion())
    val name = SessionExport.fileName(document, dir, extension)
    response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"$name\"")
    respondText(render(document), contentType)
}

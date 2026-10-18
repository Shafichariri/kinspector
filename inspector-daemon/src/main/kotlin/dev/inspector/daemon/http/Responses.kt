package dev.inspector.daemon.http

import dev.inspector.daemon.archive.SessionRepository
import dev.inspector.model.InspectorJson
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import java.nio.file.Path

internal suspend fun ApplicationCall.respondJson(json: String) {
    respondText(json, ContentType.Application.Json)
}

internal suspend fun ApplicationCall.respondError(
    status: HttpStatusCode,
    message: String,
) {
    respondText(
        InspectorJson.encodeToString(mapOf("error" to message)),
        ContentType.Application.Json,
        status,
    )
}

/**
 * Resolves the `{id}` path parameter to a session directory, responding 404 and returning
 * null when it is unknown or unsafe. Callers `?: return@get`.
 */
internal suspend fun ApplicationCall.resolveSession(repository: SessionRepository): Path? {
    val id = parameters["id"].orEmpty()
    val dir = repository.resolve(id)
    if (dir == null) {
        respondError(HttpStatusCode.NotFound, "no session '$id'")
        return null
    }
    return dir
}

/**
 * Requires a header no cross-origin request can set without a preflight.
 *
 * The daemon listens on loopback with no authentication, which means *any* page the developer
 * happens to have open can POST to `127.0.0.1:8099`. A simple form or `no-cors` fetch could
 * therefore kill the daemon. A custom header forces the browser to preflight, and this server
 * answers no CORS preflight, so only its own page gets through. The read-only endpoints are
 * left alone — this guard is about not handing strangers an off switch.
 */
internal suspend fun ApplicationCall.requireControlHeader(): Boolean {
    if (request.headers["X-Inspector-Control"] == "1") return true
    respondError(
        HttpStatusCode.Forbidden,
        "control endpoints require the X-Inspector-Control: 1 header",
    )
    return false
}

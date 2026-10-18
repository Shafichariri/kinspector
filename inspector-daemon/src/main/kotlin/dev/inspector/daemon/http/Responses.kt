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

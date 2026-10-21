package dev.inspector.daemon.http

import dev.inspector.daemon.archive.SessionRepository
import dev.inspector.model.InspectorJson
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

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
 * The request's JSON object body, or null after answering 415 (not declared as JSON) or 400 (not a
 * JSON object). Callers `?: return@post`.
 *
 * The declared type is checked rather than sniffed: the page and `curl -H 'Content-Type:
 * application/json'` both say what they send, and a body that only parses by luck is not a
 * request anyone meant.
 */
internal suspend fun ApplicationCall.receiveJsonObject(): JsonObject? {
    if (!request.contentType().match(ContentType.Application.Json)) {
        respondError(HttpStatusCode.UnsupportedMediaType, "send the body as Content-Type: application/json")
        return null
    }
    val body = runCatching { Json.parseToJsonElement(receiveText()) }.getOrNull() as? JsonObject
    if (body == null) {
        respondError(HttpStatusCode.BadRequest, "the body must be a JSON object")
        return null
    }
    return body
}

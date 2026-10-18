package dev.inspector.daemon.http

import dev.inspector.daemon.archive.SessionRepository
import dev.inspector.daemon.service.LiveApps
import dev.inspector.daemon.service.SignalProviderException
import dev.inspector.daemon.service.SignalPullRequest
import dev.inspector.model.FilterParseException
import dev.inspector.model.InspectorJson
import dev.inspector.model.Signal
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.builtins.ListSerializer

internal fun Route.signalRoutes(repository: SessionRepository, liveApps: LiveApps) {
    get("/api/sessions/{id}/signals") {
        val dir = call.resolveSession(repository) ?: return@get
        // Parameters only. Filtering, the newest-first order and the clamp live in
        // querySignals, which list_signals reads too; a rule here would be a second copy of it.
        val filter = call.request.queryParameters["filter"].orEmpty()
        val tag = call.request.queryParameters["tag"]
        val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0
        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 100
        try {
            val page = repository.querySignals(dir, filter, offset, limit, tag)
            // A bare array, not the page: the web UI and the export's archiveSource read that shape.
            call.respondJson(
                InspectorJson.encodeToString(ListSerializer(Signal.serializer()), page.items)
            )
        } catch (e: FilterParseException) {
            // The parser's message names the fix; pass it through untouched.
            call.respondError(HttpStatusCode.BadRequest, e.message ?: "invalid filter")
        }
    }

    get("/api/sessions/{id}/signals/{signalId}") {
        val dir = call.resolveSession(repository) ?: return@get
        val signalId = call.parameters["signalId"].orEmpty()
        val signal = repository.readSignal(dir, signalId)
            ?: return@get call.respondError(HttpStatusCode.NotFound, "no signal $signalId")
        call.respondJson(InspectorJson.encodeToString(signal))
    }

    get("/api/sessions/{id}/signals/{signalId}/data") {
        val dir = call.resolveSession(repository) ?: return@get
        val signalId = call.parameters["signalId"].orEmpty()
        val bytes = repository.readSignalPayload(dir, signalId)
            ?: return@get call.respondError(HttpStatusCode.NotFound, "no payload for $signalId")
        // Payloads are stored as captured: JSON for structured data, a JSON string for a
        // toString() dump. One type on disk, so one content type here.
        call.respondBytes(bytes, ContentType.Application.Json)
    }

    get("/api/sessions/{id}/current") {
        val dir = call.resolveSession(repository) ?: return@get
        val tag = call.request.queryParameters["tag"]
        call.respondJson(
            InspectorJson.encodeToString(
                ListSerializer(Signal.serializer()),
                repository.currentSignals(dir, tag),
            )
        )
    }


    controlGated {
        post("/api/sessions/{id}/signals/request") {
            val id = call.parameters["id"].orEmpty()
            val sessionId = if (id == "latest") liveApps.sole()?.sessionId else id
            val connection = sessionId?.let { liveApps.forSession(it) }
                ?: return@post call.respondError(
                    HttpStatusCode.Conflict,
                    "no app is attached for session $id; a pull needs a live session, and this " +
                        "one is only on disk. Attached: ${liveApps.attachedSessionIds()}",
                )

            val body = call.receiveText()
            val request = try {
                InspectorJson.decodeFromString(SignalPullRequest.serializer(), body)
            } catch (e: Exception) {
                return@post call.respondError(
                    HttpStatusCode.BadRequest,
                    "could not read the signal request: ${e.message}",
                )
            }
            if (request.tag.isBlank() || request.name.isBlank()) {
                return@post call.respondError(
                    HttpStatusCode.BadRequest,
                    "both tag and name are required; a pull answers exactly one provider",
                )
            }

            try {
                val signal = connection.requestSignal(request.tag, request.name)
                call.respondJson(InspectorJson.encodeToString(signal))
            } catch (e: SignalProviderException) {
                // The app's own message names what is registered; pass it through untouched.
                call.respondError(HttpStatusCode.BadGateway, e.message ?: "the pull failed")
            }
        }
    }
}

package dev.inspector.daemon.http

import dev.inspector.daemon.archive.SessionRepository
import dev.inspector.daemon.service.SessionManager
import dev.inspector.model.InspectorJson
import dev.inspector.model.Marker
import dev.inspector.model.MarkerSource
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.builtins.ListSerializer

internal fun Route.markerRoutes(repository: SessionRepository, manager: SessionManager) {
    get("/api/sessions/{id}/markers") {
        val dir = call.resolveSession(repository) ?: return@get
        call.respondJson(
            InspectorJson.encodeToString(ListSerializer(Marker.serializer()), repository.readMarkers(dir))
        )
    }

    post("/api/sessions/{id}/markers") {
        val id = call.parameters["id"].orEmpty()
        val body = call.receiveText()
        val fields = runCatching {
            InspectorJson.decodeFromString<Map<String, String>>(body)
        }.getOrNull().orEmpty()
        val label = fields["label"]
            ?: return@post call.respondError(HttpStatusCode.BadRequest, "expected {\"label\":\"…\"}")

        // Who dropped it, defaulting to `agent` because that is what every caller was until
        // the web UI grew a button. `source` is the only thing separating "an agent marked
        // this while working through the session" from "a person watching it happen", and a
        // marker attributed to the wrong one sends whoever reads the archive later to the
        // wrong question. An unrecognised value is refused rather than stored: the field is
        // read by both UIs and by the MCP tools, and a fourth value would render as nothing.
        val source = fields["source"] ?: MarkerSource.AGENT
        if (source !in KNOWN_MARKER_SOURCES) {
            return@post call.respondError(
                HttpStatusCode.BadRequest,
                "source must be one of ${KNOWN_MARKER_SOURCES.joinToString(", ")}",
            )
        }

        val resolvedId = if (id == "latest") {
            repository.resolve("latest")?.fileName?.toString()
        } else id
        val active = manager.activeSessionIds()
        if (resolvedId == null || resolvedId !in active) {
            return@post call.respondError(
                HttpStatusCode.Conflict,
                "markers can only be added to a session that is currently recording",
            )
        }
        manager.append(
            resolvedId,
            Marker(
                ts = java.time.Instant.now().toString(),
                mono = 0,
                label = label,
                source = source,
            ),
        )
        call.respondJson("""{"ok":true}""")
    }
}

/**
 * The `source` values a posted marker may carry.
 *
 * Named here rather than derived from [MarkerSource]'s members, because that object is a plain set
 * of constants with no enumeration — and a reflective list would silently accept whatever a future
 * constant happened to be called.
 */
private val KNOWN_MARKER_SOURCES = setOf(MarkerSource.APP, MarkerSource.AGENT, MarkerSource.USER)

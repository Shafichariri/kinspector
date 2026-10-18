package dev.inspector.daemon.http

import dev.inspector.daemon.service.ReplayRequest
import dev.inspector.daemon.service.ReplayResult
import dev.inspector.daemon.service.Replayer
import dev.inspector.model.InspectorJson
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

/**
 * Re-sends a captured request.
 *
 * Behind the control header, and that is doing more work here than it does for stop/restart.
 * Until now the daemon only *read* an archive; this turns it into something that sends
 * arbitrary requests carrying unredacted credentials. The header forces a preflight this
 * server never answers, which is CSRF defence — it is not authentication, and the loopback
 * bind is still the only thing standing between this and any local process. Said plainly in
 * `docs/REPLAY.md` rather than left implicit.
 */
internal fun Route.replayRoutes(replayLazy: Lazy<Replayer>) {
    // Resolved on the first replay, not when the routes are built; see InspectorDaemon.
    val replay by replayLazy

    post("/api/replay") {
        if (!call.requireControlHeader()) return@post
        val body = call.receiveText()
        val request = try {
            InspectorJson.decodeFromString(ReplayRequest.serializer(), body)
        } catch (e: Exception) {
            return@post call.respondError(
                HttpStatusCode.BadRequest,
                "could not read the replay request: ${e.message}",
            )
        }

        val result = replay.replay(request)
        call.respondJson(InspectorJson.encodeToString(ReplayResult.serializer(), result))
    }
}

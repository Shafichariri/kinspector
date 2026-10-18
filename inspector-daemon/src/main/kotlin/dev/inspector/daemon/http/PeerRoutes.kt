package dev.inspector.daemon.http

import dev.inspector.daemon.archive.InspectorDaemonJson
import dev.inspector.daemon.service.KillOutcome
import dev.inspector.daemon.service.PeerDaemon
import dev.inspector.daemon.service.PeerRegistry
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.builtins.ListSerializer

/**
 * Lists and kills sibling inspector processes.
 *
 * The manual alternative is a `pkill` that is genuinely hard to get right: `inspector` also
 * matches the editor's MCP connection, and so does `inspector.*serve`, because the classpath
 * carries `ktor-server-cio-*.jar`. Both traps are in `docs/DAEMON.md`. Matching argv tokens
 * here means the UI cannot make either mistake.
 */
internal fun Route.peerRoutes(peers: PeerRegistry) {
    get("/api/peers") {
        // Serialized rather than hand-built: `dataDir` is a filesystem path and can legally
        // contain quotes and backslashes.
        call.respondJson(
            InspectorDaemonJson.encodeToString(
                ListSerializer(PeerDaemon.serializer()),
                peers.list(),
            )
        )
    }

    controlGated {
        post("/api/peers/{pid}/kill") {
            val pid = call.parameters["pid"]?.toLongOrNull()
                ?: return@post call.respondError(HttpStatusCode.BadRequest, "pid must be a number")
            val force = call.request.queryParameters["force"] == "true"

            when (val outcome = peers.kill(pid, force)) {
                is KillOutcome.Signalled ->
                    call.respondJson("""{"ok":true,"pid":$pid,"force":$force}""")
                is KillOutcome.NoSuchProcess ->
                    call.respondError(HttpStatusCode.NotFound, "no live process with pid $pid")
                is KillOutcome.Refused ->
                    call.respondError(HttpStatusCode.Conflict, outcome.reason)
            }
        }
    }
}

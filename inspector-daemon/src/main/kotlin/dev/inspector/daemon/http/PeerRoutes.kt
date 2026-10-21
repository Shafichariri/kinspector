package dev.inspector.daemon.http

import dev.inspector.daemon.archive.InspectorDaemonJson
import dev.inspector.daemon.service.KillOutcome
import dev.inspector.daemon.service.PeerDaemon
import dev.inspector.daemon.service.PeerRegistry
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

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

            val result = peers.kill(pid, force).toResult(pid)
            when (result.outcome) {
                SIGNALLED -> call.respondJson("""{"ok":true,"pid":$pid,"force":$force}""")
                NO_SUCH_PROCESS -> call.respondError(HttpStatusCode.NotFound, result.error.orEmpty())
                else -> call.respondError(HttpStatusCode.Conflict, result.error.orEmpty())
            }
        }

        /**
         * Kills the pids in `{"pids":[…],"force":false}`, answering for each in request order.
         *
         * Each pid goes through [PeerRegistry.kill] on its own, so each is re-verified as an
         * inspector process at the moment it is signalled, exactly as on the single route; a batch
         * is never a reason to trust a pid the page sent back. 200 whenever the body is well formed:
         * one status for five kills would hide the one that was refused.
         */
        post("/api/peers/kill") {
            val body = call.receiveJsonObject() ?: return@post
            val request = parseBulkKill(body)
                ?: return@post call.respondError(
                    HttpStatusCode.BadRequest,
                    "send {\"pids\":[<pid>, …],\"force\":false} with 1 to $MAX_BULK_KILL pids as " +
                        "whole numbers; force is optional",
                )
            val results = request.pids.map { pid -> peers.kill(pid, request.force).toResult(pid) }
            call.respondJson(
                InspectorDaemonJson.encodeToString(BulkKillReply.serializer(), BulkKillReply(results))
            )
        }
    }
}

/** One pid's answer. [error] is there whenever the pid was not signalled. */
@Serializable
data class PeerKillResult(
    val pid: Long,
    /** `signalled`, `noSuchProcess` or `refused`. */
    val outcome: String,
    val error: String? = null,
)

@Serializable
data class BulkKillReply(val results: List<PeerKillResult>)

private const val SIGNALLED = "signalled"
private const val NO_SUCH_PROCESS = "noSuchProcess"
private const val REFUSED = "refused"

/** A machine has a few inspector processes, not hundreds; this bounds one request's work. */
private const val MAX_BULK_KILL = 100

/**
 * The one reading of a [KillOutcome], for both routes, so the single kill's 404 and 409 bodies and
 * the bulk kill's per-pid errors are the same words.
 */
private fun KillOutcome.toResult(pid: Long): PeerKillResult = when (this) {
    is KillOutcome.Signalled -> PeerKillResult(pid, SIGNALLED)
    is KillOutcome.NoSuchProcess -> PeerKillResult(pid, NO_SUCH_PROCESS, "no live process with pid $pid")
    is KillOutcome.Refused -> PeerKillResult(pid, REFUSED, reason)
}

private class BulkKill(val pids: List<Long>, val force: Boolean)

/** The request from `{"pids":[…],"force":…}`, or null when the body is anything else. */
private fun parseBulkKill(body: JsonObject): BulkKill? {
    if ((body.keys - setOf("pids", "force")).isNotEmpty()) return null
    val pids = body["pids"] as? JsonArray ?: return null
    if (pids.isEmpty() || pids.size > MAX_BULK_KILL) return null
    val parsed = pids.map { element ->
        val primitive = element as? JsonPrimitive
        if (primitive == null || primitive.isString) return null
        primitive.longOrNull?.takeIf { it > 0 } ?: return null
    }
    val force = when (val value = body["force"]) {
        null -> false
        is JsonPrimitive -> if (value.isString) return null else value.booleanOrNull ?: return null
        else -> return null
    }
    return BulkKill(parsed.distinct(), force)
}

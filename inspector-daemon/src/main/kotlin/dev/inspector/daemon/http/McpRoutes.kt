package dev.inspector.daemon.http

import dev.inspector.daemon.service.McpControl
import dev.inspector.daemon.service.McpInfo
import dev.inspector.daemon.service.McpProbeResult
import dev.inspector.model.InspectorJson
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/**
 * MCP registration details, and a probe that proves the server answers.
 *
 * There is no start endpoint. The MCP server speaks stdio, so a process the daemon spawned
 * would have no client on its pipes; the editor spawns it on connect. Killing a wedged one
 * goes through `/api/peers/{pid}/kill`, after which the editor spawns a fresh one.
 */
internal fun Route.mcpRoutes(mcpControlLazy: Lazy<McpControl>) {
    // Resolved on the first request, not when the routes are built; see InspectorDaemon.
    val mcpControl by mcpControlLazy

    get("/api/mcp") {
        call.respondJson(InspectorJson.encodeToString(McpInfo.serializer(), mcpControl.info()))
    }

    controlGated {
        post("/api/mcp/probe") {
            val result = mcpControl.probe()
            call.respondJson(InspectorJson.encodeToString(McpProbeResult.serializer(), result))
        }
    }
}

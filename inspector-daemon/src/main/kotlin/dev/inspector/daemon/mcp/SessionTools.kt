package dev.inspector.daemon.mcp

import dev.inspector.daemon.archive.DaemonConfig
import dev.inspector.daemon.archive.SessionRepository
import dev.inspector.daemon.archive.SessionSummary
import dev.inspector.model.InspectorJsonPretty
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put

/** Which sessions exist, and a digest of one. */
internal class SessionTools(
    config: DaemonConfig,
    repository: SessionRepository,
) : ToolContext(config, repository), ToolGroup {

    override val tools: List<Tool> = listOf(
        Tool(
            tool(
                name = "list_sessions",
                description = "List recorded sessions, newest first: id, app, device, " +
                    "transaction and error counts. Use this to find a session id; " +
                    "'latest' always works without calling it.",
            ) {
                put("limit", intProperty("Maximum sessions to return. Default 20."))
            }
        ) { listSessions(it) },
        Tool(
            tool(
                name = "session_summary",
                description = "Compact digest of one session: counts by status class and host, " +
                    "the slowest calls, every error, and the marker labels. About a kilobyte. " +
                    "Start here — it answers most questions without reading any transactions.",
            ) {
                put("session", sessionProperty())
            }
        ) { sessionSummary(it) },
    )

    private fun listSessions(args: JsonObject): ToolOutcome {
        val limit = args.int("limit", default = 20, min = 1, max = 200)
        val sessions = repository.listSessions().take(limit)
        if (sessions.isEmpty()) {
            return ToolOutcome.Ok(
                "No sessions in ${config.sessionsDir}. An app records only while `inspector serve` " +
                    "is running and the app is built with the inspector wired in."
            )
        }
        return ToolOutcome.Ok(
            InspectorJsonPretty.encodeToString(
                ListSerializer(dev.inspector.model.SessionMeta.serializer()),
                sessions,
            )
        )
    }

    private fun sessionSummary(args: JsonObject): ToolOutcome {
        val dir = resolve(args) ?: return noSuchSession(args)
        val summary = repository.summarize(dir)
            ?: return ToolOutcome.Failed("session has no metadata; it may still be initialising")
        return ToolOutcome.Ok(InspectorJsonPretty.encodeToString(SessionSummary.serializer(), summary))
    }
}

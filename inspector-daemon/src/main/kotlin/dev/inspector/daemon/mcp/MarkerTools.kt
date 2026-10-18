package dev.inspector.daemon.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put

/** The one write: a marker, posted through the daemon that owns the session's writer. */
internal class MarkerTools(private val markerPoster: MarkerPoster) : ToolGroup {

    override val tools: List<Tool> = listOf(
        Tool(
            tool(
                name = "add_marker",
                description = "Drop a labelled marker into the timeline of the session that is " +
                    "recording right now, so later calls can be filtered with " +
                    "since:marker(\"label\"). Requires a running daemon.",
                required = listOf("label"),
            ) {
                put("session", sessionProperty())
                put("label", stringProperty("Short human-readable label."))
            }
        ) { addMarker(it) },
    )

    private fun addMarker(args: JsonObject): ToolOutcome {
        val label = args.string("label") ?: return ToolOutcome.Failed("'label' is required")
        val session = args.string("session") ?: "latest"
        return markerPoster.post(session, label)
    }
}

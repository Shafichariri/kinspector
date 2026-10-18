package dev.inspector.daemon.mcp

import dev.inspector.daemon.archive.DaemonConfig
import dev.inspector.daemon.archive.SessionRepository
import dev.inspector.daemon.service.ExportMarkdown
import dev.inspector.daemon.service.HtmlExport
import dev.inspector.daemon.service.SessionExport
import dev.inspector.daemon.service.SessionExportDocument
import dev.inspector.daemon.service.daemonVersion
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** File extension to renderer, in the order `export_session` writes them. */
private val EXPORT_FORMATS: Map<String, (SessionExportDocument) -> String> = linkedMapOf(
    "html" to { HtmlExport.render(it) },
    "md" to { ExportMarkdown.render(it) },
    "json" to { SessionExport.toJson(it) },
)

/** A whole session written to files a person can open. */
internal class ExportTools(
    config: DaemonConfig,
    repository: SessionRepository,
) : ToolContext(config, repository), ToolGroup {

    override val tools: List<Tool> = listOf(
        Tool(
            tool(
                name = "export_session",
                description = "Write one whole session to files a person can open: HTML (the web " +
                    "UI, working offline from disk, with every call, body, signal and marker), " +
                    "Markdown (the timeline, then every call's request and response in full) and " +
                    "JSON (everything in one document). Returns the paths. Use it to hand a " +
                    "session to someone; to read one yourself, the other tools are cheaper.",
            ) {
                put("session", sessionProperty())
                put("format", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray { EXPORT_FORMATS.keys.forEach { add(JsonPrimitive(it)) }; add(JsonPrimitive("all")) })
                    put("description", "Which file to write. Default 'all'.")
                })
                put("dir", stringProperty(
                    "Directory to write into, created if missing. Default: the 'exports' folder " +
                        "beside the archive's sessions."
                ))
            }
        ) { exportSession(it) },
    )

    /**
     * Writes the export files and names them.
     *
     * The default directory is beside `sessions/`, never inside a session folder: retention sizes
     * a session by everything under its folder, so an export there would inflate the session it
     * describes and be pruned along with it.
     */
    private fun exportSession(args: JsonObject): ToolOutcome {
        val dir = resolve(args) ?: return noSuchSession(args)
        val format = args.string("format") ?: "all"
        val formats = when (format) {
            "all" -> EXPORT_FORMATS.keys.toList()
            in EXPORT_FORMATS -> listOf(format)
            else -> return ToolOutcome.Failed(
                "unknown format '$format'. Accepted: ${(EXPORT_FORMATS.keys + "all").joinToString(", ")}."
            )
        }
        val target = args.string("dir")?.let { java.nio.file.Path.of(it) } ?: config.dataDir.resolve("exports")
        java.nio.file.Files.createDirectories(target)

        val document = SessionExport.build(dir, repository, daemonVersion())
        val written = formats.map { ext ->
            val file = target.resolve(SessionExport.fileName(document, dir, ext))
            java.nio.file.Files.writeString(file, EXPORT_FORMATS.getValue(ext)(document))
            file.toAbsolutePath()
        }
        return ToolOutcome.Ok(
            buildString {
                appendLine("Exported ${document.meta?.sessionId ?: dir.fileName}: ${document.transactions.size} calls, " +
                    "${document.signals.size} signals, ${document.markers.size} markers.")
                written.forEach { appendLine("$it  (${java.nio.file.Files.size(it)} bytes)") }
                append("The HTML opens in a browser with no daemon running.")
            }
        )
    }
}

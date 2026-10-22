package dev.inspector.daemon.mcp

import dev.inspector.daemon.archive.DaemonConfig
import dev.inspector.daemon.archive.FindingSettingsSource
import dev.inspector.daemon.archive.SessionRepository
import dev.inspector.daemon.service.FindingsEngine
import dev.inspector.daemon.service.FindingsService
import dev.inspector.daemon.service.RouteStatsService
import dev.inspector.model.FilterParseException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Lenient on input, strict on output — agents send approximate JSON. */
internal val McpJson: Json = Json { ignoreUnknownKeys = true; isLenient = true }

/** A tool either produced text for the agent, or a message explaining why it could not. */
sealed interface ToolOutcome {
    data class Ok(val text: String) : ToolOutcome
    data class Failed(val message: String) : ToolOutcome
}

/**
 * The archive as agent-callable tools.
 *
 * Reads go straight to disk through [SessionRepository], so an agent can inspect yesterday's
 * session with no daemon running. Only [MarkerTools] needs the daemon, because a marker has to
 * land in a session that is currently recording and the daemon owns those writers.
 *
 * Every tool is sized for a context window rather than a screen: summaries before rows, rows
 * before bodies, and bodies truncated unless asked otherwise.
 */
class McpTools(
    private val config: DaemonConfig,
    private val repository: SessionRepository = SessionRepository(config),
    private val markerPoster: MarkerPoster = HttpMarkerPoster(config),
    private val signalPuller: SignalPuller = HttpSignalPuller(config),
) {

    /**
     * Every tool, in the order `tools/list` reports them, grouped by what they read.
     *
     * The one list [descriptors] and [call] both read, so a tool cannot be listed without being
     * callable, or callable without being listed.
     */
    /** One engine for `list_findings` and the exports, reading the `findings` key live. */
    private val findingsEngine = FindingsEngine(FindingSettingsSource(config.dataDir), config.routeTemplates)

    private val tools: List<Tool> = listOf(
        SessionTools(config, repository),
        TransactionTools(config, repository),
        RouteStatsTool(config, repository, RouteStatsService(repository, config.routeTemplates)),
        FindingsTool(config, repository, FindingsService(repository, findingsEngine)),
        SignalTools(config, repository, signalPuller),
        MarkerTools(markerPoster),
        ExportTools(config, repository, findingsEngine),
    ).flatMap { it.tools }

    private val byName: Map<String, Tool> =
        tools.associateBy { it.descriptor.getValue("name").jsonPrimitive.content }

    fun descriptors(): JsonArray = JsonArray(tools.map { it.descriptor })

    /**
     * Argument names each tool declares, read back out of [descriptors].
     *
     * Derived from the descriptors rather than written out again, so a parameter added above
     * cannot drift out of this allowlist and start being rejected.
     */
    private val acceptedArguments: Map<String, Set<String>> by lazy {
        descriptors().associate { descriptor ->
            val obj = descriptor.jsonObject
            val properties = obj["inputSchema"]?.jsonObject?.get("properties")?.jsonObject
            obj.getValue("name").jsonPrimitive.content to (properties?.keys ?: emptySet())
        }
    }

    /**
     * Rejects arguments a tool does not declare, instead of ignoring them.
     *
     * Ignoring them produced confidently wrong answers. `list_sessions` returns the field as
     * `sessionId`, so calling another tool with `sessionId` — the obvious next step — left
     * `session` absent, defaulted it to `latest`, and returned a **different session's** data with
     * nothing to indicate the argument had been dropped. Reported from a consuming app.
     *
     * A tool that cannot honour what it was asked must say so; guessing is worse than failing.
     */
    private fun unknownArgumentError(name: String, args: JsonObject): ToolOutcome? {
        val accepted = acceptedArguments[name] ?: return null
        val unknown = args.keys.filterNot { it in accepted }
        if (unknown.isEmpty()) return null

        // The exact confusion that prompted this, named directly rather than left to be rediscovered.
        val hint = if ("sessionId" in unknown && "session" in accepted) {
            " Did you mean 'session'? list_sessions reports that field as 'sessionId'."
        } else {
            ""
        }

        return ToolOutcome.Failed(
            "unknown argument${if (unknown.size > 1) "s" else ""} for '$name': " +
                unknown.sorted().joinToString(", ") +
                ". Accepted: " + accepted.sorted().joinToString(", ") + "." + hint
        )
    }

    fun call(name: String, args: JsonObject): ToolOutcome = try {
        unknownArgumentError(name, args) ?: when (val tool = byName[name]) {
            null -> ToolOutcome.Failed("unknown tool '$name'")
            else -> tool.run(args)
        }
    } catch (e: FilterParseException) {
        // The parser's message names the fix; an agent can correct itself from it.
        ToolOutcome.Failed(e.message ?: "invalid filter")
    } catch (e: Exception) {
        ToolOutcome.Failed("${e::class.simpleName}: ${e.message}")
    }
}

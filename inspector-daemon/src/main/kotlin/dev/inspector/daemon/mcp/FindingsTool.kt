package dev.inspector.daemon.mcp

import dev.inspector.daemon.archive.DaemonConfig
import dev.inspector.daemon.archive.FindingRuleCatalog
import dev.inspector.daemon.archive.SessionRepository
import dev.inspector.daemon.service.FindingsEngine
import dev.inspector.daemon.service.FindingsReport
import dev.inspector.daemon.service.FindingsService
import dev.inspector.model.InspectorJsonPretty
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Findings, the report `GET …/findings` serves, from the same [FindingsService] rules.
 *
 * Its own process's cache. Unlike `routeTemplates`, the `findings` key is read on every call, so an
 * MCP server started before an edit agrees with the page without a restart.
 */
internal class FindingsTool(
    config: DaemonConfig,
    repository: SessionRepository,
    private val findings: FindingsService,
) : ToolContext(config, repository), ToolGroup {

    override val tools: List<Tool> = listOf(
        Tool(
            tool(
                name = "list_findings",
                description = "Findings for one session: what the daemon's rules flag in its calls, computed " +
                    "from every call each time it is asked and never written to the archive. Each finding has " +
                    "ruleId, category, severity (high, medium or info), route, message, txnIds and callIds " +
                    "(pass a txnId to get_transaction; txnCount says how many there are when txnIds is cut at " +
                    "200), and evidence lines, each about one txnId, with an optional location, path and " +
                    "masked hint. Nothing in a finding is a captured value: request bodies are compared but " +
                    "never shown, and a body capture did not keep reads \"body unknown\". rules lists every " +
                    "rule checked with its count, so count 0 means checked and found nothing; enabled false " +
                    "means config.json's findings key turned it off. coverage says, per category, what could " +
                    "not be judged. Payment rules look only at POST, PUT, PATCH and DELETE. The findings " +
                    "settings in config.json are read on every call, so this tool and the web page agree; " +
                    "ruleVersion names the settings an answer was computed under, and configError says when " +
                    "the file's findings key could not be read and the last good settings were used.",
            ) {
                put("session", sessionProperty())
                put("category", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray { FindingRuleCatalog.CATEGORIES.forEach { add(JsonPrimitive(it)) } })
                    put("description", "Only this category's rules and findings. Absent means every category.")
                })
                put("limit", intProperty("Maximum findings to return. Default 50, maximum 200; findingCount says how many there are."))
            }
        ) { listFindings(it) },
    )

    private fun listFindings(args: JsonObject): ToolOutcome {
        val dir = resolve(args) ?: return noSuchSession(args)
        val category = FindingsEngine.parseCategory(args.string("category"))
            .getOrElse { return ToolOutcome.Failed(it.message.orEmpty()) }
        val report = findings.report(dir, category, args.int("limit", default = 50, min = 1, max = 200))
        return ToolOutcome.Ok(InspectorJsonPretty.encodeToString(FindingsReport.serializer(), report))
    }
}

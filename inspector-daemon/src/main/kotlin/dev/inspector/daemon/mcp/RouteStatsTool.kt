package dev.inspector.daemon.mcp

import dev.inspector.daemon.archive.DaemonConfig
import dev.inspector.daemon.archive.SessionRepository
import dev.inspector.daemon.service.RouteStatsReport
import dev.inspector.daemon.service.RouteStatsService
import dev.inspector.daemon.service.StatsSort
import dev.inspector.model.InspectorJsonPretty
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Per-route stats, the table `GET …/stats` serves, from the same [RouteStatsService] rules.
 *
 * Its own process's cache: `inspector mcp` is started by the editor, so it reads `routeTemplates` at
 * its own start and keeps them until it is restarted.
 */
internal class RouteStatsTool(
    config: DaemonConfig,
    repository: SessionRepository,
    private val stats: RouteStatsService,
) : ToolContext(config, repository), ToolGroup {

    override val tools: List<Tool> = listOf(
        Tool(
            tool(
                name = "route_stats",
                description = "Per-route latency and failure stats for one session, computed from " +
                    "every call in it. A route is a path template: numeric, UUID, long hex and long " +
                    "base64-like segments read as {id}, and routeTemplates in the daemon's config.json " +
                    "overrides that. One row per method, host and route: calls, attempts (each redirect " +
                    "hop or retry is an attempt), retries, 4xx/5xx/transport-failure rates over attempts " +
                    "with an outcome, and p50/p90/p95/p99/max ms over attempts that got a response. p95 " +
                    "and p99 are omitted with tailWithheld \"n<20\" below 20 samples; transport failures " +
                    "never count toward latency. Each row's filterTerm, passed to list_transactions, " +
                    "returns a superset of that row's calls: the filter grammar cannot say a route " +
                    "exactly. sort: attempts (default), p50, p90, p95, p99, max, errors.",
            ) {
                put("session", sessionProperty())
                put("filter", stringProperty(
                    "Filter expression, as list_transactions takes it; only matching calls are counted. " +
                        "Empty counts every call."
                ))
                put("sort", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray { StatsSort.entries.forEach { add(JsonPrimitive(it.key)) } })
                    put("description", "Row order, descending, rows with no value last. Default 'attempts'.")
                })
                put("limit", intProperty("Maximum routes to return. Default 25, maximum 200; routeCount says how many there are."))
            }
        ) { routeStats(it) },
    )

    private fun routeStats(args: JsonObject): ToolOutcome {
        val dir = resolve(args) ?: return noSuchSession(args)
        val sortText = args.string("sort")
        val sort = StatsSort.parse(sortText) ?: return ToolOutcome.Failed(StatsSort.unknown(sortText.orEmpty()))
        val report = stats.report(
            sessionDir = dir,
            filterText = args.string("filter").orEmpty(),
            sort = sort,
            limit = args.int("limit", default = 25, min = 1, max = 200),
        )
        return ToolOutcome.Ok(InspectorJsonPretty.encodeToString(RouteStatsReport.serializer(), report))
    }
}

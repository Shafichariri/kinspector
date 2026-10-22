package dev.inspector.daemon.mcp

import dev.inspector.daemon.service.DiffOutcome
import dev.inspector.daemon.service.DiffReport
import dev.inspector.daemon.service.DiffRequest
import dev.inspector.daemon.service.SessionDiffService
import dev.inspector.model.InspectorJsonPretty
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put

/**
 * Two sessions compared route by route, the report `GET /api/diff` serves, from the same
 * [SessionDiffService] rules and with the same error messages, as results rather than JSON-RPC errors.
 *
 * Unchanged routes are left out by default and `limit` is smaller than the route's: an agent reads
 * changes, and `counts` still says how many routes did not change. Its own process's cache, as for
 * `route_stats`.
 */
internal class DiffTool(private val diffs: SessionDiffService) : ToolGroup {

    override val tools: List<Tool> = listOf(
        Tool(
            tool(
                name = "diff_sessions",
                description = "Compare two sessions route by route: b (default the latest) against a " +
                    "(default the previous session of b's app). A route is a method, host and path " +
                    "template, as route_stats uses. Reports routes added and removed, calls per route (a " +
                    "mutation called a different number of times is loud and listed first), status codes " +
                    "that appear on one side only, p50 latency judged only with 5 or more responses per " +
                    "side and a change of at least 50 ms and 25%, mean sizes, request and response header " +
                    "names (never values), and JSON key paths and types from bodies on disk. A body capture " +
                    "did not keep in full is unknown, and a key is never reported removed because of one: " +
                    "such keys are listed as unsure. filter applies to both sides; sinceA and sinceB cut a " +
                    "side at a marker. unchanged: include routes with no change (default false).",
            ) {
                put("a", stringProperty("Baseline session id, or 'latest'. Defaults to the newest earlier session of b's app."))
                put("b", stringProperty("Session under test, an id or 'latest'. Defaults to 'latest'."))
                put("filter", stringProperty(
                    "Filter expression, as list_transactions takes it, applied to both sides. Empty compares every call."
                ))
                put("sinceA", stringProperty("A marker label: compare only a's calls at or after its last marker with that label."))
                put("sinceB", stringProperty("A marker label: compare only b's calls at or after its last marker with that label."))
                put("limit", intProperty("Maximum routes to return. Default 50, maximum 200; routeCount says how many there are."))
                put("unchanged", booleanProperty("Include routes with no change. Default false; counts.unchanged says how many."))
            }
        ) { diff(it) },
    )

    private fun diff(args: JsonObject): ToolOutcome {
        val request = DiffRequest(
            a = args.string("a"),
            b = args.string("b"),
            filter = args.string("filter").orEmpty(),
            sinceA = args.string("sinceA"),
            sinceB = args.string("sinceB"),
            limit = args.int("limit", default = 50, min = 1, max = 200),
            unchanged = args.boolean("unchanged", default = false),
        )
        return when (val outcome = diffs.diff(request)) {
            is DiffOutcome.Ok -> ToolOutcome.Ok(InspectorJsonPretty.encodeToString(DiffReport.serializer(), outcome.report))
            is DiffOutcome.NotFound -> ToolOutcome.Failed(outcome.message)
            is DiffOutcome.Invalid -> ToolOutcome.Failed(outcome.message)
        }
    }
}

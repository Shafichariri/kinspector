package dev.inspector.daemon.service

import dev.inspector.daemon.service.MarkdownText.cell
import dev.inspector.daemon.service.MarkdownText.count
import dev.inspector.daemon.service.MarkdownText.heading
import dev.inspector.daemon.service.MarkdownText.inlineCode
import dev.inspector.daemon.service.MarkdownText.ms
import dev.inspector.daemon.service.MarkdownText.server

/**
 * A session diff as a document a person reads away from the page that would explain it: what was
 * compared, under which rules, then every changed route in full.
 *
 * Rendered from the [DiffReport] the JSON route returns, never recomputed, so the two agree. Cells
 * and code spans go through [MarkdownText], the one copy the session export uses, so a `|` in a
 * route cannot break a table here either. It says in its header which rules decide a change, and
 * never words an unknown body as a removed key: a shape that could not be compared reads `unknown`
 * with the daemon's reason, and an unsure key sits under its own heading, never under "removed".
 */
object DiffMarkdown {

    fun render(report: DiffReport): String = buildString {
        appendLine("# Session diff: ${heading(report.b.sessionId)} against ${heading(report.a.sessionId)}")
        appendLine()
        appendLine("- **A (before):** ${side(report.a)}")
        appendLine("- **B (after):** ${side(report.b)}")
        appendLine("- **Filter:** ${if (report.filter.isEmpty()) "none" else inlineCode(report.filter)}")
        with(report.counts) {
            appendLine("- **Routes:** ${report.routeCount} — $added added, $removed removed, $changed changed, $unchanged unchanged")
        }
        if (report.aChosen == "previousRun") appendLine("- A is the previous run of B's app.")
        appendLine()
        if (report.sameApp == false) appendLine("> A and B are different apps.")
        if (report.b.endedAt == null) {
            appendLine("> B has no recorded end; this compares the ${report.b.matched} calls it held when the diff ran.")
        }
        if (report.sameApp == false || report.b.endedAt == null) appendLine()
        appendLine(
            "A route is a method, host and path template. Latency is judged on p50 only with 5 or more " +
                "responses on each side, a change of 50 ms or more and 25% or more. A body capture did not keep — " +
                "truncated, omitted, missing or unparsed — is unknown: a key is never called removed because of it."
        )
        appendLine()
        val shown = report.routes.size
        if (shown < report.routeCount) {
            appendLine("This file lists $shown of the ${report.routeCount} routes, in the daemon's order.")
            appendLine()
        }

        val changed = report.routes.filter { it.presence == "both" && it.changes.isNotEmpty() }
        val unchanged = report.routes.filter { it.changes.isEmpty() }
        appendLine("## Changed routes")
        appendLine()
        if (changed.isEmpty()) {
            appendLine("None.")
            appendLine()
        } else {
            compared(changed, withChanges = true)
            for (route in changed) block(route)
        }
        oneSided("## Routes added", report.routes.filter { it.presence == "added" }) { it.b!! }
        oneSided("## Routes removed", report.routes.filter { it.presence == "removed" }) { it.a!! }
        appendLine("## Unchanged routes")
        appendLine()
        when {
            unchanged.isNotEmpty() -> compared(unchanged, withChanges = false)
            report.counts.unchanged > 0 -> appendLine("${count(report.counts.unchanged, "unchanged route")} not listed.").also { appendLine() }
            else -> appendLine("None.").also { appendLine() }
        }
    }

    private fun side(info: DiffSideInfo): String {
        val parts = listOfNotNull(
            listOfNotNull(info.appId, info.appVersion).joinToString(" ").ifEmpty { null },
            info.device,
            info.startedAt,
            count(info.matched, "call"),
            info.since?.let { "since marker ${inlineCode(it)}" },
        )
        return "${inlineCode(info.sessionId)} · " + parts.joinToString(" · ") { line(it) }
    }

    private fun label(route: DiffRoute): String = "${route.method} ${server(route.scheme, route.host, route.port)}${route.route}"

    /** Routes on both sides: the summary table. */
    private fun StringBuilder.compared(routes: List<DiffRoute>, withChanges: Boolean) {
        val header = if (withChanges) "| route | changes | calls A → B | status A → B | p50 A → B | response shape |"
        else "| route | calls A → B | status A → B | p50 A → B | response shape |"
        appendLine(header)
        appendLine(if (withChanges) "|---|---|---:|---|---|---|" else "|---|---:|---|---|---|")
        for (route in routes) {
            val cells = listOfNotNull(
                label(route),
                if (withChanges) changes(route) else null,
                "${route.a?.calls} → ${route.b?.calls}",
                "${status(route.status.a)} → ${status(route.status.b)}",
                latency(route.latency),
                route.responseShape?.let(::shapeCell) ?: "—",
            )
            appendLine(cells.joinToString(" | ", prefix = "| ", postfix = " |") { cell(it) })
        }
        appendLine()
    }

    private fun StringBuilder.oneSided(title: String, routes: List<DiffRoute>, row: (DiffRoute) -> RouteStatsRow) {
        appendLine(title)
        appendLine()
        if (routes.isEmpty()) {
            appendLine("None.")
            appendLine()
            return
        }
        appendLine("| route | calls | status | p50 |")
        appendLine("|---|---:|---|---|")
        for (route in routes) {
            val stats = row(route)
            val mix = route.status.a ?: route.status.b
            val cells = listOf(
                (if (route.loud) "loud · " else "") + label(route),
                "${stats.calls}",
                status(mix),
                stats.p50Ms?.let(::ms) ?: "—",
            )
            appendLine(cells.joinToString(" | ", prefix = "| ", postfix = " |") { cell(it) })
        }
        appendLine()
    }

    /** One changed route with every list the reply holds. */
    private fun StringBuilder.block(route: DiffRoute) {
        appendLine("### ${heading(label(route))}")
        appendLine()
        appendLine("- Changes: ${line(changes(route))}")
        appendLine("- Calls: ${route.a?.calls} → ${route.b?.calls}; attempts ${route.a?.attempts} → ${route.b?.attempts}")
        appendLine("- Status: ${status(route.status.a)} → ${status(route.status.b)}")
        appendLine("- p50: ${latency(route.latency)}")
        route.requestSize?.let { appendLine("- Mean request size: ${size(it)}") }
        route.responseSize?.let { appendLine("- Mean response size: ${size(it)}") }
        headers("Request header", route.requestHeaders)
        headers("Response header", route.responseHeaders)
        route.requestShape?.let { shape("Request body", it) }
        route.responseShape?.let { shape("Response body", it) }
        appendLine()
    }

    private fun StringBuilder.headers(what: String, diff: HeaderDiff?) {
        if (diff == null) return
        if (diff.added.isNotEmpty()) appendLine("- $what names only in B: ${names(diff.added, diff.more?.get("added"))}")
        if (diff.removed.isNotEmpty()) appendLine("- $what names only in A: ${names(diff.removed, diff.more?.get("removed"))}")
    }

    private fun names(list: List<String>, more: Int?): String =
        list.joinToString(", ") { inlineCode(it) } + (more?.let { " and $it more" } ?: "")

    private fun StringBuilder.shape(what: String, shape: ShapeDiff) {
        appendLine("- $what: ${line(shapeCell(shape))}; A ${tally(shape.a)}, B ${tally(shape.b)}")
        fun list(title: String, entries: List<String>, more: Int?) {
            if (entries.isEmpty()) return
            appendLine("  - $title:")
            for (entry in entries) appendLine("    - ${line(entry)}")
            more?.let { appendLine("    - and $it more") }
        }
        list("Added: only in B", shape.added.map { "${inlineCode(it.path)} ${it.types.joinToString("/")}, in ${it.`in`} of ${it.of}" }, shape.more?.get("added"))
        list("Removed: only in A", shape.removed.map { "${inlineCode(it.path)} ${it.types.joinToString("/")}, in ${it.`in`} of ${it.of}" }, shape.more?.get("removed"))
        list("Type changed", shape.typeChanged.map { "${inlineCode(it.path)} ${it.typesA.joinToString("/")} → ${it.typesB.joinToString("/")}" }, shape.more?.get("typeChanged"))
        list("Unknown — capture did not keep every body", shape.unsure.map(::unsure), shape.more?.get("unsure"))
        list("Redacted — not compared", shape.notCompared.map { inlineCode(it) }, shape.more?.get("notCompared"))
    }

    /** An unsure key, worded so it can never be read as a removal or an addition. */
    private fun unsure(entry: ShapeUnsure): String {
        val path = inlineCode(entry.path)
        return when (entry.would) {
            "removed" -> "$path is in ${entry.inA} of A's ${entry.ofA} whole bodies and none of B's ${entry.ofB}, but B has bodies capture did not keep"
            "added" -> "$path is in ${entry.inB} of B's ${entry.ofB} whole bodies and none of A's ${entry.ofA}, but A has bodies capture did not keep"
            else -> "$path is ${entry.typesA.orEmpty().joinToString("/")} in A and ${entry.typesB.orEmpty().joinToString("/")} in B, " +
                "but a side has bodies capture did not keep"
        }
    }

    /**
     * `−2 +1 ~0` when compared; the two kinds when both are known and not both JSON (`empty`,
     * `json → empty`); otherwise `unknown (1 truncated)`, naming what the unknown side held, or the
     * daemon's reason when JSON on both sides was past what the diff walks.
     */
    private fun shapeCell(shape: ShapeDiff): String {
        if (!shape.compared) {
            val a = shape.a.kind
            val b = shape.b.kind
            if (a != UNKNOWN && b != UNKNOWN && !(a == JSON && b == JSON)) return if (a == b) kindWord(a) else "${kindWord(a)} → ${kindWord(b)}"
            val reasons = listOfNotNull(
                unknownOf(shape.a).takeIf { a == UNKNOWN }?.let { if (b == UNKNOWN) "A $it" else it },
                unknownOf(shape.b).takeIf { b == UNKNOWN }?.let { if (a == UNKNOWN) "B $it" else it },
            )
            return "unknown (${reasons.ifEmpty { listOf(shape.why.orEmpty()) }.joinToString("; ")})"
        }
        val counts = "−${shape.removed.size + (shape.more?.get("removed") ?: 0)} " +
            "+${shape.added.size + (shape.more?.get("added") ?: 0)} " +
            "~${shape.typeChanged.size + (shape.more?.get("typeChanged") ?: 0)}"
        val unsure = shape.unsure.size + (shape.more?.get("unsure") ?: 0)
        return counts + if (unsure > 0) ", $unsure unknown" else ""
    }

    private fun unknownOf(tally: BodyTally): String = listOf(
        "empty" to tally.empty, "truncated" to tally.truncated, "omitted" to tally.omitted, "missing" to tally.missing,
        "not JSON" to tally.notJson, "unparsed" to tally.unparsed, "unread" to tally.unread,
    ).filter { it.second > 0 }.joinToString(", ") { "${it.second} ${it.first}" }.ifEmpty { "no response" }

    private fun tally(tally: BodyTally): String =
        "${tally.kind} (${tally.complete} whole${unknownOf(tally).takeIf { it != "no response" }?.let { ", $it" } ?: ""})"

    private fun changes(route: DiffRoute): String = (if (route.loud) listOf("loud") else emptyList<String>()).plus(route.changes).joinToString(", ")

    private fun status(mix: Map<String, Int>?): String =
        mix?.entries?.joinToString(" ") { "${it.key}×${it.value}" }?.ifEmpty { "—" } ?: "—"

    private fun latency(diff: LatencyDiff?): String {
        if (diff == null) return "—"
        val values = "${diff.p50A?.let(::ms) ?: "—"} → ${diff.p50B?.let(::ms) ?: "—"}"
        return when (diff.verdict) {
            "notJudged" -> "$values (${diff.reason})"
            "same" -> "$values (same)"
            else -> {
                val sign = if ((diff.deltaMs ?: 0) >= 0) "+" else ""
                val pct = diff.deltaPct?.let { ", $sign$it%" } ?: ""
                "$values (${diff.verdict} $sign${diff.deltaMs} ms$pct)"
            }
        }
    }

    private fun size(diff: SizeDiff): String {
        val values = "${diff.meanA?.let { "$it B" } ?: "—"} → ${diff.meanB?.let { "$it B" } ?: "—"}"
        return if (diff.verdict == "notJudged") "$values (${diff.reason})" else "$values (${diff.verdict})"
    }

    private fun kindWord(kind: String): String = if (kind == "notJson") "not JSON" else kind

    /** One line of daemon-built text in a list item: no line break may end the item early. */
    private fun line(text: String): String = text.replace("\r", "").replace("\n", " ")

    private const val UNKNOWN = "unknown"
    private const val JSON = "json"
}

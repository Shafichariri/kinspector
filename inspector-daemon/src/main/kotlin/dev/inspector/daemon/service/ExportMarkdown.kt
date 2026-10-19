package dev.inspector.daemon.service

import dev.inspector.model.BodyOmission
import dev.inspector.model.NetworkTransaction
import dev.inspector.model.Signal
import dev.inspector.model.SignalTrigger
import dev.inspector.model.prettyJson
import dev.inspector.model.timeline
import dev.inspector.model.timelineRuns
import java.util.Base64
import java.util.Locale

/**
 * A session as a document a person reads top to bottom: what the app did, in order, and then every
 * call with its request and response in full.
 *
 * Rendered from [SessionExportDocument], never from the archive directly, so it holds exactly what
 * the JSON and HTML exports hold. It is the form meant for a ticket, a chat or an agent's context,
 * which is why it says what it cannot show rather than leaving gaps to be read as facts:
 *
 * - **Why a body is absent** comes from the row's own `*BodyOmitted` and byte counts, never a guess
 *   — the guess "outside the capture allowlist" was once shown for bodies that were on disk.
 * - **What redaction removed** is stated in the header and on each call. A redacted capture handed
 *   over silently reads as "no credential was sent".
 * - **An attempt** is named as one: redirect hops and retries are separate rows sharing a `callId`.
 *
 * JSON bodies go through [prettyJson], which re-indents the captured characters and parses
 * nothing, so a 64-bit id keeps its digits and a duplicated key stays duplicated. Bodies are not
 * capped: "every request and response" is the point of the file, and capture already capped them.
 */
object ExportMarkdown {

    /** Adjacent identical observations collapse from this many, as in the web UI's timeline. */
    private const val RUN_THRESHOLD = 3

    fun render(document: SessionExportDocument): String = buildString {
        val meta = document.meta
        val calls = document.transactions
        val origin = listOfNotNull(
            calls.minOfOrNull { it.txn.mono },
            document.signals.minOfOrNull { it.mono },
            document.markers.minOfOrNull { it.mono },
        ).minOrNull() ?: 0L

        appendLine("# Inspector session ${meta?.sessionId ?: "(no metadata)"}")
        appendLine()
        if (meta != null) {
            appendLine("- **App:** ${meta.appId} ${meta.appVersion} (${meta.buildType} build)")
            appendLine("- **Device:** ${meta.device} · ${meta.platform} · OS ${meta.osVersion}")
            appendLine(
                "- **Recorded:** ${meta.startedAt} → " +
                    (meta.endedAt ?: "no recorded end (still recording, or the app went away without closing)")
            )
        }
        val failed = calls.count { it.txn.isError }
        appendLine(
            "- **Contents:** ${count(calls.size, "call")}, $failed failed · " +
                "${count(document.signals.size, "signal")} · ${count(document.markers.size, "marker")}"
        )
        appendLine("- **Exported:** ${document.exportedAt} by ${document.exporter.name} ${document.exporter.version}")
        appendLine()

        val redactedCalls = calls.count { it.txn.redacted.isNotEmpty() }
        if (redactedCalls > 0) {
            appendLine(
                "> **Redacted at capture.** $redactedCalls of ${count(calls.size, "call")} had values replaced " +
                    "before they reached the archive; each call lists what. A credential that is " +
                    "missing below was removed, not necessarily unsent."
            )
            appendLine()
        }
        appendLine(
            "Times are the device's wall clock in UTC; offsets are from the first entry on the " +
                "device's monotonic clock. A duration is measured around the whole call — there is no " +
                "send, wait or receive breakdown."
        )
        appendLine()

        renderTimeline(document, origin)
        renderCalls(document, origin)
        renderCurrent(document)
    }

    // --- timeline ------------------------------------------------------------------------------

    private fun StringBuilder.renderTimeline(document: SessionExportDocument, origin: Long) {
        appendLine("## Timeline")
        appendLine()
        val entries = timeline(
            document.transactions.map { it.txn },
            document.markers,
            document.signals,
        )
        if (entries.isEmpty()) {
            appendLine("Nothing was recorded.")
            appendLine()
            return
        }
        val numbers = callNumbers(document)
        appendLine("| offset | time (UTC) | | what |")
        appendLine("|---:|---|---|---|")
        for (run in timelineRuns(entries)) {
            val head = run.entries.first()
            if (run.entries.size >= RUN_THRESHOLD && head is dev.inspector.model.TimelineEntry.Observation) {
                val span = run.spanMs
                append("| ${offset(head.mono, origin)} | ${clock(head.signal.ts)} | signal | ")
                appendLine(cell("${head.signal.tag}/${head.signal.name} ×${run.entries.size} over ${ms(span)}") + " |")
                continue
            }
            for (entry in run.entries) appendLine(timelineRow(entry, origin, numbers))
        }
        appendLine()
    }

    private fun timelineRow(
        entry: dev.inspector.model.TimelineEntry,
        origin: Long,
        numbers: Map<String, Int>,
    ): String = when (entry) {
        is dev.inspector.model.TimelineEntry.Call -> {
            val txn = entry.txn
            val label = cell("${txn.method} ${txn.host}${txn.path} → ${outcome(txn)}")
            "| ${offset(txn.mono, origin)} | ${clock(txn.ts)} | call | " +
                "[#${numbers[txn.id]}](#call-${txn.id}) $label |"
        }
        is dev.inspector.model.TimelineEntry.Observation -> {
            val signal = entry.signal
            "| ${offset(signal.mono, origin)} | ${clock(signal.ts)} | signal | " +
                cell("${signal.tag}/${signal.name} · ${provenance(signal)}") + " |"
        }
        is dev.inspector.model.TimelineEntry.Mark -> {
            val marker = entry.marker
            "| ${offset(marker.mono, origin)} | ${clock(marker.ts)} | **marker** | " +
                cell("**${marker.label}** (${marker.source})") + " |"
        }
    }

    // --- calls ---------------------------------------------------------------------------------

    private fun StringBuilder.renderCalls(document: SessionExportDocument, origin: Long) {
        appendLine("## Calls")
        appendLine()
        if (document.transactions.isEmpty()) {
            appendLine("No calls were recorded.")
            appendLine()
            return
        }
        val attemptsByCall = document.transactions.groupBy { it.txn.callId }
        val numbers = callNumbers(document)

        for (exported in document.transactions) {
            val txn = exported.txn
            val n = numbers.getValue(txn.id)
            appendLine("<a id=\"call-${txn.id}\"></a>")
            appendLine()
            appendLine("### $n. ${heading("${txn.method} ${txn.path} → ${outcome(txn)}")}")
            appendLine()
            // `url` already carries the query; appending it again printed it twice.
            appendLine("- **URL:** ${inlineCode(txn.url)}")
            appendLine(
                "- **Started:** ${clock(txn.ts)} UTC (${offset(txn.mono, origin)}) · " +
                    "**Duration:** ${txn.ms?.let(::ms) ?: "none — still in flight when the session ended"}"
            )
            appendLine("- **Id:** ${inlineCode(txn.id)}")
            val attempts = attemptsByCall.getValue(txn.callId)
            if (attempts.size > 1) {
                val others = attempts.filter { it.txn.id != txn.id }
                    .joinToString(", ") { "[#${numbers[it.txn.id]}](#call-${it.txn.id})" }
                appendLine(
                    "- **Attempt ${txn.attempt}** of call ${inlineCode(txn.callId)}, which made " +
                        "${attempts.size} attempts (redirects or retries); the others are $others"
                )
            }
            txn.error?.let { appendLine("- **Failed before a response:** ${inlineCode(it)}") }
            if (txn.redacted.isNotEmpty()) {
                appendLine("- **Redacted at capture:** ${txn.redacted.joinToString(", ") { inlineCode(it) }}")
            }
            appendLine()

            appendLine("#### Request")
            appendLine()
            renderSide(txn.reqHeaders, exported.reqBody, Side.of(txn, request = true))
            appendLine("#### Response")
            appendLine()
            if (txn.status == null && txn.error != null) {
                appendLine("No response: the call failed before one arrived.")
                appendLine()
            } else {
                renderSide(txn.resHeaders, exported.resBody, Side.of(txn, request = false))
            }
        }
    }

    /** The parts of a row that describe one side's body, so the request and response read alike. */
    private data class Side(
        val bytes: Long,
        val contentType: String?,
        val omitted: String?,
        val truncated: Boolean,
        val ref: String?,
    ) {
        companion object {
            fun of(txn: NetworkTransaction, request: Boolean) = if (request) {
                Side(txn.reqBytes, txn.reqContentType, txn.reqBodyOmitted, txn.reqBodyTruncated, txn.reqBodyRef)
            } else {
                Side(txn.resBytes, txn.resContentType, txn.resBodyOmitted, txn.resBodyTruncated, txn.resBodyRef)
            }
        }
    }

    private fun StringBuilder.renderSide(
        headers: Map<String, List<String>>,
        body: ExportedBody?,
        side: Side,
    ) {
        if (headers.isEmpty()) {
            appendLine("No headers recorded.")
        } else {
            fenced(headers.flatMap { (name, values) -> values.map { "$name: $it" } }.joinToString("\n"), "http")
        }
        appendLine()

        val type = side.contentType?.let { " ${inlineCode(it)}" }.orEmpty()
        when {
            body != null && body.encoding == ExportedBody.BASE64 -> {
                val captured = Base64.getDecoder().decode(body.text).size
                appendLine(
                    "Body$type: binary, ${bytes(side.bytes)}${truncatedNote(side, captured.toLong())} — " +
                        "not shown here; the JSON export carries it base64-encoded."
                )
            }
            body != null -> {
                val captured = body.text.encodeToByteArray().size.toLong()
                appendLine("Body$type, ${bytes(side.bytes)}${truncatedNote(side, captured)}:")
                appendLine()
                val language = languageOf(side.contentType, body.text)
                fenced(if (language == "json") prettyJson(body.text) else body.text, language)
            }
            else -> appendLine(absentBody(side, type))
        }
        appendLine()
    }

    private fun truncatedNote(side: Side, captured: Long): String =
        if (side.truncated) " (truncated at capture: the first ${bytes(captured)} are below)" else ""

    /** Says what the row recorded about a missing body, and nothing it did not. */
    private fun absentBody(side: Side, type: String): String = when (side.omitted) {
        BodyOmission.CONTENT_TYPE ->
            "Body$type: not captured — its content type is outside the capture allowlist. ${bytes(side.bytes)} were sent."
        BodyOmission.STREAMING ->
            "Body$type: not captured — it was streamed, so its ${bytes(side.bytes)} were counted and never held."
        BodyOmission.DISCARDED ->
            "Body: not captured — the client discarded this attempt before reading its body, as it " +
                "does for a redirect hop or a retried attempt. That does not mean it was empty."
        null -> when {
            side.ref != null -> "Body$type: captured as ${inlineCode(side.ref)}, but the archive no longer holds the file."
            side.bytes == 0L -> "No body."
            else -> "Body$type: ${bytes(side.bytes)}, not in the archive."
        }
        else -> "Body$type: not captured (${inlineCode(side.omitted)}). ${bytes(side.bytes)}."
    }

    // --- current -------------------------------------------------------------------------------

    private fun StringBuilder.renderCurrent(document: SessionExportDocument) {
        if (document.current.isEmpty()) return
        appendLine("## What the app held at the end")
        appendLine()
        appendLine(
            "The last observation of each signal. A pushed one is what the app said at that moment, " +
                "not a reading taken at the end — check its time. Payloads are in the JSON export."
        )
        appendLine()
        for (signal in document.current.sortedWith(compareBy({ it.tag }, { it.name }))) {
            appendLine("- ${inlineCode("${signal.tag}/${signal.name}")} · ${provenance(signal)} at ${clock(signal.ts)} UTC")
        }
        appendLine()
    }

    // --- formatting ----------------------------------------------------------------------------

    /** Numbered in time order, which is also the order of the sections. */
    private fun callNumbers(document: SessionExportDocument): Map<String, Int> =
        document.transactions.withIndex().associate { (i, exported) -> exported.txn.id to i + 1 }

    private fun outcome(txn: NetworkTransaction): String = when {
        txn.status != null -> "${txn.status}" + (txn.ms?.let { " · ${ms(it)}" } ?: "")
        txn.error != null -> "failed"
        else -> "in flight"
    }

    private fun provenance(signal: Signal): String =
        if (signal.trigger == SignalTrigger.Request) "pulled" else "pushed"

    private fun languageOf(contentType: String?, text: String): String {
        val type = contentType?.lowercase().orEmpty()
        val trimmed = text.trimStart()
        return when {
            "json" in type -> "json"
            "xml" in type -> "xml"
            "html" in type -> "html"
            type.isEmpty() && (trimmed.startsWith("{") || trimmed.startsWith("[")) -> "json"
            else -> ""
        }
    }

    /**
     * A fence one backtick longer than the longest run inside, so a body that itself contains a
     * Markdown fence cannot close this one early and spill the rest of the document into it.
     */
    private fun StringBuilder.fenced(text: String, language: String) {
        val longest = Regex("`+").findAll(text).maxOfOrNull { it.value.length } ?: 0
        val fence = "`".repeat(maxOf(3, longest + 1))
        appendLine("$fence$language")
        appendLine(text)
        appendLine(fence)
    }

    /** Inline code that survives a value containing backticks, by the same longer-delimiter rule. */
    private fun inlineCode(text: String): String {
        val longest = Regex("`+").findAll(text).maxOfOrNull { it.value.length } ?: 0
        val ticks = "`".repeat(longest + 1)
        val pad = if (text.startsWith("`") || text.endsWith("`")) " " else ""
        return "$ticks$pad$text$pad$ticks"
    }

    /** A table cell cannot hold a pipe or a line break. */
    private fun cell(text: String): String =
        text.replace("\\", "\\\\").replace("|", "\\|").replace("\n", " ").replace("\r", "")

    private fun heading(text: String): String = text.replace("\n", " ").replace("#", "\\#")

    /** `HH:mm:ss.SSS` out of the ISO-8601 `ts`, or the raw value when it is not in that shape. */
    private fun clock(ts: String): String =
        Regex("""T(\d{2}:\d{2}:\d{2}(?:\.\d{1,3})?)""").find(ts)?.groupValues?.get(1) ?: ts

    private fun offset(mono: Long, origin: Long): String {
        val delta = mono - origin
        return "+%d.%03d s".format(Locale.ROOT, delta / 1000, delta % 1000)
    }

    private fun count(n: Int, noun: String): String = if (n == 1) "1 $noun" else "$n ${noun}s"

    private fun ms(value: Long): String =
        if (value < 1000) "$value ms" else "%.2f s".format(Locale.ROOT, value / 1000.0)

    /** Fixed to [Locale.ROOT]: a document handed between machines must not change its decimal point. */
    private fun bytes(value: Long): String = when {
        value < 1024 -> "$value B"
        value < 1024 * 1024 -> "%.1f KB".format(Locale.ROOT, value / 1024.0)
        else -> "%.1f MB".format(Locale.ROOT, value / (1024.0 * 1024.0))
    }
}


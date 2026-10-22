package dev.inspector.daemon.service

import dev.inspector.model.NetworkTransaction
import java.util.Locale

/**
 * Text that goes into a Markdown document the daemon writes: the session export and the session
 * diff. One copy, so a `|` in a route is escaped the same way in both files, a size reads the same
 * in both and an IPv6 server is bracketed by one rule; moved here verbatim out of [ExportMarkdown].
 */
internal object MarkdownText {

    /** Inline code that survives a value containing backticks, by the same longer-delimiter rule. */
    fun inlineCode(text: String): String {
        val longest = Regex("`+").findAll(text).maxOfOrNull { it.value.length } ?: 0
        val ticks = "`".repeat(longest + 1)
        val pad = if (text.startsWith("`") || text.endsWith("`")) " " else ""
        return "$ticks$pad$text$pad$ticks"
    }

    /** A table cell cannot hold a pipe or a line break. */
    fun cell(text: String): String =
        text.replace("\\", "\\\\").replace("|", "\\|").replace("\n", " ").replace("\r", "")

    fun heading(text: String): String = text.replace("\n", " ").replace("#", "\\#")

    fun count(n: Int, noun: String): String = if (n == 1) "1 $noun" else "$n ${noun}s"

    fun ms(value: Long): String =
        if (value < 1000) "$value ms" else "%.2f s".format(Locale.ROOT, value / 1000.0)

    /**
     * A route's server as a call label names it, from `NetworkTransaction.hostPort`, so an IPv6 host
     * is bracketed by the one rule rather than a copy here. Joined by hand it read `::1:8080`. The
     * route is put in a transaction's shape because the rule is a property of one; a public free
     * function in `:inspector-model` would be API for the sake of this cell.
     */
    fun server(scheme: String, host: String, port: Int?): String = NetworkTransaction(
        id = "", ts = "", mono = 0, method = "GET", scheme = scheme, host = host, port = port, path = "", callId = "",
    ).hostPort

    /** Fixed to [Locale.ROOT]: a document handed between machines must not change its decimal point. */
    fun bytes(value: Long): String = when {
        value < 1024 -> "$value B"
        value < 1024 * 1024 -> "%.1f KB".format(Locale.ROOT, value / 1024.0)
        else -> "%.1f MB".format(Locale.ROOT, value / (1024.0 * 1024.0))
    }
}

package dev.inspector.daemon.service

import java.util.Locale

/**
 * Text that goes into a Markdown document the daemon writes: the session export and the session
 * diff. One copy, so a `|` in a route is escaped the same way in both files and a size reads the
 * same in both; moved here verbatim out of [ExportMarkdown].
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

    /** Fixed to [Locale.ROOT]: a document handed between machines must not change its decimal point. */
    fun bytes(value: Long): String = when {
        value < 1024 -> "$value B"
        value < 1024 * 1024 -> "%.1f KB".format(Locale.ROOT, value / 1024.0)
        else -> "%.1f MB".format(Locale.ROOT, value / (1024.0 * 1024.0))
    }
}

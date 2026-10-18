package dev.inspector.repochecks

/**
 * Just enough Markdown for the decision and guide checks: paragraphs and code.
 *
 * A paragraph is a run of non-blank lines. A fenced block is one paragraph even when it holds blank
 * lines, and is marked so a check can skip it: a command or a sample inside a fence is not prose,
 * and a `**` at the start of a fenced line is not a decision.
 */
object Markdown {

    class Paragraph(val text: String, val fenced: Boolean) {
        /** Whitespace collapsed, so a paragraph re-wrapped at another width still compares equal. */
        val normalised: String get() = text.trim().replace(WHITESPACE, " ")
    }

    private val WHITESPACE = Regex("\\s+")
    private val FENCE = Regex("^ {0,3}```")
    private val CODE_SPAN = Regex("`[^`\n]*`")

    fun paragraphs(text: String): List<Paragraph> {
        val out = mutableListOf<Paragraph>()
        val buffer = mutableListOf<String>()
        fun flush() {
            if (buffer.isNotEmpty()) out += Paragraph(buffer.joinToString("\n"), fenced = false)
            buffer.clear()
        }
        val lines = text.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                FENCE.containsMatchIn(line) -> {
                    flush()
                    val fence = mutableListOf(line)
                    i++
                    while (i < lines.size && !FENCE.containsMatchIn(lines[i])) fence += lines[i++]
                    if (i < lines.size) fence += lines[i]
                    out += Paragraph(fence.joinToString("\n"), fenced = true)
                }
                line.isBlank() -> flush()
                else -> buffer += line
            }
            i++
        }
        flush()
        return out
    }

    /** The text with fenced blocks and inline code spans removed: what a reader sees as prose. */
    fun prose(text: String): String =
        paragraphs(text).filterNot { it.fenced }.joinToString("\n\n") { it.text.replace(CODE_SPAN, "") }
}

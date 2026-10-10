package dev.inspector.model

/**
 * Minimal JSON pretty-printer.
 *
 * Hand-rolled rather than parse-and-re-serialize so it also formats bodies that are truncated or
 * malformed — exactly the bodies you most want to look at. It never parses, so it also cannot do
 * what a parse-and-reserialise round trip does to a body: round a 64-bit id through a double, or
 * keep only the last of a duplicated key. The output is the input's characters, re-indented.
 *
 * Lives here rather than in `:inspector-ui` because the overlay and the daemon's Markdown export
 * both render bodies for people to read, and two copies of a formatter are two formatters.
 */
fun prettyJson(raw: String, indent: String = "  "): String {
    val out = StringBuilder(raw.length + raw.length / 4)
    var depth = 0
    var inString = false
    var escaped = false

    for (char in raw) {
        if (inString) {
            out.append(char)
            when {
                escaped -> escaped = false
                char == '\\' -> escaped = true
                char == '"' -> inString = false
            }
            continue
        }
        when (char) {
            '"' -> { inString = true; out.append(char) }
            '{', '[' -> {
                out.append(char)
                depth++
                out.append('\n').append(indent.repeat(depth))
            }
            '}', ']' -> {
                depth--
                out.append('\n').append(indent.repeat(maxOf(depth, 0))).append(char)
            }
            ',' -> out.append(char).append('\n').append(indent.repeat(depth))
            ':' -> out.append(": ")
            ' ', '\n', '\t', '\r' -> Unit
            else -> out.append(char)
        }
    }
    return out.toString()
}

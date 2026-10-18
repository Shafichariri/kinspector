package dev.inspector.repochecks

import kotlin.test.Test
import kotlin.test.fail

/**
 * No deferred-work markers in source. Work not done goes in `docs/ROADMAP.md`, where somebody
 * reads it when deciding what is next; a marker in a file is read only by whoever next opens that
 * file. The rule was a convention before this, and the landing page carried a marker anyway.
 *
 * Markdown is exempt because the docs discuss the rule itself. The marker words are built from
 * halves so this file does not trip its own check.
 */
class NoTodoTest {

    private val markers = listOf("TO" + "DO", "FIX" + "ME")
    // Case-sensitive and whole-word: `todos` in prose and `fixmeUp` in an identifier are not markers.
    private val pattern = Regex("\\b(" + markers.joinToString("|") + ")\\b")

    @Test
    fun no_marker_in_any_tracked_source_file() {
        val hits = RepoFiles.text
            .filterNot { it.path.endsWith(".md") }
            .flatMap { file ->
                file.text.lineSequence().mapIndexedNotNull { i, line ->
                    if (pattern.containsMatchIn(line)) "${file.path}:${i + 1}: ${line.trim()}" else null
                }.toList()
            }
        if (hits.isNotEmpty()) {
            fail(
                "Deferred-work markers in source. Do the work, or move it into docs/ROADMAP.md and delete the marker:\n  " +
                    hits.joinToString("\n  ")
            )
        }
    }
}

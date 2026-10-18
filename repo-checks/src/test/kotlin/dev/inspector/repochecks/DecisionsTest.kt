package dev.inspector.repochecks

import java.io.File
import kotlin.test.Test
import kotlin.test.fail

/**
 * One copy of each decision. The root `AGENTS.md` once held every decision and said "there is
 * exactly one copy to keep current"; splitting them into `docs/decisions/` and adding a guide per
 * module keeps that promise only if a second copy is caught. A pasted paragraph is the failure this
 * exists for: it reads as harmless, and the two copies then drift until they disagree.
 *
 * A decision is a paragraph outside fences that starts with `**`, in `docs/decisions/` or in any
 * `AGENTS.md`. Its lead key is the bold text, normalised, so a lead copied with a reworded body is
 * still the same decision. The whole-paragraph check is separate because a copied body without its
 * lead has no key to collide on.
 *
 * A paragraph is compared with blockquote and list markers stripped, so a decision pasted as a
 * quote or a bullet is still caught; a quoted paste into a guide also counts as a decision there.
 *
 * Lead keys are not checked repo-wide: other documents repeat short bold labels ("Action:", "Done
 * when:") as structure, and those are not decisions.
 */
class DecisionsTest {

    private val decisionFiles = RepoFiles.text.filter { DECISION_FILE.matches(it.path) }
    private val guides = RepoFiles.text.filter { it.path == "AGENTS.md" || it.path.endsWith("/AGENTS.md") }
    private val root = guides.singleOrNull { it.path == "AGENTS.md" } ?: fail("no root AGENTS.md")

    private fun decisions(file: RepoFiles.TextFile) =
        Markdown.paragraphs(file.text).filter { !it.fenced && it.unquoted.startsWith("**") }

    private fun leadKey(paragraph: Markdown.Paragraph): String {
        val rest = paragraph.unquoted.removePrefix("**")
        val end = rest.indexOf("**").let { if (it < 0) rest.length else it }
        return rest.substring(0, end).replace("`", "").lowercase()
            .replace(Regex("\\s+"), " ").trim().trimEnd('.', ':', '—', ' ')
    }

    @Test
    fun every_decision_file_holds_at_least_one_decision() {
        // A guard that scans nothing must fail: with no decision files, every check below passes.
        if (decisionFiles.isEmpty()) fail("No docs/decisions/*.md found; refusing to check nothing.")
        val empty = decisionFiles.filter { decisions(it).isEmpty() }.map { it.path }
        if (empty.isNotEmpty()) {
            fail("Decision files with no decision in them (a paragraph starting with `**`):\n  " + empty.joinToString("\n  "))
        }
    }

    @Test
    fun lead_keys_are_unique_across_decision_files_and_guides() {
        val seen = (decisionFiles + guides).flatMap { file -> decisions(file).map { leadKey(it) to file.path } }
        val repeated = seen.groupBy({ it.first }, { it.second }).filterValues { it.size > 1 }
        if (repeated.isNotEmpty()) {
            fail(
                "The same decision lead appears more than once. Keep one copy, in the docs/decisions/ file for its area:\n  " +
                    repeated.entries.joinToString("\n  ") { (key, paths) -> "\"$key\" in ${paths.joinToString(", ")}" }
            )
        }
    }

    @Test
    fun module_guides_hold_no_decisions() {
        val offenders = guides.filter { it.path != "AGENTS.md" }.flatMap { file ->
            decisions(file).map { "${file.path}: ${it.unquoted.lineSequence().first().take(80)}" }
        }
        if (offenders.isNotEmpty()) {
            fail(
                "A module guide names decision files and imports them; it never holds a decision itself. " +
                    "Move these into docs/decisions/ and quote the lead in plain text if the guide must mention it:\n  " +
                    offenders.joinToString("\n  ")
            )
        }
    }

    @Test
    fun every_decision_paragraph_exists_exactly_once_in_the_docs() {
        // Every paragraph of a decision file after its header, and every root decision. Short ones
        // are left out: a one-line paragraph can legitimately recur, and the leads cover those.
        val anchors = decisionFiles.flatMap { file ->
            Markdown.paragraphs(file.text).drop(1).filterNot { it.fenced }.map { it.normalised to file.path }
        } + decisions(root).map { it.normalised to root.path }
        val counts = RepoFiles.text
            .filter { it.path.endsWith(".md") || it.path.endsWith(".mdc") }
            .flatMap { file -> Markdown.paragraphs(file.text).map { it.normalised to file.path } }
            .groupBy({ it.first }, { it.second })
        val wrong = anchors.filter { it.first.length >= MIN_ANCHOR_CHARS }.mapNotNull { (text, home) ->
            val where = counts[text].orEmpty()
            if (where.size == 1) null else "${where.size} copies (${where.ifEmpty { listOf(home) }.joinToString(", ")}): ${text.take(100)}…"
        }.distinct()
        if (wrong.isNotEmpty()) {
            fail(
                "A decision paragraph must exist exactly once across the Markdown in this repository. " +
                    "Delete the copy and point at the decision file instead:\n  " + wrong.joinToString("\n  ")
            )
        }
    }

    @Test
    fun the_root_indexes_every_decision_file_and_guides_name_only_real_ones() {
        // The index section only: a path mentioned elsewhere in the root is not an index row.
        val index = root.text.substringAfter(INDEX_HEADING, missingDelimiterValue = "")
            .substringBefore("\n## ").substringBefore("\n---\n")
        if (index.isBlank()) fail("The root AGENTS.md has no section headed ${INDEX_HEADING.trim()}.")
        val rows = index.lines().filter { it.startsWith("| `docs/decisions/") }
        val unindexed = decisionFiles.map { it.path }.filterNot { path -> rows.any { it.startsWith("| `$path` |") } }
        val named = guides.flatMap { file -> DECISION_PATH.findAll(file.text).map { file.path to it.value }.toList() }
        val missing = named.filterNot { File(RepoFiles.root, it.second).isFile }.map { "${it.first} names ${it.second}" }
        val problems = unindexed.map { "not in the root AGENTS.md index: $it" } + missing
        if (problems.isNotEmpty()) fail("Decision index out of step:\n  " + problems.joinToString("\n  "))
    }

    private companion object {
        val DECISION_FILE = Regex("docs/decisions/[^/]+\\.md")
        val DECISION_PATH = Regex("docs/decisions/[A-Za-z0-9_-]+\\.md")
        const val MIN_ANCHOR_CHARS = 80
        const val INDEX_HEADING = "\n## Non-obvious decisions — do not \"fix\" these\n"
    }
}

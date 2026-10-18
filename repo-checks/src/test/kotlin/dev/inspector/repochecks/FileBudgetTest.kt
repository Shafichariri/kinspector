package dev.inspector.repochecks

import java.io.File
import kotlin.test.Test
import kotlin.test.fail

/**
 * The file-size ratchet in `structure/budgets.txt`.
 *
 * `app.js`, `Server.kt`, `McpTools.kt` and the two overlay files each have a phase in ROADMAP §5
 * that splits them. Until it lands, a budgeted file may not grow. It may not quietly shrink
 * either: a budget left at the old size after a file lost 200 lines would let those 200 back in
 * unnoticed, so a smaller file fails until the budget is tightened to match. That is the
 * difference between a ratchet and a cap.
 *
 * Regenerating only ever lowers or removes a budget. Raising one, or adding a new file to the
 * list, is a hand edit with a `#` reason on the line, because it is a decision to let something
 * get bigger and the diff should say who decided and why.
 */
class FileBudgetTest {

    private class Budget(val path: String, val lines: Int, val raw: String)

    private fun budgets(file: File): List<Budget> = file.readLines()
        .filterNot { it.isBlank() || it.trimStart().startsWith("#") }
        .map { raw ->
            val fields = raw.substringBefore('#').trim().split(Regex("\\s+"))
            val lines = fields.getOrNull(0)?.toIntOrNull()
            if (fields.size != 2 || lines == null) fail("budgets.txt: expected `<lines> <path>  # reason`, got: $raw")
            Budget(fields[1], lines, raw)
        }

    @Test
    fun no_budgeted_file_has_grown_or_shrunk_and_no_large_file_is_unbudgeted() {
        val budgetFile = File(RepoFiles.root, "structure/budgets.txt")
        if (!budgetFile.isFile) fail("missing structure/budgets.txt")
        val budgets = budgets(budgetFile)
        budgets.groupBy { it.path }.filterValues { it.size > 1 }.keys.takeIf { it.isNotEmpty() }?.let {
            fail("budgets.txt lists these more than once: ${it.joinToString()}")
        }
        val sizes = RepoFiles.text
            .filter { it.path.substringAfterLast('.', "") in EXTENSIONS }
            .associate { it.path to it.lineCount }
        val budgeted = budgets.associateBy { it.path }

        val grew = budgets.filter { (sizes[it.path] ?: 0) > it.lines }
            .map { "${it.path}: ${sizes[it.path]} lines, budget ${it.lines}" }
        val shrank = budgets.filter { it.path in sizes && sizes.getValue(it.path) < it.lines }
        val gone = budgets.filter { it.path !in sizes }
        val unbudgeted = sizes.filter { (path, n) -> n > LIMIT && path !in budgeted }
            .map { (path, n) -> "$path: $n lines" }

        if (RepoFiles.regenerating() && (shrank.isNotEmpty() || gone.isNotEmpty())) {
            val drop = gone.map { it.raw } + shrank.filter { sizes.getValue(it.path) <= LIMIT }.map { it.raw }
            val lower = shrank.filter { sizes.getValue(it.path) > LIMIT }.associate { it.raw to sizes.getValue(it.path) }
            val rewritten = budgetFile.readLines().mapNotNull { line ->
                when {
                    line in drop -> null
                    line in lower -> line.replaceFirst(Regex("^\\s*\\d+"), lower.getValue(line).toString())
                    else -> line
                }
            }
            budgetFile.writeText(rewritten.joinToString("\n") + "\n")
            println("Tightened ${lower.size} budget(s) and removed ${drop.size} in ${budgetFile.path}")
        }

        val problems = buildList {
            if (grew.isNotEmpty()) add("Grew past its budget -- split it, or raise the budget by hand with a `#` reason:\n  " + grew.joinToString("\n  "))
            if (unbudgeted.isNotEmpty()) add("Over $LIMIT lines with no budget -- split it, or add an entry by hand with a `#` reason:\n  " + unbudgeted.joinToString("\n  "))
            if (!RepoFiles.regenerating()) {
                if (shrank.isNotEmpty()) add(
                    "Smaller than its budget -- tighten the budget so the lines cannot come back:\n  " +
                        shrank.joinToString("\n  ") { "${it.path}: ${sizes[it.path]} lines, budget ${it.lines}" } +
                        "\n  ./gradlew :repo-checks:test -Dinspector.structure.regenerate=true"
                )
                if (gone.isNotEmpty()) add(
                    "Budgeted, but no such file -- remove the entry:\n  " + gone.joinToString("\n  ") { it.path } +
                        "\n  ./gradlew :repo-checks:test -Dinspector.structure.regenerate=true"
                )
            }
        }
        if (problems.isNotEmpty()) fail(problems.joinToString("\n\n"))
    }

    private companion object {
        /** Lines, not bytes: what a reviewer pays for is lines to read. */
        const val LIMIT = 600
        val EXTENSIONS = setOf("kt", "kts", "js", "css", "html", "sh", "swift")
    }
}

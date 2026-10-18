package dev.inspector.repochecks

import java.io.File
import kotlin.test.Test
import kotlin.test.fail

/**
 * The module graph, held to `structure/modules.txt`.
 *
 * The graph is correct today and nothing checked it (audit finding 8). The edges that matter are
 * the ones that would quietly change what a consumer receives: the daemon starting to depend on
 * capture code, a noop picking up a real module, `:inspector-model` growing a project dependency.
 * Each of those is one line in a build file and was invisible until now.
 */
class ModuleGraphTest {

    @Test
    fun the_module_graph_matches_the_golden_file() {
        val actual = structure()
        val file = File(RepoFiles.root, "structure/modules.txt")
        if (RepoFiles.regeneratingModules()) {
            file.writeText(HEADER + actual.joinToString("\n") + "\n")
            println("Regenerated ${file.path} with ${actual.size} lines")
            return
        }
        val expected = RepoFiles.golden("modules.txt")
        val missing = expected - actual.toSet()
        val extra = actual - expected.toSet()
        if (missing.isEmpty() && extra.isEmpty()) return
        fail(
            buildString {
                appendLine("The module graph differs from structure/modules.txt.")
                if (extra.isNotEmpty()) appendLine("\nIn the build, not in the file:\n  " + extra.joinToString("\n  "))
                if (missing.isNotEmpty()) appendLine("\nIn the file, not in the build:\n  " + missing.joinToString("\n  "))
                appendLine("\nIf the change is intended, regenerate and commit the file:")
                append("  ./gradlew :repo-checks:test -Dinspector.modules.regenerate=true")
            }
        )
    }

    companion object {
        /** What the build passed in: `module` and `edge` lines, computed by Gradle. */
        fun structure(): List<String> {
            val raw = System.getProperty("inspector.structure")
                ?: fail("inspector.structure is not set; run these through `./gradlew :repo-checks:test`.")
            val lines = raw.split('|').filter { it.isNotBlank() }
            // An empty graph would match an empty file and pass.
            if (lines.none { it.startsWith("module ") }) fail("the build reported no modules; refusing to check nothing")
            return lines
        }

        private val HEADER = """
            |# The module graph: which modules publish, and every project-to-project edge with the
            |# configuration it was declared in. Generated, then reviewed like code -- a diff here is a
            |# change to what a consumer receives. Samples are left out: their edges flip under
            |# -Pinspector=off, which the release-swap CI job covers.
            |#
            |#   ./gradlew :repo-checks:test -Dinspector.modules.regenerate=true
            |
        """.trimMargin() + "\n"
    }
}

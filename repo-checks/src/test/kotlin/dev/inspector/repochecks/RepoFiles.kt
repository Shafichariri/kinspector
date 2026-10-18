package dev.inspector.repochecks

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.fail

/**
 * The repository as these checks see it: what git tracks plus what it would track, read once.
 *
 * Untracked-but-not-ignored files are included on purpose. A 700-line file written and not yet
 * added is exactly the one a budget should stop before it is committed, and `build/` and
 * `node_modules/` stay out because `.gitignore` already says so.
 */
object RepoFiles {

    val root: File by lazy {
        val path = System.getProperty("inspector.repoRoot")
            ?: fail("inspector.repoRoot is not set; run these through `./gradlew :repo-checks:test`.")
        File(path).also { require(File(it, "settings.gradle.kts").isFile) { "not the repo root: $it" } }
    }

    /** One readable text file: its path relative to the root, with `/` separators, and its lines. */
    class TextFile(val path: String, val text: String) {
        /** Counted as `wc -l` counts a newline-terminated file, plus a final unterminated line. */
        val lineCount: Int
            get() = text.count { it == '\n' } + if (text.isNotEmpty() && !text.endsWith('\n')) 1 else 0
    }

    /**
     * Every text file. Symlinks are skipped (`CLAUDE.md` and `.cursorrules` point at `AGENTS.md`,
     * which is read once under its own name), and so is anything holding a NUL byte, which is how
     * git itself tells binary from text.
     *
     * A git failure or an empty listing fails rather than returning nothing: every check below is
     * a loop over this list, and an empty loop passes.
     */
    val text: List<TextFile> by lazy {
        val process = ProcessBuilder("git", "ls-files", "-z", "--cached", "--others", "--exclude-standard")
            .directory(root)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
        if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
            fail("git ls-files failed in $root:\n$output")
        }
        val files = output.split('\u0000').filter { it.isNotEmpty() }.distinct().mapNotNull { path ->
            val file = File(root, path)
            // `--cached` lists a tracked file deleted from the working tree; there is nothing to read.
            if (Files.isSymbolicLink(file.toPath()) || !file.isFile) return@mapNotNull null
            val bytes = file.readBytes()
            if (bytes.contains(0)) null else TextFile(path, bytes.toString(Charsets.UTF_8))
        }
        if (files.isEmpty()) fail("git ls-files listed no text files in $root; refusing to check nothing")
        files
    }

    /** `-Dinspector.modules.regenerate=true`: rewrite `structure/modules.txt` from the build. */
    fun regeneratingModules(): Boolean = System.getProperty("inspector.modules.regenerate") == "true"

    /** `-Dinspector.budgets.tighten=true`: lower or remove budgets in `structure/budgets.txt`. */
    fun tighteningBudgets(): Boolean = System.getProperty("inspector.budgets.tighten") == "true"

    /** The structure golden files' lines, comments and blanks dropped. */
    fun golden(name: String): List<String> {
        val file = File(root, "structure/$name")
        if (!file.isFile) fail("missing structure/$name")
        return file.readLines().map { it.trim() }.filterNot { it.isEmpty() || it.startsWith("#") }
    }
}

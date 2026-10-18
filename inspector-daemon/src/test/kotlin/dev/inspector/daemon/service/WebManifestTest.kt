package dev.inspector.daemon.service

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * `web/scripts.txt` and `web/styles.txt` are the page, so a file the list and the folder disagree
 * about is a build failure here rather than a page that quietly loads less than it should.
 *
 * Each direction fails differently in a browser, and neither fails loudly there. A listed file that
 * is not bundled is a 404 on one `<script src>` and a page with a piece missing. A file in `web/js`
 * that is not listed is never loaded, so a `fetch(` written in it escapes the static network count,
 * which reads only what the manifest lists, and a stale copy left behind ships in the jar for any
 * reader still on the old path to pass against. So the folder and the list must be equal, both
 * ways, recursively.
 *
 * It reads the source folder, passed in as `inspector.webRoot` and declared as a test input, and
 * the built classpath beside it, so a change to either re-runs it without a rerun flag.
 *
 * Every rule has its own test and its own message, because each red proof should say which rule
 * broke rather than leave the reader to work out which of five it was.
 */
class WebManifestTest {

    private val root: File = System.getProperty("inspector.webRoot")
        ?.let(::File)
        ?: error("inspector.webRoot is not set; it is passed by :inspector-daemon:test, see build.gradle.kts")

    /** One manifest, the folder it lists, and the shape its entries must have. */
    private class Manifest(val name: String, val folder: String, val entry: Regex)

    private val manifests = listOf(
        Manifest(WebPage.SCRIPTS_MANIFEST, "js", Regex("""^js/\d\d-[a-z0-9-]+\.js$""")),
        Manifest(WebPage.STYLES_MANIFEST, "css", Regex("""^css/\d\d-[a-z0-9-]+\.css$""")),
    )

    private fun entries(manifest: Manifest): List<String> {
        val file = File(root, manifest.name)
        if (!file.isFile) fail("web/${manifest.name} does not exist")
        return WebPage.entries(file.readText())
    }

    @Test
    fun every_entry_resolves_on_the_built_classpath() {
        val problems = manifests.flatMap { manifest ->
            entries(manifest).filter { webResource(it) == null }
                .map { "web/${manifest.name} lists '$it', which is not on the built classpath" }
        }
        if (problems.isNotEmpty()) fail("(a) a listed file is not bundled:\n  " + problems.joinToString("\n  "))
    }

    @Test
    fun the_folder_holds_exactly_what_the_manifest_lists() {
        val problems = manifests.flatMap { manifest ->
            val folder = File(root, manifest.folder)
            if (!folder.isDirectory) return@flatMap listOf("web/${manifest.folder}/ does not exist")
            val listed = entries(manifest)
            // Recursive, so `js/sub/x.js` is a file the folder holds and the manifest does not.
            val onDisk = folder.walkTopDown().filter { it.isFile && it.name != FINDER_METADATA }
                .map { "${manifest.folder}/" + it.relativeTo(folder).invariantSeparatorsPath }
                .toSortedSet()
            listOfNotNull(if (listed.isEmpty()) "web/${manifest.name} lists no files" else null) +
                (onDisk - listed.toSet()).map { "web/$it is in the folder but web/${manifest.name} does not list it" } +
                (listed.toSet() - onDisk).map { "web/${manifest.name} lists '$it', which is not in the folder" }
        }
        if (problems.isNotEmpty()) fail("(b) the folder and the manifest disagree:\n  " + problems.joinToString("\n  "))
    }

    @Test
    fun every_entry_has_the_numbered_shape() {
        val problems = manifests.flatMap { manifest ->
            entries(manifest).mapNotNull { entry ->
                when {
                    entry != entry.trim() -> "web/${manifest.name}: '$entry' has stray whitespace"
                    !manifest.entry.matches(entry) -> "web/${manifest.name}: '$entry' does not match ${manifest.entry}"
                    else -> null
                }
            }
        }
        if (problems.isNotEmpty()) fail("(c) an entry is not a numbered file:\n  " + problems.joinToString("\n  "))
    }

    @Test
    fun entries_are_strictly_ascending_and_unique() {
        for (manifest in manifests) {
            val listed = entries(manifest)
            // A duplicate gets its own message: it loads a file twice, and a `const` declared twice
            // across classic scripts is a SyntaxError reported on the second copy.
            val duplicates = listed.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            assertTrue(duplicates.isEmpty(), "(d) web/${manifest.name} lists the same file twice: $duplicates")
            // The number is the load order, so the list must read in the order it runs.
            val outOfOrder = listed.zipWithNext().filter { (a, b) -> a >= b }
            assertTrue(
                outOfOrder.isEmpty(),
                "(d) web/${manifest.name} is not in ascending order: " +
                    outOfOrder.joinToString { (a, b) -> "'$a' before '$b'" },
            )
        }
    }

    @Test
    fun the_web_root_holds_only_the_shell_the_manifests_and_their_folders() {
        val expected = setOf("index.html", "scripts.txt", "styles.txt", "js/", "css/", "AGENTS.md", "CLAUDE.md")
        val actual = root.listFiles().orEmpty().filter { it.name != FINDER_METADATA }
            .map { if (it.isDirectory) "${it.name}/" else it.name }.toSet()
        // A leftover `app.js` or `style.css` here would ship in the jar, and anything still reading
        // the old path would pass against a stale copy.
        assertEquals(expected, actual, "(e) web/ holds something other than the shell, the manifests and js/ css/")
    }

    private companion object {
        /**
         * The one name both folder checks skip. Finder writes it into any folder it opens, so without
         * this the build goes red on a Mac for looking at `web/`. Skipped by exact name, not as a
         * dotfile: any other hidden file is something somebody put there, and still fails. Gradle's
         * default excludes keep it out of the jar, so it is never a file the page could load — and out
         * of this task's input fingerprint, which is why one turned the build red only when the test
         * re-ran for some other reason.
         */
        const val FINDER_METADATA = ".DS_Store"
    }
}

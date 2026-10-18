package dev.inspector.daemon.service

/**
 * The web UI's own files, read from the jar. One loader for the page the daemon serves and the
 * page it exports, so an export can never be built from different files than the ones served.
 */
internal fun webResource(name: String): ByteArray? =
    WebPage::class.java.classLoader.getResourceAsStream("web/$name")?.use { it.readBytes() }

/**
 * The page shell and the two manifests that say which files make up the page.
 *
 * `index.html` names no script or stylesheet. It holds two slots, [STYLES_SLOT] in `<head>` and
 * [SCRIPTS_SLOT] before `</body>`, and `web/styles.txt` and `web/scripts.txt` list the files in
 * load order. This is the only Kotlin that reads those lists: `GET /` expands the slots into tags
 * and the routes serve exactly the files listed, and [HtmlExport] expands them into inlined code.
 * A second reader would be a second idea of what the page is, and the export and the served page
 * would be free to disagree.
 *
 * Nothing serves or fetches a manifest. The browser sees ordinary tags, so there is no runtime
 * read of the list and no extra request through the page's one `fetch`.
 *
 * `scripts/render-web-ui.js` expands the same two slot literals from the working tree; change one
 * side and the other must follow. Both refuse to proceed when an expansion misses, because a
 * `replace` that matches nothing is silent and ships a page with no code in it.
 *
 * No Ktor here: the routes in `http/` call this, and `service/` may not import the server.
 */
object WebPage {

    const val STYLES_SLOT = "<!-- inspector:styles -->"
    const val SCRIPTS_SLOT = "<!-- inspector:scripts -->"
    const val SCRIPTS_MANIFEST = "scripts.txt"
    const val STYLES_MANIFEST = "styles.txt"

    /** Reads a file under `web/` as text; the default for every function here. */
    val bundled: (name: String) -> String? = { webResource(it)?.decodeToString() }

    /**
     * A manifest's entries in order. A line that is blank or starts with `#` is skipped; any other
     * line is an entry **exactly as written**. Trimming here would make `js/00-app.js ` resolve,
     * and the stray space would then be a fact `WebManifestTest` could not see — so it is kept, the
     * file does not resolve, and the test names the whitespace.
     */
    fun entries(manifest: String): List<String> =
        manifest.lines().filterNot { it.isBlank() || it.trimStart().startsWith("#") }

    /** The scripts `web/scripts.txt` lists, in load order. Refuses a missing or empty manifest. */
    fun scripts(resource: (String) -> String? = bundled): List<String> = listed(SCRIPTS_MANIFEST, resource)

    /** The stylesheets `web/styles.txt` lists, in cascade order. Refuses a missing or empty manifest. */
    fun styles(resource: (String) -> String? = bundled): List<String> = listed(STYLES_MANIFEST, resource)

    private fun listed(manifest: String, resource: (String) -> String?): List<String> {
        val text = checkNotNull(resource(manifest)) { "missing bundled resource web/$manifest" }
        // An empty list is a page with no code, or no styles, which loads and draws nothing.
        return entries(text).also { check(it.isNotEmpty()) { "web/$manifest lists no files" } }
    }

    /**
     * Fills both slots. Each must occur exactly once: a missing slot is a page that loads nothing,
     * and a second one would load every file twice — a `const` redeclared across classic scripts is
     * a `SyntaxError`, and the browser would report it on the copy, far from the cause.
     */
    fun expand(index: String, styles: String, scripts: String): String {
        for (slot in listOf(STYLES_SLOT, SCRIPTS_SLOT)) {
            val count = Regex.fromLiteral(slot).findAll(index).count()
            check(count == 1) { "index.html must hold $slot exactly once, and holds it $count time(s)" }
        }
        // Spliced by position in the shell, not by two `replace` calls: a second `replace` would
        // also rewrite a slot literal that happened to occur inside the first one's inlined text.
        val parts = listOf(index.indexOf(STYLES_SLOT) to STYLES_SLOT, index.indexOf(SCRIPTS_SLOT) to SCRIPTS_SLOT)
            .sortedBy { it.first }
        val fill = mapOf(STYLES_SLOT to styles, SCRIPTS_SLOT to scripts)
        return buildString {
            var at = 0
            for ((position, slot) in parts) {
                append(index, at, position)
                append(fill.getValue(slot))
                at = position + slot.length
            }
            append(index, at, index.length)
        }
    }

    /** `GET /`: the shell with one `<link>` per stylesheet and one `<script src>` per script. */
    fun servedIndex(resource: (String) -> String? = bundled): String {
        val index = checkNotNull(resource("index.html")) { "missing bundled resource web/index.html" }
        return expand(
            index,
            styles = styles(resource).joinToString("\n  ") { "<link rel=\"stylesheet\" href=\"/$it\">" },
            scripts = scripts(resource).joinToString("\n  ") { "<script src=\"/$it\"></script>" },
        )
    }
}

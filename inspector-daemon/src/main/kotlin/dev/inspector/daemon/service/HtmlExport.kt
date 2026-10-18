package dev.inspector.daemon.service

/**
 * One session as a single HTML file that **is the web UI**, opened from disk with no daemon.
 *
 * Not a report written for the purpose. It is the served `index.html` with every file in
 * `web/styles.txt` and `web/scripts.txt` inlined in manifest order, one `<style>` or `<script>`
 * each, and the session goes in beside them as `window.INSPECTOR_ARCHIVE`. The page sees that
 * global and answers every request from it instead of the network (its `archiveSource`), so the
 * file shows the same list, timeline, time axis, waterfall, signal browsers, detail pane and JSON
 * tree the live page does — drawn by the same code. A purpose-built report would be a second
 * rendering of the rows, and every second rendering in this repository has drifted from the first.
 *
 * What the file cannot do is what needs a daemon: the filter grammar is evaluated server-side and
 * has no JavaScript mirror, and replay, markers, pulls and process control all send something.
 * Archive mode hides or disables those and says why; see `enterArchiveMode` in the web UI.
 *
 * ## Embedding the data safely
 *
 * The document is JSON written into a `<script>`, and a body is arbitrary text: a response
 * containing `</script>` would end the script early and turn the rest of the capture into markup.
 * Every `<` is therefore written as `\u003c`. A `<` can only occur inside a JSON string, where that
 * escape means the same character to both a JSON and a JavaScript parser, so one rule removes
 * `</script>` and `<!--` alike. U+2028 and U+2029 are escaped too: legal in JSON strings, and line
 * terminators to older JavaScript engines.
 */
object HtmlExport {

    private const val TITLE_TAG = "<title>Inspector</title>"

    /*
     * Sequences that end or derail an inline element. In a script, `</script` closes it and the rest
     * of the code becomes markup; `<!--` followed later by `<script` puts the HTML tokenizer into its
     * double-escaped state, where the real `</script>` no longer closes anything. In a stylesheet,
     * `</style` is the one that closes it. None occur today; each is refused per file, naming it,
     * because the alternative is an export that loads and draws nothing.
     */
    private val BREAKS_SCRIPT = listOf("</script", "<!--", "<script")
    private val BREAKS_STYLE = listOf("</style")

    fun render(
        document: SessionExportDocument,
        resource: (name: String) -> String? = WebPage.bundled,
    ): String {
        val index = checkNotNull(resource("index.html")) { "missing bundled resource web/index.html" }
        val styles = inlinable(WebPage.styles(resource), WebPage.STYLES_MANIFEST, BREAKS_STYLE, resource)
        val scripts = inlinable(WebPage.scripts(resource), WebPage.SCRIPTS_MANIFEST, BREAKS_SCRIPT, resource)

        val data = scriptSafe(SessionExport.json.encodeToString(SessionExportDocument.serializer(), document))
        val title = document.meta?.sessionId?.let { "<title>${htmlText(it)} · Inspector</title>" } ?: TITLE_TAG

        // Each file's text goes in exactly as it is, with nothing added inside the element, so
        // `render-web-ui-archive.js` can hold every inlined script and stylesheet equal to its file.
        return WebPage.expand(
            index.replace(TITLE_TAG, title),
            styles = styles.joinToString("\n") { "<style>$it</style>" },
            // Data before code: the first script reads the global as it starts. Both go where the
            // slot was, at the end of <body>, so the DOM exists when the scripts run.
            scripts = (listOf("<script>window.INSPECTOR_ARCHIVE = $data;</script>") +
                scripts.map { "<script>$it</script>" }).joinToString("\n"),
        )
    }

    /** Every listed file's text, in order, refusing one that is missing or would break inlining. */
    private fun inlinable(
        files: List<String>,
        manifest: String,
        breaks: List<String>,
        resource: (String) -> String?,
    ): List<String> = files.map { file ->
        val text = checkNotNull(resource(file)) { "web/$manifest lists $file, which is not a bundled resource" }
        val lower = text.lowercase()
        breaks.firstOrNull { it in lower }?.let { error("$file contains $it and cannot be inlined") }
        text
    }

    /** See the class doc: valid JSON and valid JavaScript, with no way to close the script. */
    internal fun scriptSafe(json: String): String =
        json.replace("<", "\\u003c").replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")

    private fun htmlText(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}

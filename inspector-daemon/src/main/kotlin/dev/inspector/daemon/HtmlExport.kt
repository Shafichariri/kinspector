package dev.inspector.daemon

/**
 * The web UI's own files, read from the jar. One loader for the page the daemon serves and the
 * page it exports, so an export can never be built from a different `app.js` than the one served.
 */
internal fun webResource(name: String): ByteArray? =
    HtmlExport::class.java.classLoader.getResourceAsStream("web/$name")?.use { it.readBytes() }

/**
 * One session as a single HTML file that **is the web UI**, opened from disk with no daemon.
 *
 * Not a report written for the purpose. `index.html`, `style.css` and `app.js` are inlined as they
 * are served, and the session goes in beside them as `window.INSPECTOR_ARCHIVE`. `app.js` sees that
 * global and answers every request from it instead of the network (its `archiveSource`), so the
 * file shows the same list, timeline, time axis, waterfall, signal browsers, detail pane and JSON
 * tree the live page does — drawn by the same code. A purpose-built report would be a second
 * rendering of the rows, and every second rendering in this repository has drifted from the first.
 *
 * What the file cannot do is what needs a daemon: the filter grammar is evaluated server-side and
 * has no JavaScript mirror, and replay, markers, pulls and process control all send something.
 * Archive mode hides or disables those and says why; see `enterArchiveMode` in `app.js`.
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

    /*
     * The two tags replaced below. `scripts/render-web-ui.js` replaces the same two literals to
     * build its snapshot; change one side and the other must follow. Both sides check that the
     * replacement happened, because a missed `replace` is otherwise silent.
     */
    const val STYLE_TAG = "<link rel=\"stylesheet\" href=\"/style.css\">"
    const val SCRIPT_TAG = "<script src=\"/app.js\"></script>"
    private const val TITLE_TAG = "<title>Inspector</title>"

    fun render(
        document: SessionExportDocument,
        resource: (name: String) -> String? = { webResource(it)?.decodeToString() },
    ): String {
        val index = checkNotNull(resource("index.html")) { "missing bundled resource web/index.html" }
        val css = checkNotNull(resource("style.css")) { "missing bundled resource web/style.css" }
        val js = checkNotNull(resource("app.js")) { "missing bundled resource web/app.js" }

        // Refuse to build a broken file rather than ship one. Each of these is a page that would
        // load and render nothing, or render the capture as markup.
        check(STYLE_TAG in index) { "index.html no longer links style.css as $STYLE_TAG" }
        check(SCRIPT_TAG in index) { "index.html no longer loads app.js as $SCRIPT_TAG" }
        check("</style" !in css.lowercase()) { "style.css contains </style and cannot be inlined" }
        check("</script" !in js.lowercase()) { "app.js contains </script and cannot be inlined" }

        val data = scriptSafe(SessionExport.json.encodeToString(SessionExportDocument.serializer(), document))
        val title = document.meta?.sessionId?.let { "<title>${htmlText(it)} · Inspector</title>" } ?: TITLE_TAG

        return index
            .replace(TITLE_TAG, title)
            .replace(STYLE_TAG, "<style>\n$css\n</style>")
            // Data before code: app.js reads the global as it starts. Both go where the tag was,
            // at the end of <body>, so the DOM exists when the script runs.
            .replace(SCRIPT_TAG, "<script>window.INSPECTOR_ARCHIVE = $data;</script>\n<script>\n$js\n</script>")
    }

    /** See the class doc: valid JSON and valid JavaScript, with no way to close the script. */
    internal fun scriptSafe(json: String): String =
        json.replace("<", "\\u003c").replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")

    private fun htmlText(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}

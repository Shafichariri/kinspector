package dev.inspector.ui

import androidx.compose.ui.graphics.Color
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The overlay and the web UI draw one palette, and this is what holds them to it.
 *
 * "Keep them in step" was a comment on both sides for as long as there were two sides, which means
 * it held exactly as long as every change remembered both files. A 500 that is one red on the phone
 * and another in the browser reads as two different claims about the same row.
 *
 * Reads the web tokens out of `web/css/00-tokens.css` rather than from a copy, so the stylesheet
 * stays the authority for the web and this test is the only place the two meet. It finds that file
 * through `inspector.webStyles`, the path of `web/styles.txt`, which `:inspector-ui`'s `jvmTest`
 * passes with the stylesheets declared as inputs. Without that declaration a CSS-only change left
 * this task up to date and green; and without the property it fails rather than guess a relative
 * path, because a guessed path is how a test quietly reads a stale or missing copy.
 *
 * Reading one file is only sound while the tokens live only there. A checked token declared again in
 * a later stylesheet wins the cascade in the browser while this test goes on comparing the copy in
 * `00-tokens.css`, so the web would drift with this test green. The third test holds that.
 */
class PaletteParityTest {

    /** Every stylesheet `web/styles.txt` lists, in order, by its manifest entry. */
    private val sheets: Map<String, String> = run {
        val manifest = System.getProperty("inspector.webStyles")?.let(::File)
            ?: error("inspector.webStyles is not set; :inspector-ui:jvmTest passes it, see build.gradle.kts")
        assertTrue(manifest.isFile, "inspector.webStyles names $manifest, which is not a file")
        // The same rule `WebPage.entries` reads the manifest with in the daemon.
        val listed = manifest.readLines().filterNot { it.isBlank() || it.trimStart().startsWith("#") }
        assertTrue(TOKENS in listed, "web/styles.txt no longer lists $TOKENS, where the palette tokens live")
        listed.associateWith { File(manifest.parentFile, it).readText() }
    }

    private val css: String = sheets.getValue(TOKENS)

    /** The `:root` block before the light media query, and the one inside it. */
    private fun block(light: Boolean): String {
        val media = css.indexOf("@media (prefers-color-scheme: light)")
        assertTrue(media > 0, "the light theme block moved; this test has to follow it")
        val scope = if (light) css.substring(media) else css.substring(0, media)
        val start = scope.indexOf(":root")
        val open = scope.indexOf('{', start)
        return scope.substring(open + 1, scope.indexOf('}', open))
    }

    private fun token(block: String, name: String): Color {
        val raw = Regex("""--$name:\s*([^;]+);""").find(block)?.groupValues?.get(1)?.trim()
            ?: error("--$name is not declared in this block")
        val hex = Regex("""^#([0-9a-fA-F]{6})$""").find(raw)
        if (hex != null) return Color(0xFF000000 or hex.groupValues[1].toLong(16))
        // Bare components, the form the --m-* tokens take so a tint can be derived from them.
        val (r, g, b) = raw.split(Regex("\\s+")).map { it.toInt() }
        return Color(r, g, b)
    }

    private fun assertParity(colors: InspectorColors, light: Boolean) {
        val theme = if (light) "light" else "dark"
        val block = block(light)
        for ((name, overlay) in pairs(colors)) {
            assertEquals(token(block, name), overlay, "$theme --$name differs between the web and the overlay")
        }
    }

    /** Each web token this test checks, with the overlay colour it must equal. */
    private fun pairs(colors: InspectorColors): List<Pair<String, Color>> =
        listOf(
            "accent" to colors.accent,
            "ok" to colors.success,
            "redirect" to colors.redirect,
            "warn" to colors.clientError,
            "err" to colors.serverError,
            // `.sx` draws a transport failure in --err; the overlay's token must agree.
            "err" to colors.transportError,
            "m-get" to colors.methodGet,
            "m-post" to colors.methodPost,
            "m-put" to colors.methodPut,
            "m-patch" to colors.methodPatch,
            "m-delete" to colors.methodDelete,
            "fg-muted" to colors.onSurfaceMuted,
        )

    @Test
    fun `the dark palette matches the web tokens`() = assertParity(DarkColors, light = false)

    @Test
    fun `the light palette matches the web tokens`() = assertParity(LightColors, light = true)

    @Test
    fun `no checked token is declared outside the tokens file`() {
        val names = pairs(DarkColors).map { it.first }.distinct()
        val elsewhere = sheets.filterKeys { it != TOKENS }.flatMap { (file, text) ->
            // Comments stripped, so a sentence naming a token is not taken for a declaration of it.
            val code = text.replace(Regex("""/\*[\s\S]*?\*/"""), "")
            names.filter { Regex("""--${Regex.escape(it)}\s*:""").containsMatchIn(code) }.map { "--$it in $file" }
        }
        assertTrue(sheets.size > 1, "web/styles.txt lists only $TOKENS, so there is nothing to check this against")
        assertTrue(
            elsewhere.isEmpty(),
            "declared outside $TOKENS, where this test cannot see it win the cascade: ${elsewhere.joinToString()}",
        )
    }

    private companion object {
        const val TOKENS = "css/00-tokens.css"
    }
}

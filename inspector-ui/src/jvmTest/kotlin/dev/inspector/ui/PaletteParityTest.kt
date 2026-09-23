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
 * Reads the web tokens out of `style.css` rather than from a copy, so the stylesheet stays the
 * authority for the web and this test is the only place the two meet.
 */
class PaletteParityTest {

    private val css: String = sequenceOf(
        File("../inspector-daemon/src/main/resources/web/style.css"),
        File("inspector-daemon/src/main/resources/web/style.css"),
    ).firstOrNull { it.isFile }?.readText()
        ?: error("style.css not found from ${File(".").absoluteFile} — the parity check cannot run")

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
        val pairs = listOf(
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
        for ((name, overlay) in pairs) {
            assertEquals(token(block, name), overlay, "$theme --$name differs between the web and the overlay")
        }
    }

    @Test
    fun `the dark palette matches the web tokens`() = assertParity(DarkColors, light = false)

    @Test
    fun `the light palette matches the web tokens`() = assertParity(LightColors, light = true)
}

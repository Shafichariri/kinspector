package dev.inspector.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * Colour tokens for the inspector surfaces.
 *
 * Deliberately self-contained rather than inheriting the host app's MaterialTheme: the inspector
 * draws on top of arbitrary apps, and a debugger that restyles itself to match whatever screen it
 * is floating over becomes hard to read exactly when you need it.
 */
@Immutable
data class InspectorColors(
    val surface: Color,
    val surfaceElevated: Color,
    val onSurface: Color,
    val onSurfaceMuted: Color,
    val divider: Color,
    val accent: Color,
    val success: Color,
    val redirect: Color,
    val clientError: Color,
    val serverError: Color,
    val transportError: Color,
    val methodGet: Color,
    val methodPost: Color,
    val methodPut: Color,
    val methodPatch: Color,
    val methodDelete: Color,
    /**
     * Row tint for a request that was sent more than once, close together, by separate calls.
     *
     * Amber, and used at a low alpha as a *background* rather than as text. The status chip already
     * owns the foreground colour on that row, so a tint is a second channel that cannot be misread
     * as "this row is a 4xx". Added last, so an app constructing its own palette is unaffected.
     */
    val duplicate: Color = Color(0xFFE0A030),
) {
    /** Status-class colour used by the pill dot and the list chips. */
    fun forStatus(status: Int?): Color = when {
        status == null -> transportError
        status >= 500 -> serverError
        status >= 400 -> clientError
        status >= 300 -> redirect
        else -> success
    }

    /**
     * Method colour, matching the web UI's `--m-*` tokens so one row looks the same in both
     * surfaces.
     *
     * Kept out of the status hue family: an amber PUT beside an amber 404 reads as "this row is a
     * 4xx", which is the one thing the list must not get wrong. DELETE keeps red because
     * destructive is what red should mean here. Unrecognised verbs stay muted rather than
     * borrowing a colour that already means something.
     */
    fun forMethod(method: String): Color = when (method.uppercase()) {
        "GET" -> methodGet
        "POST" -> methodPost
        "PUT" -> methodPut
        "PATCH" -> methodPatch
        "DELETE" -> methodDelete
        else -> onSurfaceMuted
    }
}

private val DarkColors = InspectorColors(
    surface = Color(0xFF14161A),
    surfaceElevated = Color(0xFF1E2127),
    onSurface = Color(0xFFE6E8EB),
    onSurfaceMuted = Color(0xFF9AA1AC),
    divider = Color(0xFF2C3038),
    accent = Color(0xFF6AA9FF),
    success = Color(0xFF4CC38A),
    redirect = Color(0xFF5B9BD5),
    clientError = Color(0xFFE0A030),
    serverError = Color(0xFFE5484D),
    transportError = Color(0xFF8A8F98),
    methodGet = Color(0xFF6AA9FF),
    methodPost = Color(0xFF4CC38A),
    methodPut = Color(0xFFD99BFF),
    methodPatch = Color(0xFFE0A030),
    methodDelete = Color(0xFFE5484D),
    duplicate = Color(0xFFE0A030),
)

private val LightColors = InspectorColors(
    surface = Color(0xFFFAFAFB),
    surfaceElevated = Color(0xFFFFFFFF),
    onSurface = Color(0xFF14161A),
    onSurfaceMuted = Color(0xFF5F6672),
    divider = Color(0xFFE1E4E8),
    accent = Color(0xFF2563EB),
    success = Color(0xFF13875B),
    redirect = Color(0xFF2563EB),
    clientError = Color(0xFFB45309),
    serverError = Color(0xFFC62A2F),
    transportError = Color(0xFF6B7280),
    methodGet = Color(0xFF2563EB),
    methodPost = Color(0xFF13875B),
    methodPut = Color(0xFF7C3AED),
    methodPatch = Color(0xFFB45309),
    methodDelete = Color(0xFFC62A2F),
    duplicate = Color(0xFFB45309),
)

val LocalInspectorColors = staticCompositionLocalOf { DarkColors }

@Composable
fun InspectorTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalInspectorColors provides if (dark) DarkColors else LightColors,
        content = content,
    )
}

/**
 * Fills the screen with [background], then keeps content clear of the status bar, the display
 * cutout, the navigation bar and the keyboard.
 *
 * Modifier order carries the intent and is not incidental: `background` runs before the inset
 * padding, so the colour still bleeds edge to edge while only the content is pushed inward. The
 * alternative — padding first — leaves the app visible in a strip behind the status bar, which
 * reads as a rendering bug.
 *
 * Host apps that are not edge-to-edge report zero insets, so this is a no-op there rather than a
 * double margin.
 */
@Composable
internal fun Modifier.inspectorScreen(background: Color): Modifier =
    fillMaxSize()
        .background(background)
        .windowInsetsPadding(WindowInsets.safeDrawing)

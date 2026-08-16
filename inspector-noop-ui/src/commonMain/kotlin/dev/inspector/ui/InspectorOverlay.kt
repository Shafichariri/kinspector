package dev.inspector.ui

import androidx.compose.runtime.Composable

/**
 * Release-build stand-in for `:inspector-ui`'s overlay: renders the app and nothing else.
 *
 * Signature-identical to the real one so `InspectorOverlay { App() }` in the consuming app
 * compiles unchanged under `-Pinspector=off`.
 */
@Composable
fun InspectorOverlay(
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    content()
}

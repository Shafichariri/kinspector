package dev.inspector.ui

import androidx.compose.runtime.Composable

/**
 * `androidx.activity`'s handler, which reads `LocalOnBackPressedDispatcherOwner` — provided by
 * every `ComponentActivity`, and therefore by every Compose Android app, since `setContent`
 * requires one.
 */
@Composable
internal actual fun InspectorBackHandler(enabled: Boolean, onBack: () -> Unit) {
    androidx.activity.compose.BackHandler(enabled = enabled, onBack = onBack)
}

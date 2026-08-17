package dev.inspector.ui

import androidx.compose.runtime.Composable

/** Desktop has no system back gesture; the close button is the way out. */
@Composable
internal actual fun InspectorBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit

package dev.inspector.ui

import androidx.compose.runtime.Composable

/**
 * iOS has no system back for a full-screen overlay — the interactive pop gesture belongs to a
 * navigation controller the inspector is not inside — so the close button is the way out.
 */
@Composable
internal actual fun InspectorBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit

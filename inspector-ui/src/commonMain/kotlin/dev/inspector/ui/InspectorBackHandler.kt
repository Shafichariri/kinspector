package dev.inspector.ui

import androidx.compose.runtime.Composable

/**
 * Routes the platform's system back gesture to [onBack] while [enabled].
 *
 * Deliberately `expect`/`actual` rather than Compose Multiplatform's common `BackHandler`. That
 * one calls `error(...)` when no dispatcher owner is present in the composition, which would turn
 * a debugging aid into a startup crash in the host app on any platform that does not provide one.
 * The overlay wraps somebody's entire app; it does not get to make that trade.
 *
 * So: Android — the only target with a real system back — uses `androidx.activity`'s handler,
 * which every `ComponentActivity` already supplies. Desktop and iOS have no system back for a
 * full-screen overlay, so they no-op and the on-screen close button is the way out.
 */
@Composable
internal expect fun InspectorBackHandler(enabled: Boolean, onBack: () -> Unit)

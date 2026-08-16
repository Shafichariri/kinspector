package dev.inspector.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/**
 * Set once from the Android entry point. The overlay is host-agnostic Compose and has no
 * Context of its own; a debug-only tool wiring one field beats threading a Context through
 * every composable.
 */
internal var androidClipboardContext: Context? = null

internal actual fun copyToClipboard(text: String) {
    val context = androidClipboardContext ?: return
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    manager.setPrimaryClip(ClipData.newPlainText("curl", text))
}

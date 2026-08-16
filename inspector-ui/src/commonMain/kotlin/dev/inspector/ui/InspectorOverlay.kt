package dev.inspector.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import dev.inspector.Inspector

/**
 * Wraps the app's root, drawing the inspector on top of it.
 *
 * ```
 * InspectorOverlay {
 *     App()
 * }
 * ```
 *
 * This is the Tier-1 overlay: it lives inside the app's own Compose hierarchy, so it works
 * identically on Android, iOS and desktop with no platform code. The known limitation is that it
 * cannot draw above surfaces Compose does not own — Android `Dialog`s, or native iOS view
 * controllers presented modally over Compose. Tier 2 (a real platform window) is a later,
 * optional addition.
 *
 * `:inspector-noop` ships a passthrough version of this function so release builds compile
 * unchanged.
 */
@Composable
fun InspectorOverlay(
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    if (!enabled) {
        content()
        return
    }

    val transactions by Inspector.transactions.collectAsState()
    val markers by Inspector.markers.collectAsState()
    val latest by Inspector.latest.collectAsState()

    var screen by remember { mutableStateOf<Screen>(Screen.Hidden) }
    var collapsed by remember { mutableStateOf(false) }
    var markCounter by remember { mutableStateOf(0) }

    // Compose's own clipboard works on Android, iOS and desktop, so copy-as-cURL needs no
    // platform code and — more importantly — nothing for the consuming app to wire up.
    val clipboard = LocalClipboardManager.current

    Box(Modifier.fillMaxSize()) {
        content()

        InspectorTheme {
            when (val current = screen) {
                Screen.Hidden -> OverlayPill(
                    latest = latest,
                    inFlight = 0,
                    collapsed = collapsed,
                    onTap = { screen = Screen.List },
                    onToggleCollapsed = { collapsed = !collapsed },
                    modifier = Modifier.fillMaxSize(),
                )

                Screen.List -> InspectorList(
                    transactions = transactions,
                    markers = markers,
                    onSelect = { screen = Screen.Detail(it.id) },
                    onClear = { Inspector.clear() },
                    onMark = { Inspector.mark("mark ${++markCounter}") },
                    onClose = { screen = Screen.Hidden },
                    modifier = Modifier.fillMaxSize(),
                )

                is Screen.Detail -> {
                    val txn = transactions.firstOrNull { it.id == current.id }
                    if (txn == null) {
                        // The ring buffer evicted it while it was open.
                        screen = Screen.List
                    } else {
                        InspectorDetail(
                            txn = txn,
                            siblings = transactions.filter { it.callId == txn.callId }
                                .sortedBy { it.attempt },
                            requestBody = Inspector.requestBody(txn),
                            responseBody = Inspector.responseBody(txn),
                            onSelectSibling = { screen = Screen.Detail(it.id) },
                            onCopyCurl = { clipboard.setText(AnnotatedString(it)) },
                            onBack = { screen = Screen.List },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}

private sealed interface Screen {
    data object Hidden : Screen
    data object List : Screen
    data class Detail(val id: String) : Screen
}

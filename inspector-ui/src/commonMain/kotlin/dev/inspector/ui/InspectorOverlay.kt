package dev.inspector.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import dev.inspector.Inspector
import dev.inspector.model.SignalKey

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
    // Already in device memory beside the traffic, and unread by this module until now. The
    // merged timeline was never a missing capability — see `docs/ROADMAP.md`.
    val signals by Inspector.signals.collectAsState()
    val latest by Inspector.latest.collectAsState()

    /*
     * A snapshot, deliberately, and re-read on every recomposition rather than collected.
     *
     * `Inspector.signalProviders()` is not a flow: providers are registered and removed as caches
     * and repositories are built, so any answer is historical the moment it is read. Rather than
     * invent a flow to make it look otherwise, the controls built from it treat a failed pull as
     * ordinary — which it is, and which `pullSignal` reports as a message rather than throwing.
     */
    val providers = Inspector.signalProviders()

    var screen by remember { mutableStateOf<Screen>(Screen.Hidden) }
    var collapsed by remember { mutableStateOf(false) }
    var markCounter by remember { mutableStateOf(0) }

    // Above the screen switch, so the list's arrangement outlives the list: closing the inspector
    // and reopening it, or reading a body and coming back, finds the filter where it was left.
    val listState = rememberListViewState()
    val hidden = screen == Screen.Hidden
    LaunchedEffect(hidden) {
        if (hidden) listState.onClosed()
    }

    // Compose's own clipboard works on Android, iOS and desktop, so copying needs no platform
    // code and — more importantly — nothing for the consuming app to wire up.
    val clipboard = LocalClipboardManager.current
    val copy: (String) -> Unit = { clipboard.setText(AnnotatedString(it)) }

    // System back unwinds the inspector one screen at a time, and only while it is open. Without
    // this the overlay is a trap on Android: it covers the app, and back goes to the app's own
    // previous screen (or leaves the app) with the inspector still on top of it.
    InspectorBackHandler(enabled = screen != Screen.Hidden) {
        // Every screen that is not the list unwinds *to* the list. Written as "is not the list"
        // rather than by naming the detail screens, so a screen added later cannot quietly become
        // one where back closes the inspector from two levels down.
        screen = if (screen is Screen.List) Screen.Hidden else Screen.List
    }

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
                    signals = signals,
                    onSelect = { screen = Screen.Detail(it.id) },
                    onSelectSignal = { screen = Screen.SignalDetail(it.id) },
                    onClear = { Inspector.clear() },
                    onMark = { Inspector.mark("mark ${++markCounter}") },
                    onClose = { screen = Screen.Hidden },
                    modifier = Modifier.fillMaxSize(),
                    providers = providers,
                    onPull = { key -> Inspector.pullSignal(key.tag, key.name) },
                    state = listState,
                )

                is Screen.SignalDetail -> {
                    val signal = signals.firstOrNull { it.id == current.id }
                    if (signal == null) {
                        // The signal ring evicted it while it was open — the same thing the
                        // transaction branch below handles, and for the same reason.
                        screen = Screen.List
                    } else {
                        val key = SignalKey(signal.tag, signal.name)
                        InspectorSignalDetail(
                            signal = signal,
                            signals = signals,
                            onSelectSignal = { screen = Screen.SignalDetail(it.id) },
                            onCopy = copy,
                            onBack = { screen = Screen.List },
                            onClose = { screen = Screen.Hidden },
                            modifier = Modifier.fillMaxSize(),
                            // Only when this key actually has one, so the button appearing is the
                            // same fact as the button working.
                            onPull = if (key in providers) {
                                {
                                    val failure = Inspector.pullSignal(key.tag, key.name)
                                    if (failure == null) {
                                        // Move to the answer. Staying on the row that was stale
                                        // enough to make somebody ask for a fresh reading would be
                                        // showing the old value under a button that just worked;
                                        // the history on the screen keeps the old one reachable.
                                        Inspector.signals.value
                                            .lastOrNull { it.tag == key.tag && it.name == key.name }
                                            ?.let { screen = Screen.SignalDetail(it.id) }
                                    }
                                    failure
                                }
                            } else {
                                null
                            },
                        )
                    }
                }

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
                            onCopy = copy,
                            onBack = { screen = Screen.List },
                            onClose = { screen = Screen.Hidden },
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
    data class SignalDetail(val id: String) : Screen
}

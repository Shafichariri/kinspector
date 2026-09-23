package dev.inspector.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dev.inspector.model.Marker
import dev.inspector.model.NetworkTransaction
import dev.inspector.model.Signal

/**
 * How the reader has arranged the list: what it is filtered to, which way round it runs, whether
 * signals are drawn, where it is scrolled and whether it is held still.
 *
 * **Owned by the overlay, not by the list.** It used to be `remember`ed inside [InspectorList],
 * which leaves the composition every time the list does — on close, and on every tap into a
 * detail screen. So each visit started from an empty filter, oldest first, signals on: the reader
 * set the same filter again every time they opened the inspector, and lost their place every time
 * they read a body. Hoisted one level, it lives as long as the app's root composable.
 *
 * **The defaults are newest first with signals hidden**, because the question the overlay is
 * opened to answer is almost always "what did the app just send". Signals are one tap away, and
 * the choice sticks once made.
 */
internal class ListViewState(
    filterText: String = "",
    newestFirst: Boolean = true,
    showSignals: Boolean = false,
    nowExpanded: Boolean = false,
) {
    var filterText by mutableStateOf(filterText)
    var newestFirst by mutableStateOf(newestFirst)
    var showSignals by mutableStateOf(showSignals)

    // Collapsed by default. The strip is worth a line as a summary and worth several only when
    // somebody asks, and on a phone the several are taken from the traffic below it.
    var nowExpanded by mutableStateOf(nowExpanded)

    var expandedRuns by mutableStateOf(emptySet<String>())

    /** Held here so reading a body and coming back lands on the same row, not the top. */
    val scroll = LazyListState()

    // Non-null while the list is held still. Holding the snapshot rather than a boolean is what
    // makes freezing mean anything: capture keeps running and the ring keeps evicting, so a flag
    // that merely stopped redrawing would still lose rows out from under the reader.
    //
    // It survives a trip into a detail screen, because opening a row is the whole reason to hold
    // the list still. It does not survive closing the inspector — see [onClosed].
    var frozen by mutableStateOf<Frozen?>(null)

    /**
     * The inspector was dismissed. The arrangement is kept; the freeze is not.
     *
     * A snapshot still held when the reader comes back ten minutes later would show those rows as
     * if they were what the app is doing now — a frozen list nobody remembers freezing reads as a
     * capture that stopped working.
     */
    fun onClosed() {
        frozen = null
    }

    companion object {
        /**
         * Only the choices the reader made survive the Activity being recreated. The freeze holds
         * rows and the scroll position points into them, and neither means anything once the list
         * it described has been rebuilt.
         */
        val Saver: Saver<ListViewState, Any> = listSaver(
            save = { listOf(it.filterText, it.newestFirst, it.showSignals, it.nowExpanded) },
            restore = {
                ListViewState(
                    filterText = it[0] as String,
                    newestFirst = it[1] as Boolean,
                    showSignals = it[2] as Boolean,
                    nowExpanded = it[3] as Boolean,
                )
            },
        )
    }
}

/**
 * A [ListViewState] that survives rotation and the app being restored after process death.
 * Surviving close-and-reopen needs nothing more than being called above the screen switch.
 */
@Composable
internal fun rememberListViewState(): ListViewState =
    rememberSaveable(saver = ListViewState.Saver) { ListViewState() }

/** The rows and markers a frozen list holds, so capture can carry on without moving them. */
internal data class Frozen(
    val transactions: List<NetworkTransaction>,
    val markers: List<Marker>,
    val signals: List<Signal>,
)

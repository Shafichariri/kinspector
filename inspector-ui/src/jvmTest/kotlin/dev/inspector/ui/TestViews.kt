package dev.inspector.ui

import androidx.compose.ui.test.performClick

/**
 * The arrangement tests about signals and ordering need: merged, oldest first.
 *
 * Not the overlay's default, which is newest first with signals hidden. Tests about the defaults
 * leave `state` unset and so get the real ones; everything else names this, so a test about the
 * now strip does not quietly depend on what the overlay opens with.
 */
internal fun mergedOldestFirst() = ListViewState(newestFirst = false, showSignals = true)

/** Opens the filters sheet, where the quick filters, markers, endpoints, tags and signals live. */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
internal fun androidx.compose.ui.test.ComposeUiTest.openFilters() {
    onNode(androidx.compose.ui.test.hasText("filters") and androidx.compose.ui.test.hasClickAction())
        .performClick()
    waitForIdle()
}

/**
 * The order control, by the state it announces. An arrow on screen, so it is found by its
 * description — which names the order the list is in *now*, the claim the control exists to make.
 */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
internal fun androidx.compose.ui.test.ComposeUiTest.orderControl(newestFirst: Boolean) =
    onNode(
        androidx.compose.ui.test.hasContentDescription(
            if (newestFirst) "newest first — tap for oldest first" else "oldest first — tap for newest first",
        ),
    )

/** A chip in the filters sheet. Toggleable, which is what tells it apart from a row with the same word. */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
internal fun androidx.compose.ui.test.ComposeUiTest.sheetChip(label: String) =
    onNode(androidx.compose.ui.test.hasText(label) and androidx.compose.ui.test.isToggleable())

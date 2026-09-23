package dev.inspector.ui

/**
 * The arrangement tests about signals and ordering need: merged, oldest first.
 *
 * Not the overlay's default, which is newest first with signals hidden. Tests about the defaults
 * leave `state` unset and so get the real ones; everything else names this, so a test about the
 * now strip does not quietly depend on what the overlay opens with.
 */
internal fun mergedOldestFirst() = ListViewState(newestFirst = false, showSignals = true)

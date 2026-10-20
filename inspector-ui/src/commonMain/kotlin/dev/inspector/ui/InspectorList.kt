package dev.inspector.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import dev.inspector.model.Filter
import dev.inspector.model.FilterContext
import dev.inspector.model.FilterParser
import dev.inspector.model.Marker
import dev.inspector.model.NetworkTransaction
import dev.inspector.model.pathScope
import dev.inspector.model.Signal
import dev.inspector.model.TimelineEntry
import dev.inspector.model.duplicatesById
import dev.inspector.model.SignalKey
import dev.inspector.model.timeline
import kotlinx.coroutines.delay
import dev.inspector.model.timelineRuns
// Extension: `matches` on the interface takes a Row; this is the transaction overload.
import dev.inspector.model.matches

/**
 * Transaction list with a live filter bar.
 *
 * The filter uses the same grammar as the web UI and the MCP tools — a string typed here is a
 * string you can hand an agent, which is the point of keeping the parser in `:inspector-model`.
 */
@Composable
internal fun InspectorList(
    transactions: List<NetworkTransaction>,
    markers: List<Marker>,
    signals: List<Signal> = emptyList(),
    onSelect: (NetworkTransaction) -> Unit,
    onSelectSignal: (Signal) -> Unit,
    onClear: () -> Unit,
    onMark: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Wall-clock now, injectable so the freeze rule below can be proved.
     *
     * It would otherwise be unprovable: the ages come from the real clock, a test cannot move the
     * real clock, and a rule about what happens to ages over time that nothing can exercise is a
     * paragraph of comment rather than a behaviour. Defaulted, so no call site changes.
     */
    nowMsProvider: () -> Long = ::nowEpochMs,
    /** `(tag, name)` the app registered a provider for; see `Inspector.signalProviders`. */
    providers: List<SignalKey> = emptyList(),
    /** Reads one provider now, returning null on success or a message to show. */
    onPull: (suspend (SignalKey) -> String?)? = null,
    /**
     * The reader's arrangement. The overlay passes one it owns so the arrangement outlives this
     * composable; the default is for tests and previews that only ever show one screen.
     */
    state: ListViewState = remember { ListViewState() },
) {
    val colors = LocalInspectorColors.current
    var filterText by state::filterText
    var newestFirst by state::newestFirst
    var showSignals by state::showSignals
    var expandedRuns by state::expandedRuns
    var nowExpanded by state::nowExpanded
    var frozen by state::frozen

    // Everything below reads these, never the parameters. A frozen list that filtered against live
    // markers, or counted live rows in its header, would be frozen in the one way nobody wants.
    val rows = frozen?.transactions ?: transactions
    val marks = frozen?.markers ?: markers
    val observations = frozen?.signals ?: signals

    /*
     * Wall-clock now, re-read once a second so ages tick, and held still while the list is frozen.
     *
     * Frozen has to include this. A held list whose ages kept climbing would be describing a
     * snapshot with a clock that had moved on from it — and "oldest 4m ago" under rows that stopped
     * updating four minutes ago is a sentence about two different moments. Everything downstream of
     * the freeze reads the snapshot; the clock is downstream.
     *
     * The ticker only runs while the inspector is open, which is the whole of this composable's
     * life: the app is already behind a full-screen overlay by then, so a 1s recomposition is not a
     * cost the efficiency contract is about.
     */
    var nowMs by remember { mutableStateOf(nowMsProvider()) }
    LaunchedEffect(frozen != null) {
        if (frozen != null) return@LaunchedEffect
        while (true) {
            nowMs = nowMsProvider()
            delay(1_000)
        }
    }

    val parsed = remember(filterText) { FilterParser.parse(filterText) }
    val filter = parsed.getOrNull() ?: Filter.MatchAll
    val parseError = parsed.exceptionOrNull()?.message

    val context = remember(marks) { FilterContext(marks) }
    val visible = remember(rows, filter, context) {
        rows.filter { filter.matches(it, context) }
    }
    // Signals go through the same filter, which they did not until now — the overlay filtered the
    // traffic and let every observation through, so `path:/v2` thinned the calls and left the
    // signals untouched. The web UI has always passed its filter to the signal route; this is the
    // surface that disagreed.
    //
    // The exclusion rule then does the rest, and is the point rather than a side effect: `tag:` is
    // a SignalTerm so it drops every transaction, and `path:` is a TransactionTerm so it drops
    // every signal. That is what turns a tag chip into a browser for one tag.
    val visibleSignals = remember(observations, filter, context) {
        observations.filter { filter.matches(it, context) }
    }

    // Transient, and deliberately not in [ListViewState]: a sheet still open when the inspector is
    // reopened would be a list you cannot see until you find the way out of something.
    var sheetOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    // Back closes whichever is open before it leaves the list. Registered after the overlay's own
    // handler, so it is the one asked first.
    InspectorBackHandler(enabled = sheetOpen || menuOpen) {
        sheetOpen = false
        menuOpen = false
    }

    // Computed over *all* transactions, not the filtered view: a duplicate whose twin is filtered
    // out is still a duplicate, and hiding that would make the highlight depend on what you
    // happened to be searching for. The scope shares the rule — see [pathScope].
    val duplicates = remember(rows) { duplicatesById(rows) }
    val scope = remember(rows) { pathScope(rows) }
    // Shown only while something on screen is actually under it. A filter that leaves only the odd
    // rows out would otherwise leave a claim describing nothing visible.
    val activeScope = scope?.takeIf { s -> visible.any(s::covers) }

    val sections = remember(rows, marks, observations, context) { sheetSections(rows, marks, observations, context) }
    val glance = remember(observations, providers, nowMs) {
        nowGlance(currentObservations(observations), nowMs, unreadProviders(observations, providers).size)
    }

    // The menu and the sheet sit outside the inset padding, so the scrim dims the whole screen —
    // status bar and home indicator included — and the sheet runs under the home indicator the
    // way a system sheet does. Inside it, they left undimmed strips at both edges.
    Box(modifier.fillMaxSize()) {
        Column(Modifier.inspectorScreen(colors.surface)) {
            ListHeader(
                total = rows.size,
                onMark = onMark,
                onMenu = { menuOpen = !menuOpen },
                onClose = onClose,
            )
            FilterRow(
                // Dimmed while there is nothing to filter, and kept rather than hidden, so the
                // layout does not jump when the first call arrives.
                modifier = Modifier.alpha(if (rows.isEmpty() && observations.isEmpty()) EMPTY_CHROME_ALPHA else 1f),
                filterText = filterText,
                onFilterText = { filterText = it },
                parseError = parseError,
                activeFilters = activeSheetTerms(filterText, sections),
                onOpenFilters = { sheetOpen = true },
                newestFirst = newestFirst,
                onOrder = { newestFirst = it },
                frozen = frozen != null,
                onFreeze = { hold -> frozen = if (hold) Frozen(transactions, markers, signals) else null },
            )
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))

            // The now half follows the signals toggle: "signals off" with a signal panel still on
            // screen would be the toggle not meaning what it says.
            ScopeNowLine(
                shown = visible.size,
                total = rows.size,
                scopeLabel = activeScope?.label,
                glance = if (showSignals) glance else null,
                expanded = nowExpanded,
                onToggleNow = { nowExpanded = !nowExpanded },
            )
            // Above the list, and fed the *unfiltered* observations: "what the app holds" is a fact
            // about the session, not about what you happened to type.
            if (showSignals && nowExpanded && glance != null) {
                NowPanel(
                    signals = observations,
                    nowMs = nowMs,
                    onSelectSignal = onSelectSignal,
                    providers = providers,
                    onPull = onPull,
                )
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))

            // Markers are not filtered with the rows. The filter grammar describes traffic, and a
            // divider still says where in the session you are looking — which is most of its job
            // when a filter has thinned the rows around it. The web UI does the same.
            val shown = if (showSignals) visibleSignals else emptyList()
            val entries = remember(visible, marks, shown, newestFirst) {
                timeline(visible, marks, shown, newestFirst)
            }
            // A chatty state holder emits dozens of adjacent rows differing only in a payload the
            // row cannot show, and on a phone that is the whole screen. Collapsing is what keeps the
            // traffic this view exists to correlate on screen at all.
            val runs = remember(entries) { timelineRuns(entries) }

            if (runs.isEmpty()) {
                // No calls is "nothing yet" whether or not the filter is set — unless signals are
                // showing and the filter hid them, which is the case the clear button is for.
                if (rows.isEmpty() && (observations.isEmpty() || !showSignals)) {
                    NothingYet(
                        providers = providers,
                        onPull = onPull,
                        hiddenSignals = if (showSignals) 0 else observations.size,
                        onShowSignals = { showSignals = true },
                    )
                } else {
                    // Rows exist and the filter hid them. Clearing it is the whole fix, so it is
                    // offered here rather than left to finding the field and deleting by hand.
                    Column(
                        Modifier.fillMaxSize().padding(horizontal = 32.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("No calls match this filter", color = colors.onSurfaceMuted, fontSize = 13.sp)
                        Box(
                            Modifier.padding(top = 12.dp).height(40.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .border(1.dp, colors.divider, RoundedCornerShape(6.dp))
                                .clickable { filterText = "" }
                                .padding(horizontal = 16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("Clear filter", color = colors.onSurface, fontSize = 13.sp)
                        }
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), state = state.scroll) {
                    // Keyed by index as well as content: two markers can carry the same label at the
                    // same millisecond, and a duplicate key is a crash rather than a rendering glitch.
                    itemsIndexed(runs, key = { index, run -> "$index:${run.id}" }) { _, run ->
                        val collapsible = run.entries.size >= RUN_COLLAPSE_THRESHOLD
                        val expanded = run.id in expandedRuns
                        if (collapsible) {
                            RunHeader(
                                run = run,
                                expanded = expanded,
                                onToggle = {
                                    expandedRuns = if (expanded) expandedRuns - run.id else expandedRuns + run.id
                                },
                            )
                            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
                        }
                        if (!collapsible || expanded) {
                            for (entry in run.entries) {
                                when (entry) {
                                    is TimelineEntry.Call -> TransactionRow(
                                        txn = entry.txn,
                                        scope = activeScope,
                                        duplicate = duplicates[entry.txn.id],
                                        onClick = { onSelect(entry.txn) },
                                    )
                                    is TimelineEntry.Mark -> MarkerDivider(entry.marker)
                                    is TimelineEntry.Observation -> SignalRow(
                                        signal = entry.signal,
                                        indented = collapsible,
                                        onClick = { onSelectSignal(entry.signal) },
                                    )
                                }
                                Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
                            }
                        }
                    }
                }
            }
        }

        if (menuOpen) {
            OverflowMenu(
                rowCount = rows.size,
                providers = providers,
                onPull = onPull,
                onClear = onClear,
                onDismiss = { menuOpen = false },
            )
        }
        if (sheetOpen) {
            FiltersSheet(
                sections = sections,
                filterText = filterText,
                onFilterText = { filterText = it },
                signalCount = observations.size,
                showSignals = showSignals,
                onShowSignals = { showSignals = it },
                matching = visible.size,
                onDismiss = { sheetOpen = false },
            )
        }
    }
}

/**
 * How many adjacent identical observations before the run collapses to one row.
 *
 * Three, matching the web. Two adjacent rows are cheap to read and collapsing them would hide as
 * much as it saved; by three the run is a pattern rather than a coincidence.
 */
private const val RUN_COLLAPSE_THRESHOLD = 3

/**
 * @param prominent draws the button filled, for the one action on a screen that is the way out.
 *   Touch target is padded to stay tappable on a phone regardless.
 */
@Composable
internal fun ToolbarButton(label: String, onClick: () -> Unit, prominent: Boolean = false) {
    val colors = LocalInspectorColors.current
    Text(
        label,
        color = if (prominent) colors.surface else colors.accent,
        fontSize = 12.sp,
        fontWeight = if (prominent) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
            .then(if (prominent) Modifier.background(colors.accent) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

/** The filter row while there is nothing to filter: present, so nothing moves later, but quiet. */
private const val EMPTY_CHROME_ALPHA = 0.6f

internal val PILL_RADIUS = 999.dp

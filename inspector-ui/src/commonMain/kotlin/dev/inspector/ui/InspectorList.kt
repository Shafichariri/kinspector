package dev.inspector.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import dev.inspector.model.Filter
import dev.inspector.model.FilterContext
import dev.inspector.model.FilterParser
import dev.inspector.model.Marker
import dev.inspector.model.NetworkTransaction
import dev.inspector.model.PathScope
import dev.inspector.model.pathScope
import dev.inspector.model.Signal
import dev.inspector.model.SignalTrigger
import dev.inspector.model.DuplicateGroup
import dev.inspector.model.EndpointShortcut
import dev.inspector.model.TimelineEntry
import dev.inspector.model.TimelineRun
import dev.inspector.model.duplicatesById
import dev.inspector.model.endpointFilterTerm
import dev.inspector.model.endpointShortcuts
import dev.inspector.model.markerLabels
import dev.inspector.model.SignalKey
import dev.inspector.model.TagShortcut
import dev.inspector.model.signalTagShortcuts
import dev.inspector.model.tagFilterTerm
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
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (rows.isEmpty()) "no traffic captured yet" else "no rows match this filter",
                        color = colors.onSurfaceMuted,
                        fontSize = 13.sp,
                    )
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
 * The sheet's sections, built from the session rather than from constants.
 *
 * Endpoints, markers, methods and tags are all whatever this session actually holds — a list of
 * the tags this *build* knows about is not a fact about the app being debugged. Built off the
 * unfiltered rows, like the duplicate highlight and for the same reason: chips built from the
 * filtered view collapse to the one you just tapped.
 *
 * Counts are what each term alone would keep, asked of the parser, so a count and the rows a tap
 * then shows cannot disagree. Markers the grammar cannot express are left out; their dividers are
 * still drawn, it is only the chip that could not work.
 */
internal fun sheetSections(
    rows: List<NetworkTransaction>,
    marks: List<Marker>,
    observations: List<Signal>,
    context: FilterContext,
): List<SheetSection> {
    fun count(term: String): Int? {
        val filter = FilterParser.parse(term).getOrNull() ?: return null
        return rows.count { filter.matches(it, context) }
    }
    val status = QUICK_FILTERS.map { (label, term) -> SheetChip(label, term, count(term)) }
    val methods = rows.groupingBy { it.method.uppercase() }.eachCount().entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .take(METHOD_CHIP_LIMIT)
        .map { (method, n) -> SheetChip(method, "method:$method", n) }
    val endpoints = endpointShortcuts(rows, ENDPOINT_CHIP_LIMIT)
        .map { SheetChip(it.segment, endpointFilterTerm(it.segment), it.count) }
    val markers = markerLabels(marks)
        .filter { FilterParser.parse(markerFilterTerm(it)).isSuccess }
        .map { SheetChip(it, markerFilterTerm(it), count(markerFilterTerm(it))) }
    val tags = signalTagShortcuts(observations, TAG_CHIP_LIMIT)
        .map { SheetChip(it.tag, tagFilterTerm(it.tag), it.count) }
    return listOf(
        SheetSection("Status", status),
        SheetSection("Method", methods),
        SheetSection("Endpoint", endpoints),
        SheetSection("Since marker", markers),
        SheetSection("Signal tag", tags),
    )
}

/**
 * One app observation, on the same clock as the traffic around it.
 *
 * Deliberately not shaped like a transaction row. A signal has no status, no duration and no byte
 * count, and giving it the columns anyway would leave four gaps that read as missing data. One
 * line, a coloured stripe naming its tag, and the clock — which is the only column it shares with
 * the traffic and the only one that makes the two comparable.
 *
 * The payload is still not drawn *here*. A row cannot show a JSON object usefully at 360dp and a
 * truncated one would be worse than none — so the row opens one instead. Tapping it is the way to
 * the payload, its history and where it came from, the same way tapping a call is the way to a
 * body.
 */
@Composable
private fun SignalRow(signal: Signal, indented: Boolean, onClick: () -> Unit) {
    val colors = LocalInspectorColors.current
    val tagColor = colors.forTag(signal.tag)
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 40.dp)
            .clickable(onClick = onClick)
            // Drawn rather than laid out, like the failure stripe on a transaction row, so the
            // lane costs the row no width and cannot pull the columns out of line with the traffic.
            .drawBehind { drawRect(tagColor, size = Size(STRIPE_WIDTH.toPx(), size.height)) }
            .padding(start = if (indented) 24.dp else 12.dp, end = 12.dp)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // In the method badge's column, so a tag and a verb line up down the list.
        Text(
            signal.tag,
            color = tagColor,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(BADGE_COLUMN),
        )
        StartEllipsisText(
            signal.name,
            color = colors.onSurface,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            // The tail identifies it, same as a path.
            modifier = Modifier.weight(1f),
        )
        // Provenance, because a snapshot with none reads as the current state of the app. "pulled"
        // means the host asked and a provider answered; the default is the app pushing it.
        if (signal.trigger == SignalTrigger.Request) {
            Text("pulled", color = colors.onSurfaceMuted, fontSize = 11.sp, maxLines = 1)
        }
        Text(formatClock(signal.ts), color = colors.onSurfaceMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
    }
}

/**
 * The one row standing in for a run of identical observations.
 *
 * Reports the count **and** the wall of time the run covers, which is the part a collapsed run
 * must not lose: "48 times" and "48 times over 23 seconds" say different things about the app.
 * Tapping expands, and the expanded members are indented so the run they belong to stays legible
 * once it is several screens long.
 */
@Composable
private fun RunHeader(run: TimelineRun, expanded: Boolean, onToggle: () -> Unit) {
    val colors = LocalInspectorColors.current
    val signal = (run.first as? TimelineEntry.Observation)?.signal ?: return
    val tagColor = colors.forTag(signal.tag)
    Row(
        Modifier.fillMaxWidth()
            .clickable(onClick = onToggle)
            .drawBehind { drawRect(tagColor, size = Size(STRIPE_WIDTH.toPx(), size.height)) }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            if (expanded) "▾" else "▸",
            color = colors.onSurfaceMuted,
            fontSize = 11.sp,
        )
        Text(signal.tag, color = tagColor, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
        StartEllipsisText(
            signal.name,
            color = colors.onSurface,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f),
        )
        Text(
            "×${run.entries.size} / ${formatDuration(run.spanMs)}",
            color = colors.onSurfaceMuted,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
        )
    }
}

/**
 * One marker, drawn across the list as a labelled rule.
 *
 * A marker is a boundary between two stretches of traffic, so it is drawn as a line and not as a
 * row: anything row-shaped here would be counted by eye as a call. Rules on both sides and the
 * label in the middle, which is what the web UI does — the same event on two surfaces should not
 * need learning twice.
 *
 * The label is whatever the app or an agent passed, so it is not trusted to be short. It gets the
 * middle and ellipsises; the rules take what is left.
 */
@Composable
private fun MarkerDivider(marker: Marker) {
    val colors = LocalInspectorColors.current
    Row(
        Modifier.fillMaxWidth().background(colors.surfaceElevated).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.weight(1f).height(1.dp).background(colors.divider))
        Text(
            marker.label,
            color = colors.accent,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // Never smaller than the rules beside it, and never so wide it pushes them off.
            modifier = Modifier.weight(3f, fill = false),
            textAlign = TextAlign.Center,
        )
        Box(Modifier.weight(1f).height(1.dp).background(colors.divider))
    }
}

/**
 * The status presets, as label to filter term.
 *
 * The same grammar the field above takes, so a chip is a shortcut and never a second mechanism —
 * tap one and the field shows what it did, which is how anybody learns the grammar at all. `4xx`
 * is back now that these live in a sheet rather than a strip every other chip had to be scrolled
 * past; `POST` is in the sheet's method section instead.
 *
 * `QuickFiltersTest` parses every one of these, so a term cannot rot into a chip that shows an
 * error message when tapped.
 */
internal val QUICK_FILTERS: List<Pair<String, String>> = listOf(
    "errors" to "has:error",
    "5xx" to "status>=500",
    "4xx" to "status>=400 status<500",
    "slow" to "slower:500ms",
    "retries" to "attempt>1",
)

/**
 * How many endpoint chips the sheet offers.
 *
 * The web defaults to ten and makes it a setting. Here it is fixed and smaller: a phone has no
 * settings pane to put the knob in, and past six the section is taller than the sheet.
 */
private const val ENDPOINT_CHIP_LIMIT = 6

/** GET, POST and a few more cover every app; beyond that it is a list nobody taps. */
private const val METHOD_CHIP_LIMIT = 5

/**
 * How many tag chips the sheet offers.
 *
 * Fewer than the endpoints, because a session has few tags and many endpoints: the four
 * conventional ones plus whatever the app invented is the realistic ceiling, and a session with
 * more than four distinct tags is one where the strip is no longer the right way in.
 */
private const val TAG_CHIP_LIMIT = 4

/**
 * How many adjacent identical observations before the run collapses to one row.
 *
 * Three, matching the web. Two adjacent rows are cheap to read and collapsing them would hide as
 * much as it saved; by three the run is a pattern rather than a coincidence.
 */
private const val RUN_COLLAPSE_THRESHOLD = 3

/**
 * The filter a marker chip applies.
 *
 * Quoted because a label may contain spaces, and the grammar's own `since:marker("…")` form is
 * what the web UI, the CLI and the MCP tools all take — so the string a chip produces is one that
 * can be copied out and handed to an agent unchanged. Internal rather than private so the render
 * test can assert the chip emits the term the parser actually accepts.
 */
internal fun markerFilterTerm(label: String): String = "since:marker(\"$label\")"

/**
 * One call over two lines: what was called, then everything else about it.
 *
 * **Path first.** The path is the only part that tells one row from another, and the status is
 * the part scanned for, so the two share the top line with the method badge as its anchor. The
 * clock, duration, size and flags go underneath, indented to start under the path.
 *
 * **The path still loses its front, never its end.** The design pass drew it end-ellipsised, and
 * that is the one part of it not taken: `…/accounts/balance` names an endpoint and
 * `/v2/some-service/acc…` does not, and two rows that clip to the same front read alike.
 */
@Composable
private fun TransactionRow(
    txn: NetworkTransaction,
    scope: PathScope?,
    duplicate: DuplicateGroup?,
    onClick: () -> Unit,
) {
    val colors = LocalInspectorColors.current
    val statusColor = colors.forStatus(txn.status)
    val failed = txn.status == null || (txn.status ?: 0) >= 400
    val slow = (txn.ms ?: 0L) >= SLOW_MS

    Column(
        Modifier.fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(onClick = onClick)
            // Background before padding, so the tint fills the row rather than insetting with it.
            .background(if (duplicate != null) colors.duplicate.copy(alpha = DUPLICATE_TINT_ALPHA) else Color.Transparent)
            // A failure is findable by shape before it is read. Drawn rather than laid out, so it
            // costs the row no width and cannot pull the columns out of line.
            .drawBehind {
                if (failed) drawRect(statusColor, size = Size(STRIPE_WIDTH.toPx(), size.height))
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        // Centred rather than baseline-aligned: the fitted path is drawn inside a measuring box,
        // which does not report a baseline to line up with.
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MethodBadge(txn.method, minWidth = BADGE_COLUMN)
            StartEllipsisText(
                if (scope?.covers(txn) == true) scope.strip(txn.path) else txn.path,
                color = colors.onSurface,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
            )
            Text(
                statusLabel(txn),
                color = statusColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(start = BADGE_COLUMN + 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // The clock is what lines a row up against logcat or a backend log, and what makes a
            // repeat legible: two rows 1.9s apart is the whole finding.
            Text(formatClock(txn.ts), color = colors.onSurfaceMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
            Text(
                formatDuration(txn.ms),
                // The one number on the row with a natural bad state, so the list diagnoses as it
                // scrolls instead of only once a row is opened.
                color = if (slow) colors.clientError else colors.onSurfaceMuted,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
            Text(
                formatBytes(maxOf(txn.reqBytes, txn.resBytes)),
                color = colors.onSurfaceMuted,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
            // Everything unusual about this row, in whatever width is left. Empty on an ordinary
            // call, which is most of them — so anything here is worth the glance.
            Text(
                rowFlags(txn, scope, duplicate),
                color = if (txn.error != null) colors.serverError else colors.onSurfaceMuted,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * What is unusual about this row, said in words.
 *
 * The host appears only when it is not the one the scope bar claims: repeating it on every row is
 * noise when it never changes, and load-bearing on the one row where it does.
 *
 * A repeat says how many and over how long, which is the part that decides what it *is*: three
 * calls over 40ms is a double-fetch on one code path, three over 2.4s is a retry storm or a poll,
 * and the bare word "repeated" cannot tell them apart. The web says the same thing in a tooltip;
 * there are no tooltips here, so it goes in the row.
 */
private fun rowFlags(txn: NetworkTransaction, scope: PathScope?, duplicate: DuplicateGroup?): String {
    val parts = mutableListOf<String>()
    if (txn.attempt > 1) parts += "attempt ${txn.attempt}"
    // Said in words as well as colour: a tint alone is invisible to anyone who cannot distinguish
    // it, and unexplained to everyone else.
    if (duplicate != null) parts += repeatLabel(duplicate)
    if (scope == null || txn.host != scope.host) parts += txn.host
    txn.error?.let { parts += it }
    return parts.joinToString("  ·  ")
}

/**
 * `3× / 1.9s` — how many calls asked, and across how long.
 *
 * [DuplicateGroup.callCount] rather than `ids.size`, because a group containing retry attempts
 * holds more rows than calls and the reader is asking how many times the app asked.
 *
 * `×` rather than the word, and a slash rather than "over": this shares one line with the host,
 * the attempt number and any transport error, in whatever width the fixed columns leave.
 *
 * Internal rather than private so the `callCount` choice is pinned by a test — it is the kind of
 * thing that reads as interchangeable with `ids.size` until a retry lands in the group.
 */
internal fun repeatLabel(group: DuplicateGroup): String =
    "${group.callCount}× / ${formatDuration(group.spanMs)}"

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

/** Low enough to read as a tint rather than as a status colour. */
private const val DUPLICATE_TINT_ALPHA = 0.08f

/** Wide enough to catch the eye down the margin, narrow enough not to read as a second column. */
private val STRIPE_WIDTH = 3.dp

/**
 * Where a duration stops being unremarkable.
 *
 * A round second rather than a measured threshold: the point is to make the slow rows findable
 * while scrolling, and anything in this range separates "fine" from "worth a look" well enough.
 */
private const val SLOW_MS = 1_000L

/** The method badge's column, which the second line indents past so it starts under the path. */
private val BADGE_COLUMN = 52.dp

internal val PILL_RADIUS = 999.dp

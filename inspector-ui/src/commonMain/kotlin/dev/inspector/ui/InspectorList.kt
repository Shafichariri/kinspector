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

    Column(modifier.inspectorScreen(colors.surface)) {
        Row(
            Modifier.fillMaxWidth().background(colors.surfaceElevated).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Inspector",
                color = colors.onSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "${visible.size}/${rows.size}",
                color = colors.onSurfaceMuted,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
            )
            ToolbarButton("mark", onMark)
            ToolbarButton("clear", onClear)
            // The way back to the app. Prominent because on a phone this is the only exit that
            // is always present — system back also works, but nothing on screen says so.
            ToolbarButton("✕ close", onClose, prominent = true)
        }

        Column(Modifier.fillMaxWidth().background(colors.surfaceElevated).padding(horizontal = 12.dp, vertical = 8.dp)) {
            BasicTextField(
                value = filterText,
                onValueChange = { filterText = it },
                singleLine = true,
                textStyle = TextStyle(
                    color = colors.onSurface,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                ),
                cursorBrush = SolidColor(colors.accent),
                decorationBox = { inner ->
                    Box(
                        Modifier.fillMaxWidth()
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                            .background(colors.surface)
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        if (filterText.isEmpty()) {
                            Text(
                                "filter — e.g. status>=400  path:/v2  slower:500ms",
                                color = colors.onSurfaceMuted,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                        inner()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (parseError != null) {
                // Parser messages are written to be shown verbatim; they name the fix.
                Text(
                    parseError,
                    color = colors.clientError,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            // Only labels the grammar can actually express — a chip that sets a filter the parser
            // rejects is worse than no chip. The divider for such a marker is still drawn; it is
            // the chip that cannot work. Asked of the parser rather than guessed at, so this
            // follows the grammar if the grammar moves.
            val labels = remember(marks) {
                markerLabels(marks).filter { FilterParser.parse(markerFilterTerm(it)).isSuccess }
            }
            val endpoints = remember(rows) { endpointShortcuts(rows, ENDPOINT_CHIP_LIMIT) }
            // Built from the session's own tags, never from `SignalTags`: the tag set is open, so
            // a fixed list would be the tags this build knows about rather than the ones the app
            // emits. Off the *unfiltered* observations, like the endpoint chips and for the same
            // reason — chips built from the filtered view collapse to the one you just tapped.
            val tags = remember(observations) { signalTagShortcuts(observations, TAG_CHIP_LIMIT) }

            ControlStrip(
                newestFirst = newestFirst,
                onOrder = { newestFirst = it },
                frozen = frozen != null,
                onFreeze = { hold ->
                    frozen = if (hold) Frozen(transactions, markers, signals) else null
                },
                signalCount = observations.size,
                showSignals = showSignals,
                onShowSignals = { showSignals = it },
                filterText = filterText,
                onFilter = { filterText = it },
                markerLabels = labels,
                tags = tags,
                endpoints = endpoints,
            )
        }

        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))

        // Above the list, and fed the *unfiltered* observations: "what the app holds" is a fact
        // about the session, not about what you happened to type. Same rule as the endpoint chips
        // and the duplicate highlighting.
        if (showSignals) {
            NowStrip(
                signals = observations,
                nowMs = nowMs,
                expanded = nowExpanded,
                onToggle = { nowExpanded = !nowExpanded },
                onSelectSignal = onSelectSignal,
                providers = providers,
                onPull = onPull,
            )
            if (observations.isNotEmpty() || providers.isNotEmpty()) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
            }
        }

        // Markers are not filtered with the rows. The filter grammar describes traffic, and a
        // divider still says where in the session you are looking — which is most of its job when
        // a filter has thinned the rows around it. The web UI does the same.
        val shown = if (showSignals) visibleSignals else emptyList()
        val entries = remember(visible, marks, shown, newestFirst) {
            timeline(visible, marks, shown, newestFirst)
        }
        // A chatty state holder emits dozens of adjacent rows differing only in a payload the row
        // cannot show, and on a phone that is the whole screen. Collapsing is what keeps the
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
            // Computed over *all* transactions, not the filtered view: a duplicate whose twin is
            // filtered out is still a duplicate, and hiding that would make the highlight depend
            // on what you happened to be searching for. The scope shares the rule — see [pathScope].
            val duplicates = remember(rows) { duplicatesById(rows) }
            val scope = remember(rows) { pathScope(rows) }

            // Shown only while something on screen is actually under it. A filter that leaves only
            // the odd rows out would otherwise leave a bar describing nothing visible.
            val activeScope = scope?.takeIf { s -> visible.any(s::covers) }
            if (activeScope != null) {
                ScopeBar(activeScope, rows.size)
                Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
            }

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
                                expandedRuns = if (expanded) {
                                    expandedRuns - run.id
                                } else {
                                    expandedRuns + run.id
                                }
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
            .clickable(onClick = onClick)
            // Drawn rather than laid out, like the failure stripe on a transaction row, so the
            // lane costs the row no width and cannot pull the clock out of line with the traffic.
            .drawBehind { drawRect(tagColor, size = Size(STRIPE_WIDTH.toPx(), size.height)) }
            .padding(start = if (indented) 24.dp else 12.dp, end = 12.dp)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            formatClock(signal.ts),
            color = colors.onSurfaceMuted,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
        )
        Text(
            signal.tag,
            color = tagColor,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
        )
        Text(
            signal.name,
            color = colors.onSurface,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            // The tail identifies it, same as a path.
            overflow = TextOverflow.StartEllipsis,
            modifier = Modifier.weight(1f),
        )
        // Provenance, because a snapshot with none reads as the current state of the app. "pulled"
        // means the host asked and a provider answered; the default is the app pushing it.
        if (signal.trigger == SignalTrigger.Request) {
            Text("pulled", color = colors.onSurfaceMuted, fontSize = 10.sp, maxLines = 1)
        }
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
        Text(
            signal.name,
            color = colors.onSurface,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.StartEllipsis,
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
 * One scrollable strip above the list, holding everything that is not the filter field itself.
 *
 * **One strip and not three rows.** The phone is 360dp wide and around 720 tall; the header, the
 * filter field and the scope bar already spend four lines before any traffic, and giving the order
 * control, the quick filters, the markers and the endpoints a row each would spend four more. A
 * horizontally scrollable strip trades vertical space, which is the scarce one, for horizontal
 * space, which is not.
 *
 * Order is by how often a thumb wants them and by how stable they are. The two view toggles never
 * move and never grow, so they lead and are always reachable without scrolling. The quick filters
 * are a fixed four. Markers come before endpoints because a marker is something the reader
 * deliberately dropped, and endpoints are merely what the session happened to contain.
 *
 * The toggles are tinted differently from the filter chips on purpose: one changes what the list
 * *shows* and the other changes how it is *arranged*, and a strip that made them look alike would
 * invite tapping `newest` expecting fewer rows.
 */
@Composable
private fun ControlStrip(
    newestFirst: Boolean,
    onOrder: (Boolean) -> Unit,
    frozen: Boolean,
    onFreeze: (Boolean) -> Unit,
    signalCount: Int,
    showSignals: Boolean,
    onShowSignals: (Boolean) -> Unit,
    filterText: String,
    onFilter: (String) -> Unit,
    markerLabels: List<String>,
    tags: List<TagShortcut>,
    endpoints: List<EndpointShortcut>,
) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Labelled with the order it is *currently* in, not the one a tap would produce. A phone
        // has no hover and no tooltip, so a control that names its effect rather than its state
        // leaves the reader unable to tell which way round the list is.
        StateChip(
            label = if (newestFirst) "newest first" else "oldest first",
            active = newestFirst,
            onClick = { onOrder(!newestFirst) },
        )
        // Mobile is live by construction, which sounds better than it is: there is no way to hold
        // still and read while traffic keeps arriving and the ring keeps evicting.
        StateChip(
            label = if (frozen) "frozen" else "live",
            active = frozen,
            onClick = { onFreeze(!frozen) },
        )
        // Only when the app emits any. A toggle for something a session does not contain is a
        // control that can only disappoint, and this strip has no room for one.
        if (signalCount > 0) {
            StateChip(
                label = if (showSignals) "signals $signalCount" else "signals off",
                active = showSignals,
                onClick = { onShowSignals(!showSignals) },
            )
        }

        // Tags before the markers, and the reason is arithmetic rather than taste: there are at
        // most four tags and there is no limit at all on markers. Putting the unbounded list first
        // pushes the bounded one off the right-hand edge by however many markers somebody happened
        // to drop, and this strip is 360dp wide. The reverse costs the markers a fixed four chips.
        //
        // Measured, not reasoned: with the tags after the markers, `list-dark.png` showed the
        // strip ending mid-marker with no tag chip on screen at all — so the one route from
        // "something changed" to "this is what it changed to" was the hardest chip to reach.
        if (tags.isNotEmpty()) {
            StripDivider()
            for (tag in tags) {
                FilterChip(
                    label = "${tag.tag} ${tag.count}",
                    term = tagFilterTerm(tag.tag),
                    filterText = filterText,
                    onFilter = onFilter,
                )
            }
        }

        // Markers ahead of the quick filters, because a marker only exists because somebody
        // deliberately dropped one — so when there are any, they are what the reader came to the
        // strip for. The quick filters and the endpoints are always there and lose nothing by
        // being a swipe further along. A session with no markers pays nothing.
        if (markerLabels.isNotEmpty()) {
            StripDivider()
            for (label in markerLabels) {
                FilterChip(
                    label = label,
                    term = markerFilterTerm(label),
                    filterText = filterText,
                    onFilter = onFilter,
                )
            }
        }

        StripDivider()

        for ((label, term) in QUICK_FILTERS) {
            FilterChip(label = label, term = term, filterText = filterText, onFilter = onFilter)
        }

        if (endpoints.isNotEmpty()) {
            StripDivider()
            for (endpoint in endpoints) {
                FilterChip(
                    label = "${endpoint.segment} ${endpoint.count}",
                    term = endpointFilterTerm(endpoint.segment),
                    filterText = filterText,
                    onFilter = onFilter,
                )
            }
        }
    }
}

/**
 * Separates groups that do different things, so the strip does not read as one long list.
 *
 * Drawn from `onSurfaceMuted` rather than `divider`: `divider` is tuned to separate full-width
 * rows against a flat background, and a 1dp by 16dp mark of it between two chips disappears.
 */
@Composable
private fun StripDivider() {
    val colors = LocalInspectorColors.current
    Box(
        Modifier.padding(horizontal = 2.dp)
            .width(1.dp)
            .height(16.dp)
            .background(colors.onSurfaceMuted.copy(alpha = STRIP_DIVIDER_ALPHA)),
    )
}

/**
 * A chip that changes how the list is arranged rather than what it contains.
 *
 * Muted rather than accent-tinted: the accent chips below it all filter, and two controls that
 * look alike and do different things is the mistake worth designing out.
 */
@Composable
private fun StateChip(label: String, active: Boolean, onClick: () -> Unit) {
    val colors = LocalInspectorColors.current
    Text(
        label,
        color = if (active) colors.surface else colors.onSurfaceMuted,
        fontSize = 11.sp,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(PILL_RADIUS))
            .background(if (active) colors.onSurfaceMuted else Color.Transparent)
            .border(1.dp, colors.divider, RoundedCornerShape(PILL_RADIUS))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

/**
 * A chip that applies one filter term, and clears it when tapped again.
 *
 * Tapping an active chip clears rather than re-applying. A chip that can only be turned on is a
 * trap on a surface with no obvious way to select and delete text.
 */
@Composable
private fun FilterChip(label: String, term: String, filterText: String, onFilter: (String) -> Unit) {
    val colors = LocalInspectorColors.current
    val active = filterText.trim() == term
    Text(
        label,
        color = if (active) colors.surface else colors.accent,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(PILL_RADIUS))
            .background(if (active) colors.accent else colors.accent.copy(alpha = SCOPE_FILL_ALPHA))
            .border(1.dp, colors.accent.copy(alpha = SCOPE_BORDER_ALPHA), RoundedCornerShape(PILL_RADIUS))
            .clickable { onFilter(if (active) "" else term) }
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

/**
 * The four presets, as label to filter term.
 *
 * The same grammar the field above takes, so a chip is a shortcut and never a second mechanism —
 * tap one and the field shows what it did, which is how anybody learns the grammar at all. Four
 * and not the web's six: `4xx` and `POST` are a tap of typing away, and every chip here is a chip
 * the markers and endpoints have to be scrolled past.
 *
 * `QuickFiltersTest` parses every one of these, so a term cannot rot into a chip that shows an
 * error message when tapped.
 */
internal val QUICK_FILTERS: List<Pair<String, String>> = listOf(
    "errors" to "has:error",
    "5xx" to "status>=500",
    "slow" to "slower:500ms",
    "retries" to "attempt>1",
)

/**
 * How many endpoint chips the strip offers.
 *
 * The web defaults to ten and makes it a setting. Here it is fixed and smaller: every extra chip
 * is one more thing between a thumb and the marker chips, and a phone has no settings pane to put
 * the knob in.
 */
private const val ENDPOINT_CHIP_LIMIT = 6

/**
 * How many tag chips the strip offers.
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

/** Enough to read as a separator between chips, not enough to read as a chip of its own. */
private const val STRIP_DIVIDER_ALPHA = 0.45f

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
 * The shared prefix, lifted above the list it describes.
 *
 * Deliberately not scrolled away with the rows: it is a standing claim about what every path
 * beneath it is missing, and a row read after the bar had scrolled off would be read wrong.
 */
@Composable
private fun ScopeBar(scope: PathScope, total: Int) {
    val colors = LocalInspectorColors.current
    Row(
        Modifier.fillMaxWidth().background(colors.surface).padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            scope.label,
            color = colors.onSurface,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            // The front of a host is what identifies it, so this truncates the opposite way round
            // from the rows below.
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f, fill = false)
                .padding(end = 8.dp)
                .clip(RoundedCornerShape(PILL_RADIUS))
                .background(colors.accent.copy(alpha = SCOPE_FILL_ALPHA))
                .border(1.dp, colors.accent.copy(alpha = SCOPE_BORDER_ALPHA), RoundedCornerShape(PILL_RADIUS))
                .padding(horizontal = 8.dp, vertical = 3.dp),
        )
        Text(
            "${scope.covered} of $total",
            color = colors.onSurfaceMuted,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/**
 * One call over two lines: what happened, then what was called.
 *
 * The path is the only part that tells one row from another, and it used to share a line with the
 * method badge, the status and the timings — about 22 characters on a 360dp phone, which clips
 * mid-segment and leaves two different endpoints reading alike. Here it has a line of its own and
 * shares it only with the duration, which is nearer 37.
 *
 * Metadata first, path second, which is the less obvious half. The method and status stay in a
 * fixed column you can run an eye down; the line underneath is what you stop for.
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
            .clickable(onClick = onClick)
            // Background before padding, so the tint fills the row rather than insetting with it.
            .background(if (duplicate != null) colors.duplicate.copy(alpha = DUPLICATE_TINT_ALPHA) else Color.Transparent)
            // A failure is findable by shape before it is read. Drawn rather than laid out, so it
            // costs the row no width and cannot pull the columns out of line.
            .drawBehind {
                if (failed) drawRect(statusColor, size = Size(STRIPE_WIDTH.toPx(), size.height))
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(statusColor))

            // Before the method, which is where the web UI puts it — one event should not need
            // reading twice. On the metadata line rather than beside the path, because the path
            // line is the one the 0.7.0 layout exists to protect and this column is fixed width.
            Text(
                formatClock(txn.ts),
                color = colors.onSurfaceMuted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )

            MethodBadge(txn.method, minWidth = 52.dp)

            // Everything unusual about this row, in the space the fixed columns leave over. Empty
            // on an ordinary call, which is most of them — so anything here is worth the glance.
            Text(
                rowFlags(txn, scope, duplicate),
                color = colors.onSurfaceMuted,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )

            Text(
                formatBytes(maxOf(txn.reqBytes, txn.resBytes)),
                color = colors.onSurfaceMuted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                statusLabel(txn),
                color = statusColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace,
            )
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                formatDuration(txn.ms),
                // The one number on the row with a natural bad state, so the list diagnoses as it
                // scrolls instead of only once a row is opened.
                color = if (slow) colors.clientError else colors.onSurfaceMuted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.End,
                maxLines = 1,
                // Fixed width, so every path below starts at the same x. Durations run 3 to 6
                // characters, and a ragged left edge costs more in scanning than the column costs
                // in space.
                modifier = Modifier.width(DURATION_COLUMN).alignByBaseline(),
            )
            Text(
                if (scope?.covers(txn) == true) scope.strip(txn.path) else txn.path,
                color = colors.onSurface,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                // The tail identifies the endpoint; the front is boilerplate the scope bar has
                // usually already said. Clipping the end leaves two rows reading alike.
                overflow = TextOverflow.StartEllipsis,
                modifier = Modifier.weight(1f).alignByBaseline(),
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

/** Fits `1234ms` at 11sp monospace, which is the widest [formatDuration] produces. */
private val DURATION_COLUMN = 42.dp

internal val PILL_RADIUS = 999.dp
private const val SCOPE_FILL_ALPHA = 0.13f
private const val SCOPE_BORDER_ALPHA = 0.26f

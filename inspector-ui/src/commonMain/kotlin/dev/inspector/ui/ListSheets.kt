package dev.inspector.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.inspector.model.SignalKey
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.draw.alpha
import dev.inspector.model.FilterContext
import dev.inspector.model.FilterParser
import dev.inspector.model.Marker
import dev.inspector.model.NetworkTransaction
import dev.inspector.model.Signal
import dev.inspector.model.endpointFilterTerm
import dev.inspector.model.endpointShortcuts
import dev.inspector.model.markerLabels
import dev.inspector.model.signalTagShortcuts
import dev.inspector.model.tagFilterTerm
// Extension: `matches` on the interface takes a Row; this is the transaction overload.
import dev.inspector.model.matches

/*
 * What opens over the list: the `⋮` menu and the filters sheet, behind one scrim.
 *
 * Both are drawn in the overlay's own tree rather than as windows; `docs/decisions/overlay.md` says
 * why. The sheet's sections are built here too, from the session, and so is the term-at-a-time
 * editing the sheet does to the filter text — so a chip, what it offers and what it types are read
 * in one file.
 */

/**
 * The overflow: the session-wide actions that are not worth a place in the header.
 *
 * Clear takes two taps, and says what it will clear on the first. It empties the ring on the
 * device, which is the only copy of anything the daemon never received.
 */
@Composable
internal fun BoxScope.OverflowMenu(
    rowCount: Int,
    providers: List<SignalKey>,
    onPull: (suspend (SignalKey) -> String?)?,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalInspectorColors.current
    val scope = rememberCoroutineScope()
    var armed by remember { mutableStateOf(false) }
    var pulling by remember { mutableStateOf(false) }
    var failures by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(armed) {
        if (armed) {
            delay(CONFIRM_WINDOW_MS)
            armed = false
        }
    }
    MenuPanel(onDismiss) {
        // Every registered provider at once. Offered only where there are some, so the item
        // appearing is the same fact as it working.
        if (onPull != null && providers.isNotEmpty()) {
            MenuItem(
                label = when {
                    pulling -> "Pulling…"
                    failures != null && failures!! > 0 -> "Pull signals — $failures failed"
                    else -> "Pull signals"
                },
                color = if (failures != null && failures!! > 0) colors.clientError else colors.onSurface,
                enabled = !pulling,
            ) {
                pulling = true
                scope.launch {
                    var failed = 0
                    for (key in providers) if (onPull(key) != null) failed++
                    pulling = false
                    failures = failed
                    // A pull that failed stays on screen to say so; one that worked gets out of the way.
                    if (failed == 0) onDismiss()
                }
            }
            Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(colors.divider))
        }
        MenuItem(
            label = if (armed) "Clear $rowCount ${if (rowCount == 1) "call" else "calls"}?" else "Clear…",
            hint = if (armed) "tap again" else null,
            color = colors.serverError,
        ) {
            if (armed) {
                armed = false
                onClear()
                onDismiss()
            } else {
                armed = true
            }
        }
    }
}

/**
 * A menu under a 48dp header's right-hand end, dismissed by tapping anywhere else. Drawn in the
 * overlay's own tree rather than as a `Popup` window, for the reason `InspectorBackHandler` is
 * hand-rolled: a debugging aid must not depend on the host providing a window owner.
 */
@Composable
internal fun BoxScope.MenuPanel(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val colors = LocalInspectorColors.current
    Scrim(transparent = true, onDismiss)
    Column(
        Modifier.align(Alignment.TopEnd)
            // Under the header, wherever the header is: below the status bar and clear of a cutout.
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.End))
            .padding(top = 48.dp, end = 8.dp)
            // As wide as its widest item. Its items fill their width so the whole row is the tap
            // target, and without this that made the menu as wide as the screen.
            .width(IntrinsicSize.Max)
            .widthIn(min = 200.dp, max = 280.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(colors.surfaceElevated)
            .border(1.dp, colors.divider, RoundedCornerShape(6.dp))
            .padding(vertical = 4.dp),
        content = content,
    )
}

@Composable
internal fun MenuItem(
    label: String,
    color: Color,
    hint: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = LocalInspectorColors.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 40.dp).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, color = color, fontSize = 13.sp, modifier = Modifier.weight(1f, fill = false))
        if (hint != null) Text(hint, color = colors.onSurfaceMuted, fontSize = 11.sp)
    }
}

/** Covers the list behind a menu or sheet, and closes it when tapped. */
@Composable
private fun BoxScope.Scrim(transparent: Boolean, onDismiss: () -> Unit) {
    Box(
        Modifier.matchParentSize()
            .background(if (transparent) Color.Transparent else Color.Black.copy(alpha = SCRIM_ALPHA))
            // No ripple: the scrim is not a control, and flashing the whole screen says it is.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
    )
}

/** One chip in the sheet: a term it applies, and how many rows that term alone would keep. */
internal data class SheetChip(val label: String, val term: String, val count: Int?)

/**
 * One section of the sheet. Chips within a section replace each other — one status, one method,
 * one endpoint — and sections combine with AND. That is the grammar's own shape: whitespace ANDs,
 * and an OR inside a section would need parentheses the grammar deliberately does not have.
 */
internal data class SheetSection(val title: String, val chips: List<SheetChip>)

/**
 * Everything the chip strip held, in a sheet with room for it.
 *
 * It edits the same filter text the field shows, a term at a time, so the field still says what
 * the sheet did — which is how anybody learns the grammar — and anything typed by hand survives a
 * trip through the sheet untouched.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BoxScope.FiltersSheet(
    sections: List<SheetSection>,
    filterText: String,
    onFilterText: (String) -> Unit,
    signalCount: Int,
    showSignals: Boolean,
    onShowSignals: (Boolean) -> Unit,
    matching: Int,
    onDismiss: () -> Unit,
) {
    val colors = LocalInspectorColors.current
    val anyOn = sections.any { s -> s.chips.any { hasTerm(filterText, it.term) } }
    Scrim(transparent = false, onDismiss)
    Column(
        Modifier.align(Alignment.BottomCenter)
            .fillMaxWidth()
            .heightIn(max = SHEET_MAX_HEIGHT)
            .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
            .background(colors.surfaceElevated)
            // Swallows taps, so one between two chips does not fall through to the scrim.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            // Background first, then the inset: the sheet's colour reaches the bottom edge while its
            // contents stay clear of the home indicator and the navigation bar.
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)),
    ) {
        Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.width(36.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(colors.divider))
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Filters", color = colors.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(
                "Reset",
                color = if (anyOn) colors.accent else colors.onSurfaceMuted,
                fontSize = 12.sp,
                modifier = Modifier.clip(RoundedCornerShape(4.dp))
                    .clickable(enabled = anyOn) {
                        onFilterText(sections.flatMap { it.chips }.fold(filterText) { t, c -> removeTerm(t, c.term) })
                    }
                    .padding(horizontal = 6.dp, vertical = 8.dp),
            )
        }
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            for (section in sections) {
                if (section.chips.isEmpty()) continue
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionTitle(section.title)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val terms = section.chips.map { it.term }
                        for (chip in section.chips) {
                            val on = hasTerm(filterText, chip.term)
                            SheetToggle(chip.label, chip.count, on) {
                                onFilterText(if (on) removeTerm(filterText, chip.term) else replaceTerm(filterText, chip.term, terms))
                            }
                        }
                    }
                }
            }
            // Not a filter: signals are drawn or not, and no term expresses that. Here because
            // the sheet is where a reader looks for "why is the list showing this".
            if (signalCount > 0) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionTitle("Show")
                    SheetToggle("signals", signalCount, showSignals) { onShowSignals(!showSignals) }
                }
            }
        }
        Box(Modifier.fillMaxWidth().padding(top = 12.dp).height(1.dp).background(colors.divider))
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), contentAlignment = Alignment.CenterEnd) {
            // Updates as chips are tapped, so the effect is visible before the sheet is closed.
            Box(
                Modifier.height(40.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(colors.onSurface)
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Show $matching ${if (matching == 1) "call" else "calls"}",
                    color = colors.surface,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    val colors = LocalInspectorColors.current
    Text(title.uppercase(), color = colors.onSurfaceMuted, fontSize = 11.sp, letterSpacing = 0.66.sp)
}

@Composable
private fun SheetToggle(label: String, count: Int?, on: Boolean, onClick: () -> Unit) {
    val colors = LocalInspectorColors.current
    Row(
        Modifier.height(36.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (on) colors.accent.copy(alpha = SELECTION_ALPHA) else Color.Transparent)
            .border(1.dp, if (on) colors.accent else colors.divider, RoundedCornerShape(6.dp))
            // A toggle, not a button: a screen reader then says whether it is on, which the
            // border colour alone never told anyone.
            .toggleable(value = on, role = Role.Checkbox, onValueChange = { onClick() })
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            label,
            color = if (on) colors.onSurface else colors.onSurfaceMuted,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 220.dp),
        )
        if (count != null) {
            Text("$count", color = colors.onSurfaceMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

// --- the filter text, a term at a time ------------------------------------------------------

/**
 * Where [term] appears in [text] as a whole term: bounded by the ends or by whitespace.
 *
 * Not a substring test. `status>=400` must not light up because `status>=400 status<500` is in the
 * field, and a marker label is quoted text that may itself contain a space.
 */
private fun termIndex(text: String, term: String): Int {
    var from = 0
    while (true) {
        val i = text.indexOf(term, from)
        if (i < 0) return -1
        val before = i == 0 || text[i - 1].isWhitespace()
        val end = i + term.length
        val after = end == text.length || text[end].isWhitespace()
        if (before && after) return i
        from = i + 1
    }
}

/** Whether [term] is one of the terms in [text]. */
internal fun hasTerm(text: String, term: String): Boolean = termIndex(text, term) >= 0

/** [text] without [term], and with the rest left exactly as typed. */
internal fun removeTerm(text: String, term: String): String {
    val i = termIndex(text, term)
    if (i < 0) return text
    val left = text.substring(0, i).trimEnd()
    val right = text.substring(i + term.length).trimStart()
    return if (left.isEmpty() || right.isEmpty()) left + right else "$left $right"
}

/** [text] with [term] in place of whichever of [section] it held, so a section stays one choice. */
internal fun replaceTerm(text: String, term: String, section: List<String>): String {
    val cleared = section.fold(text) { t, other -> removeTerm(t, other) }
    return if (cleared.isBlank()) term else "${cleared.trimEnd()} $term"
}

/** How many of the sheet's terms [text] holds — the number on the `filters` button. */
internal fun activeSheetTerms(text: String, sections: List<SheetSection>): Int =
    sections.sumOf { s -> s.chips.count { hasTerm(text, it.term) } }

/** Taps on an armed Clear must land within this, or it disarms. */
private const val CONFIRM_WINDOW_MS = 3_000L

/** Room for five sections and the footer without covering the header. */
private val SHEET_MAX_HEIGHT = 520.dp

private const val SCRIM_ALPHA = 0.4f

/** The selection fill: the accent at 10%, the same tint the web uses for a selected row. */
private const val SELECTION_ALPHA = 0.10f

// --- what the sheet offers, built from the session -----------------------------------------

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
 * The filter a marker chip applies.
 *
 * Quoted because a label may contain spaces, and the grammar's own `since:marker("…")` form is
 * what the web UI, the CLI and the MCP tools all take — so the string a chip produces is one that
 * can be copied out and handed to an agent unchanged. Internal rather than private so the render
 * test can assert the chip emits the term the parser actually accepts.
 */
internal fun markerFilterTerm(label: String): String = "since:marker(\"$label\")"

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.inspector.model.SignalKey
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * The list's chrome: three bars where there were five.
 *
 * The first real phone session measured about 230dp of 720 spent on a header, a filter field, a
 * chip strip, the now strip and the scope bar before the first row. Everything the chip strip held
 * is still here — the order and freeze toggles moved into the filter row, and the filters moved
 * behind one button into a sheet — so the list starts around 150dp down and nothing was dropped
 * to get it there.
 */

/** Title, size, and the three things you do *to* the session: mark it, open the menu, leave. */
@Composable
internal fun ListHeader(total: Int, onMark: () -> Unit, onMenu: () -> Unit, onClose: () -> Unit) {
    val colors = LocalInspectorColors.current
    Row(
        Modifier.fillMaxWidth().height(48.dp).background(colors.surfaceElevated).padding(start = 12.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Inspector", color = colors.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "$total ${if (total == 1) "call" else "calls"}",
            color = colors.onSurfaceMuted,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
        )
        Box(
            Modifier.height(36.dp).clip(RoundedCornerShape(6.dp)).clickable(onClick = onMark).padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("Mark", color = colors.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        IconBox("⋮", "more actions", onMenu)
        CloseButton(onClose)
    }
}

/**
 * The way back to the app, and the only filled control in any header. System back also leaves,
 * but nothing on screen says so, and on a phone this is the one exit always there.
 */
@Composable
internal fun CloseButton(onClose: () -> Unit) {
    val colors = LocalInspectorColors.current
    Box(
        Modifier.size(36.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(colors.onSurface)
            .clickable(onClick = onClose)
            .semantics { contentDescription = "close inspector" },
        contentAlignment = Alignment.Center,
    ) {
        Text("✕", color = colors.surface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun IconBox(glyph: String, description: String, onClick: () -> Unit) {
    val colors = LocalInspectorColors.current
    Box(
        Modifier.size(36.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, color = colors.onSurface, fontSize = 16.sp)
    }
}

/**
 * The filter field and the three controls that act on the whole list.
 *
 * Freeze sits here rather than in the sheet: it is reached for *while* rows are arriving, and a
 * control behind a sheet is one that the rows you meant to hold have scrolled past by the time
 * it is open.
 */
@Composable
internal fun FilterRow(
    filterText: String,
    onFilterText: (String) -> Unit,
    parseError: String?,
    activeFilters: Int,
    onOpenFilters: () -> Unit,
    newestFirst: Boolean,
    onOrder: (Boolean) -> Unit,
    frozen: Boolean,
    onFreeze: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalInspectorColors.current
    Column(modifier.fillMaxWidth().background(colors.surfaceElevated).padding(start = 12.dp, end = 12.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicTextField(
                value = filterText,
                onValueChange = onFilterText,
                singleLine = true,
                textStyle = TextStyle(color = colors.onSurface, fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                cursorBrush = SolidColor(colors.accent),
                decorationBox = { inner ->
                    Box(
                        Modifier.fillMaxWidth().height(36.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(colors.surface)
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (filterText.isEmpty()) {
                            Text(
                                "status>=400",
                                color = colors.onSurfaceMuted,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                            )
                        }
                        inner()
                    }
                },
                modifier = Modifier.weight(1f),
            )
            // Accent-bordered while anything in the sheet is on, and counting it: a filter hidden
            // behind a button is otherwise a list that is mysteriously short.
            Row(
                Modifier.height(36.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .border(1.dp, if (activeFilters > 0) colors.accent else colors.divider, RoundedCornerShape(6.dp))
                    .clickable(onClick = onOpenFilters)
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text("filters", color = colors.onSurface, fontSize = 12.sp)
                if (activeFilters > 0) {
                    Text("$activeFilters", color = colors.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            // An arrow for the direction the list reads, and a description that names the state
            // rather than the effect: a phone has no tooltip, so "newest first" must mean *now*.
            Box(
                Modifier.size(36.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .border(1.dp, colors.divider, RoundedCornerShape(6.dp))
                    .clickable { onOrder(!newestFirst) }
                    .semantics {
                        contentDescription = if (newestFirst) "newest first — tap for oldest first" else "oldest first — tap for newest first"
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(if (newestFirst) "↑" else "↓", color = colors.onSurface, fontSize = 15.sp)
            }
            // Names its state too. Filled while frozen, so a held list cannot be mistaken for a
            // quiet one — which is the failure a freeze nobody remembers causes.
            Box(
                Modifier.height(36.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (frozen) colors.onSurfaceMuted else Color.Transparent)
                    .border(1.dp, colors.divider, RoundedCornerShape(6.dp))
                    .clickable { onFreeze(!frozen) }
                    .padding(horizontal = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (frozen) "frozen" else "live",
                    color = if (frozen) colors.surface else colors.onSurfaceMuted,
                    fontSize = 12.sp,
                )
            }
        }
        if (parseError != null) {
            // Parser messages are written to be shown verbatim; they name the fix.
            Text(parseError, color = colors.clientError, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/**
 * How much of the session is showing and what it has in common, beside what the app holds now.
 *
 * Two facts that each used to cost a bar of their own. The count never clips. The scope clips at
 * its *start*, like a path: its end is the prefix the rows below are missing, and a bar that cut
 * that off would be a claim about the rows with the claim removed. `MiddleEllipsis` was the first
 * choice and was measured clipping the end anyway at 360dp, which is exactly the part it was for.
 * The now half is fixed width and never ellipsised — the unread count is the one number you can
 * only learn here.
 */
@Composable
internal fun ScopeNowLine(
    shown: Int,
    total: Int,
    scopeLabel: String?,
    glance: NowGlance?,
    expanded: Boolean,
    onToggleNow: () -> Unit,
) {
    val colors = LocalInspectorColors.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 34.dp).background(colors.surface).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (scopeLabel != null) "$shown of $total · " else "$shown of $total",
                color = colors.onSurfaceMuted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
            if (scopeLabel != null) {
                StartEllipsisText(
                    scopeLabel,
                    color = colors.onSurfaceMuted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
        if (glance != null) {
            Row(
                Modifier.heightIn(min = 34.dp).clickable(onClick = onToggleNow).padding(start = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("now", color = colors.onSurface, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                if (glance.held.isNotEmpty()) {
                    Text(glance.held, color = colors.onSurfaceMuted, fontSize = 11.sp, maxLines = 1)
                }
                if (glance.unread > 0) {
                    Text("${glance.unread} unread", color = colors.accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
                Text(if (expanded) "▾" else "▸", color = colors.onSurfaceMuted, fontSize = 11.sp)
            }
        }
    }
}

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

/**
 * What an empty inspector says: that nothing has happened yet, what would make something happen,
 * and the one thing you can do about it from here.
 *
 * Left-aligned prose rather than a centred label, because it is instructions and centred text
 * reads as a heading. It names both ways traffic arrives — the Ktor plugin and the OkHttp
 * interceptor — because the first real app to use this saw its own calls and not an SDK's, and
 * "nothing" is the answer an unwrapped client produces.
 */
@Composable
internal fun NothingYet(
    providers: List<SignalKey>,
    onPull: (suspend (SignalKey) -> String?)?,
    /** Signals recorded but not drawn, which a list that says "nothing" must not hide silently. */
    hiddenSignals: Int = 0,
    onShowSignals: () -> Unit = {},
) {
    val colors = LocalInspectorColors.current
    val scope = rememberCoroutineScope()
    var pulling by remember { mutableStateOf(false) }
    var failures by remember { mutableStateOf<Int?>(null) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
        Column(
            Modifier.padding(horizontal = 32.dp).widthIn(max = 320.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("No calls yet", color = colors.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Requests made through the inspected HttpClient — or an OkHttpClient given the " +
                    "interceptor — appear here as they happen. Signals (cache, screen, state) show up " +
                    "when the app pushes them or you pull.",
                color = colors.onSurfaceMuted,
                fontSize = 13.sp,
                lineHeight = 19.sp,
            )
            Text("Long-press the pill to collapse it.", color = colors.onSurfaceMuted, fontSize = 13.sp, lineHeight = 19.sp)
            if (hiddenSignals > 0) {
                Box(
                    Modifier.padding(top = 4.dp).height(40.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .border(1.dp, colors.divider, RoundedCornerShape(6.dp))
                        .clickable(onClick = onShowSignals)
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Show $hiddenSignals hidden ${if (hiddenSignals == 1) "signal" else "signals"}",
                        color = colors.onSurface,
                        fontSize = 13.sp,
                    )
                }
            }
            // Only where it can work: an app that registered no providers has nothing to answer.
            if (onPull != null && providers.isNotEmpty()) {
                Box(
                    Modifier.padding(top = 4.dp).height(40.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .border(1.dp, colors.divider, RoundedCornerShape(6.dp))
                        .clickable(enabled = !pulling) {
                            pulling = true
                            scope.launch {
                                var failed = 0
                                for (key in providers) if (onPull(key) != null) failed++
                                failures = failed
                                pulling = false
                            }
                        }
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (pulling) "Pulling…" else "Pull signals now", color = colors.onSurface, fontSize = 13.sp)
                }
                failures?.takeIf { it > 0 }?.let {
                    Text("$it of ${providers.size} providers failed to answer", color = colors.clientError, fontSize = 12.sp)
                }
            }
        }
    }
}

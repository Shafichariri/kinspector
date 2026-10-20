package dev.inspector.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.inspector.model.SignalKey
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

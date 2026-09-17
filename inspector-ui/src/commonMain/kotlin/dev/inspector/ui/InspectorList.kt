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
import dev.inspector.model.TimelineEntry
import dev.inspector.model.duplicateIds
import dev.inspector.model.markerLabels
import dev.inspector.model.timeline
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
    onSelect: (NetworkTransaction) -> Unit,
    onClear: () -> Unit,
    onMark: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalInspectorColors.current
    var filterText by remember { mutableStateOf("") }

    val parsed = remember(filterText) { FilterParser.parse(filterText) }
    val filter = parsed.getOrNull() ?: Filter.MatchAll
    val parseError = parsed.exceptionOrNull()?.message

    val context = remember(markers) { FilterContext(markers) }
    val visible = remember(transactions, filter, context) {
        transactions.filter { filter.matches(it, context) }
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
                "${visible.size}/${transactions.size}",
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

            // Only when there are markers, and only labels the grammar can actually express — a
            // chip that sets a filter the parser rejects is worse than no chip. The divider for
            // such a marker is still drawn; it is the chip that cannot work. Asked of the parser
            // rather than guessed at, so this follows the grammar if the grammar moves.
            val labels = remember(markers) {
                markerLabels(markers).filter { FilterParser.parse(markerFilterTerm(it)).isSuccess }
            }
            if (labels.isNotEmpty()) {
                MarkerChips(
                    labels = labels,
                    filterText = filterText,
                    onFilter = { filterText = it },
                )
            }
        }

        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))

        // Markers are not filtered with the rows. The filter grammar describes traffic, and a
        // divider still says where in the session you are looking — which is most of its job when
        // a filter has thinned the rows around it. The web UI does the same.
        val entries = remember(visible, markers) { timeline(visible, markers) }

        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (transactions.isEmpty()) "no traffic captured yet" else "no rows match this filter",
                    color = colors.onSurfaceMuted,
                    fontSize = 13.sp,
                )
            }
        } else {
            // Computed over *all* transactions, not the filtered view: a duplicate whose twin is
            // filtered out is still a duplicate, and hiding that would make the highlight depend
            // on what you happened to be searching for. The scope shares the rule — see [pathScope].
            val duplicates = remember(transactions) { duplicateIds(transactions) }
            val scope = remember(transactions) { pathScope(transactions) }

            // Shown only while something on screen is actually under it. A filter that leaves only
            // the odd rows out would otherwise leave a bar describing nothing visible.
            val activeScope = scope?.takeIf { s -> visible.any(s::covers) }
            if (activeScope != null) {
                ScopeBar(activeScope, transactions.size)
                Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
            }

            LazyColumn(Modifier.fillMaxSize()) {
                // Keyed by index as well as content: two markers can carry the same label at the
                // same millisecond, and a duplicate key is a crash rather than a rendering glitch.
                itemsIndexed(
                    entries,
                    key = { index, entry ->
                        when (entry) {
                            is TimelineEntry.Call -> entry.txn.id
                            is TimelineEntry.Mark -> "marker-$index-${entry.marker.mono}"
                        }
                    },
                ) { _, entry ->
                    when (entry) {
                        is TimelineEntry.Call -> TransactionRow(
                            txn = entry.txn,
                            scope = activeScope,
                            isDuplicate = entry.txn.id in duplicates,
                            onClick = { onSelect(entry.txn) },
                        )
                        is TimelineEntry.Mark -> MarkerDivider(entry.marker)
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
                }
            }
        }
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
 * The marker labels, as taps that filter.
 *
 * This is the half of the feature that makes `since:marker(…)` usable at all. The grammar has
 * always supported it, but the labels live in the app's own code — so without this, using the
 * filter meant knowing what somebody passed to `Inspector.mark` and typing it exactly, on a phone
 * keyboard, with a typo silently matching nothing (`since:marker` with an unknown label matches
 * no rows, deliberately).
 *
 * Tapping an active chip clears the filter rather than re-applying it. A chip that can only be
 * turned on is a trap on a surface with no obvious way to select and delete text.
 */
@Composable
private fun MarkerChips(labels: List<String>, filterText: String, onFilter: (String) -> Unit) {
    val colors = LocalInspectorColors.current
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "since",
            color = colors.onSurfaceMuted,
            fontSize = 10.sp,
            modifier = Modifier.padding(end = 2.dp),
        )
        for (label in labels) {
            val term = markerFilterTerm(label)
            val active = filterText.trim() == term
            Text(
                label,
                color = if (active) colors.surface else colors.accent,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(PILL_RADIUS))
                    .background(
                        if (active) colors.accent else colors.accent.copy(alpha = SCOPE_FILL_ALPHA),
                    )
                    .border(
                        1.dp,
                        colors.accent.copy(alpha = SCOPE_BORDER_ALPHA),
                        RoundedCornerShape(PILL_RADIUS),
                    )
                    .clickable { onFilter(if (active) "" else term) }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
    }
}

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
    isDuplicate: Boolean,
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
            .background(if (isDuplicate) colors.duplicate.copy(alpha = DUPLICATE_TINT_ALPHA) else Color.Transparent)
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

            MethodBadge(txn.method, minWidth = 52.dp)

            // Everything unusual about this row, in the space the fixed columns leave over. Empty
            // on an ordinary call, which is most of them — so anything here is worth the glance.
            Text(
                rowFlags(txn, scope, isDuplicate),
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
 */
private fun rowFlags(txn: NetworkTransaction, scope: PathScope?, isDuplicate: Boolean): String {
    val parts = mutableListOf<String>()
    if (txn.attempt > 1) parts += "attempt ${txn.attempt}"
    // Said in words as well as colour: a tint alone is invisible to anyone who cannot distinguish
    // it, and unexplained to everyone else.
    if (isDuplicate) parts += "repeated"
    if (scope == null || txn.host != scope.host) parts += txn.host
    txn.error?.let { parts += it }
    return parts.joinToString("  ·  ")
}

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
private const val DUPLICATE_TINT_ALPHA = 0.14f

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

private val PILL_RADIUS = 999.dp
private const val SCOPE_FILL_ALPHA = 0.13f
private const val SCOPE_BORDER_ALPHA = 0.26f

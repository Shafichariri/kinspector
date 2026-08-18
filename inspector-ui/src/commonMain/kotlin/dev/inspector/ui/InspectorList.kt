package dev.inspector.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import dev.inspector.model.Filter
import dev.inspector.model.FilterContext
import dev.inspector.model.FilterParser
import dev.inspector.model.Marker
import dev.inspector.model.NetworkTransaction
import dev.inspector.model.duplicateIds

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
        }

        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))

        if (visible.isEmpty()) {
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
            // on what you happened to be searching for.
            val duplicates = remember(transactions) { duplicateIds(transactions) }

            LazyColumn(Modifier.fillMaxSize()) {
                items(visible, key = { it.id }) { txn ->
                    TransactionRow(txn, isDuplicate = txn.id in duplicates) { onSelect(txn) }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
                }
            }
        }
    }
}

@Composable
private fun TransactionRow(
    txn: NetworkTransaction,
    isDuplicate: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalInspectorColors.current
    Row(
        Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            // Background before padding, so the tint fills the row rather than insetting with it.
            .background(if (isDuplicate) colors.duplicate.copy(alpha = DUPLICATE_TINT_ALPHA) else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(colors.forStatus(txn.status)))

        MethodBadge(txn.method, minWidth = 52.dp)

        Column(Modifier.weight(1f)) {
            Text(
                txn.path,
                color = colors.onSurface,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
            Text(
                buildString {
                    append(txn.host)
                    if (txn.attempt > 1) append("  ·  attempt ${txn.attempt}")
                    // Said in words as well as colour: a tint alone is invisible to anyone who
                    // cannot distinguish it, and unexplained to everyone else.
                    if (isDuplicate) append("  ·  repeated")
                    txn.error?.let { append("  ·  ").append(it) }
                },
                color = colors.onSurfaceMuted,
                fontSize = 11.sp,
                maxLines = 1,
            )
        }

        Column(horizontalAlignment = Alignment.End) {
            Text(
                statusLabel(txn),
                color = colors.forStatus(txn.status),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                "${formatDuration(txn.ms)}  ${formatBytes(maxOf(txn.reqBytes, txn.resBytes))}",
                color = colors.onSurfaceMuted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
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

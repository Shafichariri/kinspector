package dev.inspector.ui

import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import dev.inspector.model.Marker
import dev.inspector.model.NetworkTransaction
import dev.inspector.model.PathScope
import dev.inspector.model.Signal
import dev.inspector.model.SignalTrigger
import dev.inspector.model.DuplicateGroup
import dev.inspector.model.TimelineEntry
import dev.inspector.model.TimelineRun

/*
 * The list's rows: a call, an observation, a collapsed run and a marker's divider.
 *
 * Split out of `InspectorList.kt`, which keeps the list itself — what it holds, in which order, and
 * which row each entry gets. The rows are `internal` rather than private only so that file can
 * reach them; nothing outside the list draws one.
 */

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
internal fun SignalRow(signal: Signal, indented: Boolean, onClick: () -> Unit) {
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
internal fun RunHeader(run: TimelineRun, expanded: Boolean, onToggle: () -> Unit) {
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
internal fun MarkerDivider(marker: Marker) {
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
internal fun TransactionRow(
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

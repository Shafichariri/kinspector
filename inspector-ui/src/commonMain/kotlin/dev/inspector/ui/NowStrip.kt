package dev.inspector.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.inspector.model.Signal
import dev.inspector.model.SignalTrigger
import dev.inspector.model.signalGroups

/**
 * What the app holds right now: the latest observation of every `(tag, name)`, with its age.
 *
 * The list below answers *what happened*. This answers *what is true*, which is a different
 * question and one the list is bad at — after two hundred rows the current value of a cache entry
 * is somewhere above the fold, and finding it means scrolling for the last mention of a name you
 * have to remember.
 *
 * **Collapsed by default, and the collapsed line has to earn itself.** Vertical space is the
 * scarce thing on a phone: the header, the filter field and the control strip already spend four
 * lines before any traffic. So collapsed it says how much there is and how stale the oldest of it
 * is — the second half being the part a count alone dodges, and the reason to open it.
 *
 * **"Now" is a claim the age column keeps honest.** Most observations are *pushed*: the app said
 * something once and has not been asked since. A panel headed "now" listing a value pushed at app
 * start, with nothing saying when, is the same confidently-wrong reading `SignalTrigger` exists to
 * prevent. Every row carries its age and its provenance for that reason.
 */
@Composable
internal fun NowStrip(
    signals: List<Signal>,
    /** Wall-clock now, passed in so a frozen list holds its ages still along with its rows. */
    nowMs: Long,
    expanded: Boolean,
    onToggle: () -> Unit,
    onSelectSignal: (Signal) -> Unit,
) {
    val colors = LocalInspectorColors.current
    val current = remember(signals) { currentObservations(signals) }
    if (current.isEmpty()) return

    Column(Modifier.fillMaxWidth().background(colors.surfaceElevated)) {
        Row(
            Modifier.fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                if (expanded) "▾" else "▸",
                color = colors.onSurfaceMuted,
                fontSize = 11.sp,
            )
            Text(
                "now",
                color = colors.onSurface,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                nowSummary(current, nowMs),
                color = colors.onSurfaceMuted,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }

        if (expanded) {
            // Capped and scrollable rather than unbounded: this sits above the list it is meant to
            // sit above, and an app with forty keys would otherwise push the traffic off the
            // screen entirely — which is the opposite of what a glance panel is for.
            Column(Modifier.fillMaxWidth().heightIn(max = NOW_MAX_HEIGHT).verticalScroll(rememberScrollState())) {
                for (signal in current) {
                    NowRow(signal, nowMs) { onSelectSignal(signal) }
                }
            }
        }
    }
}

@Composable
private fun NowRow(signal: Signal, nowMs: Long, onClick: () -> Unit) {
    val colors = LocalInspectorColors.current
    Row(
        Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 24.dp, end = 12.dp)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            signal.tag,
            color = colors.forTag(signal.tag),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
        )
        Text(
            signal.name,
            color = colors.onSurface,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.StartEllipsis,
            modifier = Modifier.weight(1f),
        )
        // Provenance before age, because it changes what the age *means*: two minutes since a
        // pulled reading is two minutes of possible drift, and two minutes since a pushed one is
        // only how long ago the app last mentioned it.
        Text(
            if (signal.trigger == SignalTrigger.Request) "pulled" else "pushed",
            color = colors.onSurfaceMuted,
            fontSize = 10.sp,
            maxLines = 1,
        )
        Text(
            ageMsOf(signal.ts, nowMs)?.let(::formatAge) ?: NO_CLOCK,
            color = colors.onSurfaceMuted,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
        )
    }
}

/**
 * The latest observation of each key, in a **stable** order.
 *
 * Alphabetical by tag and then name, which is deliberately not what [signalGroups] returns.
 * Ordering by recency is right for a history — the reader is looking for what changed — and wrong
 * here: this panel is a lookup, glanced at repeatedly, and a row that moves every time the app
 * mentions something else is one you have to find again each time. The list underneath is the
 * feed; this is the index.
 */
internal fun currentObservations(signals: List<Signal>): List<Signal> =
    signalGroups(signals)
        .map { it.latest }
        .sortedWith(compareBy({ it.tag }, { it.name }))

/**
 * `3 cache · 2 state — oldest 4m ago`.
 *
 * The staleness is the half worth the characters. "6 keys" says the panel has something in it;
 * "oldest 4m ago" is what tells you whether opening it is worth doing, and it is the number that
 * makes the word "now" in the header a claim rather than a label.
 */
internal fun nowSummary(current: List<Signal>, nowMs: Long): String {
    if (current.isEmpty()) return ""
    val counts = LinkedHashMap<String, Int>()
    for (signal in current) counts[signal.tag] = (counts[signal.tag] ?: 0) + 1
    val parts = counts.entries.joinToString(" · ") { (tag, n) -> "$n $tag" }
    // Null ages are skipped rather than counted as zero: an unreadable timestamp is not a fresh
    // one, and reporting it as the freshest would be the panel's one job done backwards.
    val oldest = current.mapNotNull { ageMsOf(it.ts, nowMs) }.maxOrNull()
    return if (oldest == null) parts else "$parts — oldest ${formatAge(oldest)}"
}

/** Roughly six rows. Enough to be a glance, short enough to leave the traffic on screen. */
private val NOW_MAX_HEIGHT = 160.dp

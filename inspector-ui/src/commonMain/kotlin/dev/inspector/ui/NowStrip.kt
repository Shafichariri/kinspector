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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.inspector.model.Signal
import dev.inspector.model.SignalKey
import dev.inspector.model.SignalTrigger
import dev.inspector.model.signalGroups
import kotlinx.coroutines.launch

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
    /** `(tag, name)` pairs the app registered a provider for. Empty when it registered none. */
    providers: List<SignalKey> = emptyList(),
    /** Reads one provider now. Null when pulling is not available at all. */
    onPull: (suspend (SignalKey) -> String?)? = null,
) {
    val colors = LocalInspectorColors.current
    val current = remember(signals) { currentObservations(signals) }
    // Registered but never answered. These exist nowhere else in the UI: with no observation there
    // is no row to open, so without this the pull button on the detail screen is unreachable for
    // exactly the providers that have never been used. The host cannot show these at all — a
    // daemon does not learn a provider's name until one answers.
    val unread = remember(signals, providers) { unreadProviders(signals, providers) }
    if (current.isEmpty() && unread.isEmpty()) return

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
                nowSummary(current, nowMs, unread.size),
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
                // After the observations, because a value the app has actually reported outranks
                // one it merely could.
                if (onPull != null) {
                    for (key in unread) {
                        UnreadProviderRow(key, onPull)
                    }
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
 * A provider the app registered and has never answered for.
 *
 * Its own row rather than a greyed-out entry among the observations: it has no age, no provenance
 * and no value, so it would be three empty columns in a panel whose whole job is saying how stale
 * things are.
 */
@Composable
private fun UnreadProviderRow(key: SignalKey, onPull: suspend (SignalKey) -> String?) {
    val colors = LocalInspectorColors.current
    val scope = rememberCoroutineScope()
    var pulling by remember(key) { mutableStateOf(false) }
    var error by remember(key) { mutableStateOf<String?>(null) }

    Column {
        Row(
            Modifier.fillMaxWidth()
                .clickable(enabled = !pulling) {
                    pulling = true
                    error = null
                    scope.launch {
                        error = onPull(key)
                        pulling = false
                    }
                }
                .padding(start = 24.dp, end = 12.dp)
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                key.tag,
                color = colors.forTag(key.tag),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
            Text(
                key.name,
                color = colors.onSurfaceMuted,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.StartEllipsis,
                modifier = Modifier.weight(1f),
            )
            // "never read" and not "—": the app has something to say here and has not been asked,
            // which is a different state from a value that is merely old.
            Text(
                if (pulling) "reading…" else "never read",
                color = colors.onSurfaceMuted,
                fontSize = 10.sp,
                maxLines = 1,
            )
            Text(
                "pull",
                color = colors.accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
        error?.let {
            Text(
                it,
                color = colors.clientError,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(start = 24.dp, end = 12.dp, bottom = 6.dp),
            )
        }
    }
}

/**
 * Registered providers with nothing recorded against them yet.
 *
 * Compared on `(tag, name)` against the observations, so a key that has been pushed *or* pulled
 * even once drops out of this list and appears among the current values instead — where it has an
 * age, which is the more useful thing to know about it.
 */
internal fun unreadProviders(signals: List<Signal>, providers: List<SignalKey>): List<SignalKey> {
    val observed = signals.mapTo(mutableSetOf()) { SignalKey(it.tag, it.name) }
    return providers.filterNot { it in observed }
        .sortedWith(compareBy({ it.tag }, { it.name }))
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
 * `3 cache · 2 state — oldest 4m ago · 1 never read`.
 *
 * The staleness is the half worth the characters. "6 keys" says the panel has something in it;
 * "oldest 4m ago" is what tells you whether opening it is worth doing, and it is the number that
 * makes the word "now" in the header a claim rather than a label.
 *
 * [unread] is counted separately and last, never folded into the tag counts. Those describe what
 * the app *holds*, and a provider that has never answered is not held — but leaving it out
 * entirely made the collapsed line say four where the open panel drew five, and meant the one
 * thing you can only learn by opening the panel was the existence of something to read.
 */
internal fun nowSummary(current: List<Signal>, nowMs: Long, unread: Int = 0): String {
    if (current.isEmpty() && unread == 0) return ""
    val counts = LinkedHashMap<String, Int>()
    for (signal in current) counts[signal.tag] = (counts[signal.tag] ?: 0) + 1
    // The breakdown, or a total once the breakdown is longer than the facts it is competing with.
    //
    // Measured rather than guessed: with four tags the line read
    // `1 cache · 1 screen · 1 session · 1 state — oldest 5m ag…` at 360dp — the staleness cut in
    // half and the unread count gone entirely, which are the two numbers that decide whether to
    // open the panel at all. A breakdown is worth its width while it is short and is the first
    // thing to give up when it is not.
    val parts = if (counts.size > SUMMARY_TAG_BREAKDOWN_LIMIT) {
        "${current.size} keys"
    } else {
        counts.entries.joinToString(" · ") { (tag, n) -> "$n $tag" }
    }
    // Null ages are skipped rather than counted as zero: an unreadable timestamp is not a fresh
    // one, and reporting it as the freshest would be the panel's one job done backwards.
    val oldest = current.mapNotNull { ageMsOf(it.ts, nowMs) }.maxOrNull()
    val held = when {
        parts.isEmpty() -> ""
        oldest == null -> parts
        else -> "$parts — oldest ${formatAge(oldest)}"
    }
    if (unread == 0) return held
    val never = "$unread never read"
    return if (held.isEmpty()) never else "$held · $never"
}

/**
 * Above this many distinct tags, the collapsed line says a total instead of a breakdown.
 *
 * Three is where `2 cache · 1 state · 1 screen` still leaves room for the staleness on a 360dp
 * strip. The four conventional tags plus anything an app invents is past it, which is the case
 * this exists for.
 */
private const val SUMMARY_TAG_BREAKDOWN_LIMIT = 3

/** Roughly six rows. Enough to be a glance, short enough to leave the traffic on screen. */
private val NOW_MAX_HEIGHT = 160.dp

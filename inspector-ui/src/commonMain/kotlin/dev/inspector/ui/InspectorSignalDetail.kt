package dev.inspector.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.inspector.model.Signal
import dev.inspector.model.SignalTrigger
import dev.inspector.model.signalHistory

/**
 * One observation: what the app said, when, where it came from, and how it got there.
 *
 * Stage 1 merged signals into the list and deliberately drew no payloads — a row cannot show a
 * JSON object usefully at 360dp. This is where the payload lives instead, reached the same way a
 * response body is: by tapping the row.
 *
 * The history is the part that is not simply "the web UI, smaller". A single observation answers
 * *what is it now*; the question a state observation actually raises is *what was it before*, and
 * on a phone there is no room for a side-by-side browser to answer that. So the screen is one key
 * at a time, with its own past under it, and tapping a past entry moves to that one.
 */
@Composable
internal fun InspectorSignalDetail(
    signal: Signal,
    /** Every signal still in the ring, from which this key's history is taken. */
    signals: List<Signal>,
    onSelectSignal: (Signal) -> Unit,
    onCopy: (String) -> Unit,
    onBack: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalInspectorColors.current
    val tagColor = colors.forTag(signal.tag)
    val history = remember(signals, signal.tag, signal.name) {
        signalHistory(signals, signal.tag, signal.name)
    }
    val payload = remember(signal) { formatSignalPayload(signal) }

    Column(modifier.inspectorScreen(colors.surface)) {
        Row(
            Modifier.fillMaxWidth().background(colors.surfaceElevated).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ToolbarButton("‹ back", onBack)
            TagBadge(signal.tag)
            Text(
                signal.name,
                color = colors.onSurface,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                // The tail identifies it, the same as a path: a state holder's name is usually a
                // package-ish prefix and then the part that differs.
                overflow = TextOverflow.StartEllipsis,
                modifier = Modifier.weight(1f),
            )
            ToolbarButton("✕", onClose, prominent = true)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
            // No "Tag" field: the badge in the header is the tag, in colour, and a phone screen
            // has no room to say it twice. The name is repeated from the header on purpose — the
            // header truncates it and this one is whole and copyable.
            Field("Name", signal.name, onCopy = { onCopy(signal.name) })
            Field("Recorded", formatClock(signal.ts))

            /*
             * Provenance, always drawn — never only when it is interesting.
             *
             * `SignalTrigger`'s own documentation is the argument: an agent handed a cache snapshot
             * with no provenance reports it as the current state of the cache, and if that snapshot
             * was pushed at app start and the session is now twenty minutes old, the answer is
             * confidently wrong. A human reading this screen is in exactly that position. Drawing
             * it only for pulled rows would make the common case — pushed, possibly long ago — the
             * one with nothing said about it.
             */
            Field(
                "Provenance",
                when (signal.trigger) {
                    SignalTrigger.Request -> "pulled — the host asked and a provider answered"
                    SignalTrigger.App -> "pushed by the app"
                },
            )
            signal.requestId?.let { Field("Request", it) }

            // The size belongs on the section heading, not in a field above it. Both were called
            // "Payload" and sat one line apart, which reads as two different things about which
            // the screen then says one thing each.
            //
            // `bytes` is the *true* size, counted even when the payload was cut — "9 KB, of which
            // you are seeing 2" is a different fact from "2 KB", and the truncated case is the one
            // where somebody is deciding whether what they can see is enough.
            val size = when {
                signal.bytes <= 0 -> ""
                signal.dataTruncated -> " — ${formatBytes(signal.bytes)}, truncated at capture"
                else -> " — ${formatBytes(signal.bytes)}"
            }
            SectionTitle("Payload$size", onCopy = payload?.let { { onCopy(it) } })
            when {
                payload == null && signal.bytes > 0 -> Text(
                    // The ring evicts payloads with their rows, and a pulled row whose provider
                    // returned nothing has none either. Saying which is beyond this screen; saying
                    // that there is none, rather than drawing an empty box, is not.
                    "${formatBytes(signal.bytes)} recorded, but the payload is no longer held",
                    color = colors.onSurfaceMuted,
                    fontSize = 13.sp,
                )
                payload == null -> Text(
                    "none — this observation is the name and the moment",
                    color = colors.onSurfaceMuted,
                    fontSize = 13.sp,
                )
                else -> {
                    if (signal.dataTruncated) {
                        // The heading says how much was cut; this says what that means for what
                        // is below it, which is the part a reader acts on.
                        Text(
                            "what follows is a prefix, and is not valid JSON",
                            color = colors.clientError,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                    }
                    Text(
                        payload,
                        color = colors.onSurface,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(colors.surfaceElevated)
                            .padding(10.dp),
                    )
                }
            }

            if (history.size > 1) {
                SectionTitle("History — ${history.size} observations")
                // Newest first regardless of the list's own order toggle. The list's toggle is
                // about reading traffic as a log; this is a question about one value, and the
                // answer starts from what it is now and works backwards.
                // By index, not by `indexOf`: two observations of one key can be equal in every
                // field a data class compares — same tag, same name, same payload, emitted in one
                // conflation batch at one `mono` — and `indexOf` would then hand every one of them
                // the same predecessor.
                for (i in history.indices.reversed()) {
                    val entry = history[i]
                    HistoryRow(
                        entry = entry,
                        previous = history.getOrNull(i - 1),
                        current = entry.id == signal.id,
                        tagColor = tagColor,
                        onClick = { onSelectSignal(entry) },
                    )
                }
            }
        }
    }
}

/**
 * One past observation of the same key.
 *
 * The gap from the one before it is the column that earns its place: a value that moved three
 * times in 40ms is a state holder firing on every keystroke, and one that moved three times over
 * two minutes is the app actually changing. The bare timestamps do not say which without
 * arithmetic the reader should not be doing.
 */
@Composable
private fun HistoryRow(
    entry: Signal,
    previous: Signal?,
    current: Boolean,
    tagColor: Color,
    onClick: () -> Unit,
) {
    val colors = LocalInspectorColors.current
    Row(
        Modifier.fillMaxWidth()
            .clickable(enabled = !current, onClick = onClick)
            .background(if (current) colors.surfaceElevated else Color.Transparent)
            .padding(vertical = 8.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            formatClock(entry.ts),
            color = if (current) colors.onSurface else colors.accent,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
        )
        Text(
            previous?.let { "+${formatDuration(entry.mono - it.mono)}" } ?: "first",
            color = colors.onSurfaceMuted,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f),
        )
        if (entry.trigger == SignalTrigger.Request) {
            Text("pulled", color = colors.onSurfaceMuted, fontSize = 10.sp)
        }
        if (entry.bytes > 0) {
            Text(
                formatBytes(entry.bytes),
                color = colors.onSurfaceMuted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        if (current) {
            Text("showing", color = tagColor, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** The tag, tinted from the same palette the lane stripe uses so the two read as one thing. */
@Composable
private fun TagBadge(tag: String) {
    val colors = LocalInspectorColors.current
    val tagColor = colors.forTag(tag)
    Text(
        tag,
        color = tagColor,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier
            .clip(RoundedCornerShape(PILL_RADIUS))
            .background(tagColor.copy(alpha = 0.18f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

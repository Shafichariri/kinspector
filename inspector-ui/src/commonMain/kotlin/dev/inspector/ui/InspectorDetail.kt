package dev.inspector.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import dev.inspector.model.BodyOmission
import dev.inspector.model.NetworkTransaction
import kotlinx.coroutines.delay

/**
 * The faces of one call, in the order they are read. Response first: it is what the row was opened
 * for, and the overview is the part a list row already summarised.
 */
internal enum class DetailTab(val label: String) { Response("Response"), Request("Request"), Overview("Overview") }

/**
 * Detail view for one attempt.
 *
 * Shows the attempt chain rather than only this row, because a redirect or retry is usually only
 * intelligible next to its siblings.
 */
@Composable
internal fun InspectorDetail(
    txn: NetworkTransaction,
    siblings: List<NetworkTransaction>,
    requestBody: ByteArray?,
    responseBody: ByteArray?,
    onSelectSibling: (NetworkTransaction) -> Unit,
    onCopy: (String) -> Unit,
    onBack: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Which face is showing. Owned by the overlay so it survives moving between rows: comparing
     * one field down a list means picking the same tab on every row otherwise, which is why the
     * web remembers it too. Defaulted for callers that only ever show one screen.
     */
    tab: DetailTab = DetailTab.Response,
    onTab: (DetailTab) -> Unit = {},
    /** Folding per body, held by the overlay so it survives leaving the screen. */
    treeStates: JsonTreeStates = remember { JsonTreeStates() },
) {
    val colors = LocalInspectorColors.current
    var menuOpen by remember { mutableStateOf(false) }
    InspectorBackHandler(enabled = menuOpen) { menuOpen = false }

    Box(modifier.fillMaxSize()) {
        Column(Modifier.inspectorScreen(colors.surface)) {
            // What this call is, in the same order as the row it was opened from: method, path,
            // status. The path keeps its end — the part that names the endpoint.
            Row(
                Modifier.fillMaxWidth().height(48.dp).background(colors.surfaceElevated).padding(start = 4.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                IconBox("‹", "back to the list", onBack)
                MethodBadge(txn.method)
                StartEllipsisText(
                    txn.path,
                    color = colors.onSurface,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    statusLabel(txn),
                    color = colors.forStatus(txn.status),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                )
                IconBox("⋮", "more actions") { menuOpen = !menuOpen }
                CloseButton(onClose)
            }

            Row(
                Modifier.fillMaxWidth().background(colors.surfaceElevated).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                for (candidate in DetailTab.entries) {
                    val active = candidate == tab
                    Column(
                        Modifier.clickable { onTab(candidate) }.padding(top = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            candidate.label,
                            color = if (active) colors.onSurface else colors.onSurfaceMuted,
                            fontSize = 13.sp,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        )
                        // An indicator under the active tab, not a colour change alone: accent
                        // text read as a link, and a tab bar is not a row of links.
                        Box(
                            Modifier.padding(top = 8.dp).height(2.dp).width(28.dp)
                                .background(if (active) colors.accent else Color.Transparent),
                        )
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))

            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
                when (tab) {
                    DetailTab.Overview -> Overview(txn, siblings, onSelectSibling, onCopy)
                    DetailTab.Request -> BodySection(
                        headers = txn.reqHeaders,
                        body = requestBody,
                        contentType = txn.reqContentType,
                        truncated = txn.reqBodyTruncated,
                        totalBytes = txn.reqBytes,
                        omitted = txn.reqBodyOmitted,
                        redacted = txn.redacted,
                        onCopy = onCopy,
                        treeKey = "txn:${txn.id}:req",
                        treeStates = treeStates,
                    )
                    DetailTab.Response -> BodySection(
                        headers = txn.resHeaders,
                        body = responseBody,
                        contentType = txn.resContentType,
                        truncated = txn.resBodyTruncated,
                        totalBytes = txn.resBytes,
                        omitted = txn.resBodyOmitted,
                        redacted = txn.redacted,
                        onCopy = onCopy,
                        treeKey = "txn:${txn.id}:res",
                        treeStates = treeStates,
                    )
                }
            }
        }

        if (menuOpen) {
            // Copying was a header button; it moved here with the header down to what the call
            // *is*. Each item says it worked, because iOS and desktop show nothing on a copy.
            MenuPanel(onDismiss = { menuOpen = false }) {
                CopyMenuItem("Copy cURL", onDone = { menuOpen = false }) { onCopy(toCurl(txn, requestBody)) }
                CopyMenuItem("Copy URL", onDone = { menuOpen = false }) { onCopy(txn.url) }
            }
        }
    }
}

/** A menu item that copies, says so, and then closes the menu. */
@Composable
private fun CopyMenuItem(label: String, onDone: () -> Unit, copy: () -> Unit) {
    val colors = LocalInspectorColors.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(COPIED_LINGER_MS)
            onDone()
        }
    }
    MenuItem(
        label = if (copied) "Copied" else label,
        color = if (copied) colors.success else colors.onSurface,
        enabled = !copied,
    ) {
        copy()
        copied = true
    }
}

/** Long enough to read the word, short enough not to be in the way. */
private const val COPIED_LINGER_MS = 700L

@Composable
private fun Overview(
    txn: NetworkTransaction,
    siblings: List<NetworkTransaction>,
    onSelectSibling: (NetworkTransaction) -> Unit,
    onCopy: (String) -> Unit,
) {
    val colors = LocalInspectorColors.current

    Field("URL", txn.url, onCopy = { onCopy(txn.url) })
    Field("Status", txn.status?.toString() ?: "transport failure")
    txn.error?.let { Field("Error", it, onCopy = { onCopy(it) }) }
    Field("Duration", formatDuration(txn.ms))
    Field("Request size", formatBytes(txn.reqBytes))
    Field("Response size", formatBytes(txn.resBytes))
    // The list's clock, so a row and its detail name the same moment the same way; the absolute
    // date underneath, for lining up against a log from another day or another zone.
    Field("Started", formatClock(txn.ts), caption = utcCaption(txn.ts))

    if (txn.redacted.isNotEmpty()) {
        SectionTitle("Redacted at capture")
        // Stated explicitly so nobody — human or agent — reads an absent value as "not sent".
        for (entry in txn.redacted) {
            Text(
                "· $entry",
                color = colors.clientError,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(vertical = 1.dp),
            )
        }
    }

    if (siblings.size > 1) {
        SectionTitle("Attempt chain")
        for (sibling in siblings) {
            val isCurrent = sibling.id == txn.id
            Text(
                "${sibling.attempt}. ${sibling.method} ${sibling.path} → ${statusLabel(sibling)} (${formatDuration(sibling.ms)})",
                color = if (isCurrent) colors.onSurface else colors.accent,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.clickable { onSelectSibling(sibling) }.padding(vertical = 3.dp),
            )
        }
    }
}

@Composable
private fun BodySection(
    headers: Map<String, List<String>>,
    body: ByteArray?,
    contentType: String?,
    truncated: Boolean,
    totalBytes: Long,
    omitted: String?,
    redacted: List<String>,
    onCopy: (String) -> Unit,
    treeKey: String,
    treeStates: JsonTreeStates,
) {
    val colors = LocalInspectorColors.current
    val redactedHeaders = remember(redacted) {
        redacted.filter { it.startsWith("header:") }.mapTo(mutableSetOf()) { it.removePrefix("header:") }
    }

    // Decoded once here rather than inside the branch, so the copy button in the section title
    // can offer exactly the text the pane is showing.
    val rendered = remember(body, contentType) {
        body?.takeIf { it.isNotEmpty() }?.decodeToString()?.let {
            if (looksLikeJson(contentType, it)) prettyJson(it) else it
        }
    }

    // A tree only for a whole body: a truncated prefix that happened to parse would be drawn as
    // though it were all of it. The raw text is still what copy and raw use.
    val tree = remember(body, contentType, truncated) {
        body?.takeIf { !truncated && it.isNotEmpty() }?.decodeToString()
            ?.takeIf { looksLikeJson(contentType, it) }
            ?.let(::parseJsonTree)
    }

    // The tree carries its own label, size and copy, so the plain title would say it twice.
    if (tree == null) SectionTitle("Body", onCopy = rendered?.let { { onCopy(it) } })
    when {
        // A recorded reason outranks the byte count. A discarded hop reports zero bytes because
        // nothing was ever read from it, and falling through to "empty" here would state as fact
        // the one thing capture could not determine.
        body == null && omitted != null -> Text(
            bodyAbsenceReason(omitted, contentType, totalBytes),
            color = colors.onSurfaceMuted,
            fontSize = 12.sp,
        )

        body == null && totalBytes > 0 -> Text(
            bodyAbsenceReason(omitted, contentType, totalBytes),
            color = colors.onSurfaceMuted,
            fontSize = 12.sp,
        )

        rendered == null -> Text("empty", color = colors.onSurfaceMuted, fontSize = 12.sp)

        tree != null -> JsonTree(
            root = tree,
            label = "Body · ${formatBytes(totalBytes)}",
            text = rendered,
            state = treeStates.get(treeKey) ?: remember(tree) { initialTreeState(tree) },
            onState = { treeStates.set(treeKey, it) },
            onCopy = onCopy,
        )

        else -> {
            if (truncated) {
                Text(
                    "truncated at ${formatBytes((body?.size ?: 0).toLong())} of ${formatBytes(totalBytes)}",
                    color = colors.clientError,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            Box(
                Modifier.fillMaxWidth()
                    .background(colors.surfaceElevated)
                    .padding(8.dp)
                    .horizontalScroll(rememberScrollState()),
            ) {
                Text(
                    rendered,
                    color = colors.onSurface,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }

    // After the body, as the web has them: the body is the part that differs and the reason the
    // call was opened; the headers are long, mostly boilerplate, and the same on every call.
    SectionTitle(
        "Headers",
        onCopy = if (headers.isEmpty()) null else {
            { onCopy(headers.entries.joinToString("\n") { "${it.key}: ${it.value.joinToString(", ")}" }) }
        },
    )
    if (headers.isEmpty()) {
        Text("none", color = colors.onSurfaceMuted, fontSize = 12.sp)
    } else {
        for ((name, values) in headers) {
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(
                    "$name: ",
                    color = colors.onSurfaceMuted,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
                // Said in words, and in the warning colour: the placeholder alone reads as a value the
                // app sent, and "the token was never sent" is the wrong conclusion to hand anyone.
                if (name.lowercase() in redactedHeaders) {
                    Text("redacted at capture", color = colors.clientError, fontSize = 12.sp)
                } else {
                    Text(
                        values.joinToString(", "),
                        color = colors.onSurface,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }

}

/**
 * Explains an absent body from what capture recorded, rather than assuming a cause.
 *
 * The single hardcoded "outside the capture allowlist" line this replaces was wrong often enough
 * to send someone hunting a content-type problem that did not exist.
 */
private fun bodyAbsenceReason(omitted: String?, contentType: String?, totalBytes: Long): String {
    val size = formatBytes(totalBytes)
    return when (omitted) {
        BodyOmission.CONTENT_TYPE -> {
            val named = contentType?.let { "content type $it is" }
                ?: "no content type was declared, so it is"
            "$size not captured — $named not on the capture allowlist " +
                "(set captureAllBodies = true to capture it anyway)"
        }

        BodyOmission.STREAMING -> "$size not captured — streamed body, never held in memory"

        // Says "not read", not "empty". This hop may well have carried a body; the client threw the
        // response away to follow a redirect or retry before anything could read it.
        BodyOmission.DISCARDED ->
            "body not read — the client discarded this response to make the next attempt"

        else -> "$size recorded, but the body is no longer in the device buffer"
    }
}

@Composable
internal fun Field(label: String, value: String, onCopy: (() -> Unit)? = null, caption: String? = null) {
    val colors = LocalInspectorColors.current
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = colors.onSurfaceMuted, fontSize = 11.sp)
            if (onCopy != null) {
                Spacer(Modifier.width(8.dp))
                CopyButton(onCopy)
            }
        }
        Text(value, color = colors.onSurface, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
        if (caption != null) {
            Text(caption, color = colors.onSurfaceMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

/**
 * `22 Sep 2026 · 09:41:07.412 UTC` from a capture timestamp, or the timestamp itself when it is
 * not the shape capture writes — shown as recorded rather than guessed at.
 */
internal fun utcCaption(ts: String): String {
    val match = UTC_TS.matchEntire(ts) ?: return ts
    val (year, month, day, time, fraction) = match.destructured
    val name = MONTHS.getOrNull(month.toInt() - 1) ?: return ts
    val millis = fraction.removePrefix(".").take(3).padEnd(3, '0')
    return "${day.toInt()} $name $year · $time.$millis UTC"
}

private val UTC_TS = Regex("""(\d{4})-(\d{2})-(\d{2})T(\d{2}:\d{2}:\d{2})(\.\d+)?Z""")
private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

@Composable
internal fun SectionTitle(title: String, onCopy: (() -> Unit)? = null) {
    val colors = LocalInspectorColors.current
    Row(
        Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            color = colors.onSurfaceMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
        if (onCopy != null) {
            Spacer(Modifier.width(8.dp))
            CopyButton(onCopy)
        }
    }
}

/**
 * Copy affordance for one block of text.
 *
 * Confirms in place for a moment after a tap. On Android 13+ the system shows its own clipboard
 * toast, but iOS and desktop show nothing at all, and a copy button that gives no feedback reads
 * as broken — which is exactly how the first phone tester read the toolbar.
 */
@Composable
internal fun CopyButton(onCopy: () -> Unit) {
    val colors = LocalInspectorColors.current
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1200)
            copied = false
        }
    }

    Text(
        if (copied) "copied" else "copy",
        color = if (copied) colors.success else colors.accent,
        fontSize = 11.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable {
                onCopy()
                copied = true
            }
            // Generous relative to the text so it stays tappable on a phone.
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

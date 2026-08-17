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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import dev.inspector.model.BodyOmission
import dev.inspector.model.NetworkTransaction
import kotlinx.coroutines.delay

internal enum class DetailTab { Overview, Request, Response }

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
) {
    val colors = LocalInspectorColors.current
    var tab by remember { mutableStateOf(DetailTab.Overview) }

    Column(modifier.inspectorScreen(colors.surface)) {
        Row(
            Modifier.fillMaxWidth().background(colors.surfaceElevated).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ToolbarButton("‹ back", onBack)
            Text(
                "${txn.method} ${pathTail(txn.path)}",
                color = colors.onSurface,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            ToolbarButton("cURL", onClick = { onCopy(toCurl(txn, requestBody)) })
            ToolbarButton("✕", onClose, prominent = true)
        }

        Row(
            Modifier.fillMaxWidth().background(colors.surfaceElevated).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            for (candidate in DetailTab.entries) {
                Text(
                    candidate.name,
                    color = if (candidate == tab) colors.accent else colors.onSurfaceMuted,
                    fontSize = 13.sp,
                    fontWeight = if (candidate == tab) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.clickable { tab = candidate }.padding(vertical = 10.dp),
                )
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
                    onCopy = onCopy,
                )
                DetailTab.Response -> BodySection(
                    headers = txn.resHeaders,
                    body = responseBody,
                    contentType = txn.resContentType,
                    truncated = txn.resBodyTruncated,
                    totalBytes = txn.resBytes,
                    omitted = txn.resBodyOmitted,
                    onCopy = onCopy,
                )
            }
        }
    }
}

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
    Field("Started", txn.ts)

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
    onCopy: (String) -> Unit,
) {
    val colors = LocalInspectorColors.current

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
                Text(
                    values.joinToString(", "),
                    color = colors.onSurface,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }

    // Decoded once here rather than inside the branch, so the copy button in the section title
    // can offer exactly the text the pane is showing.
    val rendered = remember(body, contentType) {
        body?.takeIf { it.isNotEmpty() }?.decodeToString()?.let {
            if (looksLikeJson(contentType, it)) prettyJson(it) else it
        }
    }

    SectionTitle("Body", onCopy = rendered?.let { { onCopy(it) } })
    when {
        body == null && totalBytes > 0 -> Text(
            bodyAbsenceReason(omitted, contentType, totalBytes),
            color = colors.onSurfaceMuted,
            fontSize = 12.sp,
        )

        rendered == null -> Text("empty", color = colors.onSurfaceMuted, fontSize = 12.sp)

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

        else -> "$size recorded, but the body is no longer in the device buffer"
    }
}

@Composable
private fun Field(label: String, value: String, onCopy: (() -> Unit)? = null) {
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
    }
}

@Composable
private fun SectionTitle(title: String, onCopy: (() -> Unit)? = null) {
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
private fun CopyButton(onCopy: () -> Unit) {
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

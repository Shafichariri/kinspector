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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import dev.inspector.model.NetworkTransaction

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
    onCopyCurl: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalInspectorColors.current
    var tab by remember { mutableStateOf(DetailTab.Overview) }

    Column(modifier.fillMaxSize().background(colors.surface)) {
        Row(
            Modifier.fillMaxWidth().background(colors.surfaceElevated).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ToolbarButton("back", onBack)
            Text(
                "${txn.method} ${pathTail(txn.path)}",
                color = colors.onSurface,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            ToolbarButton("copy cURL") { onCopyCurl(toCurl(txn, requestBody)) }
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
                DetailTab.Overview -> Overview(txn, siblings, onSelectSibling)
                DetailTab.Request -> BodySection(
                    headers = txn.reqHeaders,
                    body = requestBody,
                    contentType = txn.reqContentType,
                    truncated = txn.reqBodyTruncated,
                    totalBytes = txn.reqBytes,
                )
                DetailTab.Response -> BodySection(
                    headers = txn.resHeaders,
                    body = responseBody,
                    contentType = txn.resContentType,
                    truncated = txn.resBodyTruncated,
                    totalBytes = txn.resBytes,
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
) {
    val colors = LocalInspectorColors.current

    Field("URL", txn.url)
    Field("Status", txn.status?.toString() ?: "transport failure")
    txn.error?.let { Field("Error", it) }
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
) {
    val colors = LocalInspectorColors.current

    SectionTitle("Headers")
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

    SectionTitle("Body")
    when {
        body == null && totalBytes > 0 -> Text(
            "${formatBytes(totalBytes)} not captured — content type is outside the capture allowlist",
            color = colors.onSurfaceMuted,
            fontSize = 12.sp,
        )

        body == null || body.isEmpty() -> Text("empty", color = colors.onSurfaceMuted, fontSize = 12.sp)

        else -> {
            if (truncated) {
                Text(
                    "truncated at ${formatBytes(body.size.toLong())} of ${formatBytes(totalBytes)}",
                    color = colors.clientError,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            val text = remember(body) { body.decodeToString() }
            val rendered = remember(text, contentType) {
                if (looksLikeJson(contentType, text)) prettyJson(text) else text
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

@Composable
private fun Field(label: String, value: String) {
    val colors = LocalInspectorColors.current
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(label, color = colors.onSurfaceMuted, fontSize = 11.sp)
        Text(value, color = colors.onSurface, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun SectionTitle(title: String) {
    val colors = LocalInspectorColors.current
    Text(
        title,
        color = colors.onSurfaceMuted,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
    )
}

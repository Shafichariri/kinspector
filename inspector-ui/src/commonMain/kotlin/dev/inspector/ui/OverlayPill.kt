package dev.inspector.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import dev.inspector.model.NetworkTransaction
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * The always-on HUD: a small draggable pill showing the most recent call.
 *
 * Deliberately terse — method, path tail, status, duration — because its job is to be glanceable
 * while you use the app, not to be read. Tapping it opens the full inspector.
 */
@Composable
internal fun OverlayPill(
    latest: NetworkTransaction?,
    inFlight: Int,
    collapsed: Boolean,
    onTap: () -> Unit,
    onToggleCollapsed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalInspectorColors.current

    // Fades back after activity so it stops competing with the app for attention.
    var active by remember { mutableStateOf(false) }
    LaunchedEffect(latest?.id) {
        if (latest != null) {
            active = true
            delay(2000)
            active = false
        }
    }
    val alpha by animateFloatAsState(
        targetValue = if (active) 1f else 0.6f,
        animationSpec = tween(durationMillis = 400),
    )

    BoxWithConstraints(modifier) {
        val maxWidthPx = with(androidx.compose.ui.platform.LocalDensity.current) { maxWidth.toPx() }
        val maxHeightPx = with(androidx.compose.ui.platform.LocalDensity.current) { maxHeight.toPx() }

        var offsetX by remember { mutableStateOf(24f) }
        var offsetY by remember { mutableStateOf(120f) }
        var pillWidth by remember { mutableStateOf(0) }
        var pillHeight by remember { mutableStateOf(0) }

        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                .onSizeChanged { pillWidth = it.width; pillHeight = it.height }
                .alpha(alpha)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDrag = { change, drag ->
                            change.consume()
                            offsetX = (offsetX + drag.x).coerceIn(0f, (maxWidthPx - pillWidth).coerceAtLeast(0f))
                            offsetY = (offsetY + drag.y).coerceIn(0f, (maxHeightPx - pillHeight).coerceAtLeast(0f))
                        },
                        onDragEnd = {
                            // Snap to whichever edge is nearer, so it never sits mid-screen
                            // covering content.
                            val center = offsetX + pillWidth / 2f
                            offsetX = if (center < maxWidthPx / 2f) 8f else (maxWidthPx - pillWidth - 8f).coerceAtLeast(0f)
                        },
                    )
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onTap() },
                        onLongPress = { onToggleCollapsed() },
                    )
                },
        ) {
            if (collapsed) {
                CollapsedDot(latest, inFlight)
            } else {
                ExpandedPill(latest, inFlight)
            }
        }
    }
}

@Composable
private fun CollapsedDot(latest: NetworkTransaction?, inFlight: Int) {
    val colors = LocalInspectorColors.current
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(colors.surfaceElevated),
        contentAlignment = Alignment.Center,
    ) {
        if (inFlight > 0) {
            Text(
                text = inFlight.toString(),
                color = colors.onSurface,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
        } else {
            Box(
                Modifier.size(10.dp).clip(CircleShape)
                    .background(colors.forStatus(latest?.status)),
            )
        }
    }
}

@Composable
private fun ExpandedPill(latest: NetworkTransaction?, inFlight: Int) {
    val colors = LocalInspectorColors.current
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surfaceElevated)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.size(8.dp).clip(CircleShape)
                .background(colors.forStatus(latest?.status)),
        )

        if (latest == null) {
            Text(
                "no traffic yet",
                color = colors.onSurfaceMuted,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
            return@Row
        }

        MethodBadge(latest.method, fontSize = 11.sp)
        Text(
            text = pathTail(latest.path),
            color = colors.onSurface,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            // Monospace so the row does not jitter as digit counts change.
            text = "${statusLabel(latest)}  ${formatDuration(latest.ms)}",
            color = colors.onSurfaceMuted,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
        )
        if (inFlight > 0) {
            Text(
                text = "+$inFlight",
                color = colors.accent,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

package dev.inspector.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text

/**
 * The HTTP method, as a filled badge in its method colour.
 *
 * A badge rather than coloured text: at 11sp on a phone, a hue change alone is easy to miss while
 * scanning, which is the only thing this label is for. The tint carries the signal at a glance and
 * the text stays readable.
 *
 * The background is the method colour at low alpha rather than a solid fill with white text. Solid
 * works for GET's blue and fails for PATCH's amber in the light theme, where white on amber is
 * unreadable — one rule that holds for all five beats five exceptions.
 *
 * @param minWidth set on the list so the path column still lines up across rows of different verbs.
 */
@Composable
internal fun MethodBadge(
    method: String,
    fontSize: androidx.compose.ui.unit.TextUnit = 11.sp,
    minWidth: androidx.compose.ui.unit.Dp = 0.dp,
    modifier: Modifier = Modifier,
) {
    val color = LocalInspectorColors.current.forMethod(method)
    Box(
        modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = BADGE_TINT_ALPHA))
            .widthIn(min = minWidth)
            .padding(horizontal = 5.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            method.uppercase(),
            color = color,
            fontSize = fontSize,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

/** Enough tint to read as a chip against both themes' surfaces, not enough to fight the text. */
private const val BADGE_TINT_ALPHA = 0.18f

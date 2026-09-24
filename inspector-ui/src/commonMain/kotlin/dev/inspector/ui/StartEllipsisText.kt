package dev.inspector.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit

/**
 * One line of text that, when it does not fit, loses its **front** and keeps its end.
 *
 * `TextOverflow.StartEllipsis` is meant to do this and does not everywhere. On the skiko renderer
 * — desktop and **iOS** — it falls back to an end ellipsis, measured: `/v3/some-service/accounts/
 * balance` at 150dp renders `/v3/some-service/ac…` under Ellipsis, StartEllipsis and
 * MiddleEllipsis alike. Every path, signal name and scope in the overlay was written against
 * StartEllipsis, because the tail is what names an endpoint, so on an iPhone every one of them was
 * cutting off exactly the part it existed to keep. Android's platform text honours it, which is
 * why nobody saw this there.
 *
 * So the fitting is done here, by measurement: the longest tail that fits after an ellipsis. The
 * string drawn is also the string in the semantics tree, so a screen reader and a test both read
 * what is actually on screen.
 */
@Composable
internal fun StartEllipsisText(
    text: String,
    color: Color,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
    fontFamily: FontFamily? = null,
    fontWeight: FontWeight? = null,
) {
    val style = TextStyle(color = color, fontSize = fontSize, fontFamily = fontFamily, fontWeight = fontWeight)
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        val max = constraints.maxWidth
        val shown = remember(text, style, max) {
            if (max == Constraints.Infinity) {
                text
            } else {
                fitTail(text, max) { measurer.measure(it, style, maxLines = 1, softWrap = false).size.width }
            }
        }
        Text(shown, style = style, maxLines = 1, softWrap = false)
    }
}

/**
 * [text] if it fits in [maxWidth], else `…` and the longest tail of it that does.
 *
 * A binary search over the tail length, because width grows with it monotonically — a handful of
 * measurements for any path rather than one per character. Pure apart from [width], so the fitting
 * rule is testable without a renderer.
 */
internal fun fitTail(text: String, maxWidth: Int, width: (String) -> Int): String {
    if (width(text) <= maxWidth) return text
    var lo = 0
    var hi = text.length - 1
    while (lo < hi) {
        val mid = (lo + hi + 1) / 2
        if (width(ELLIPSIS + text.takeLast(mid)) <= maxWidth) lo = mid else hi = mid - 1
    }
    return ELLIPSIS + text.takeLast(lo)
}

private const val ELLIPSIS = "…"

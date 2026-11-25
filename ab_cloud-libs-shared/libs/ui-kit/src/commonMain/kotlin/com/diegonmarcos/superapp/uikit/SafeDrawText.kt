package com.diegonmarcos.superapp.uikit

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText

/**
 * #1921 Compose's drawText(textMeasurer, text, topLeft, ...) lays the text out in the room left between topLeft and the
 * canvas edge; an origin at or past the right or bottom edge leaves negative room and it throws "maxWidth must be >=
 * than minWidth" (Cloud Wiki crashed twice). Every such call in the fleet goes through [safeDrawText] / [safeTextOrigin].
 */
fun safeTextOrigin(topLeft: Offset, width: Float, height: Float): Offset? {
    if (!topLeft.x.isFinite() || !topLeft.y.isFinite() || width <= 0f || height <= 0f) return null
    if (topLeft.x >= width - 1f || topLeft.y >= height - 1f) return null
    return Offset(topLeft.x.coerceAtLeast(0f), topLeft.y.coerceAtLeast(0f))
}

/** drawText that skips text whose origin is outside the canvas and clamps negative origins to 0. */
fun DrawScope.safeDrawText(
    measurer: TextMeasurer,
    text: String,
    topLeft: Offset,
    style: TextStyle = TextStyle.Default,
    maxLines: Int = Int.MAX_VALUE,
    softWrap: Boolean = true,
) {
    val at = safeTextOrigin(topLeft, size.width, size.height) ?: return
    drawText(measurer, text, topLeft = at, style = style, maxLines = maxLines, softWrap = softWrap)
}

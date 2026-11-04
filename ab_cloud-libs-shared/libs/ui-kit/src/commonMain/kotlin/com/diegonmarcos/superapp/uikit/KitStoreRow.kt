package com.diegonmarcos.superapp.uikit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * #1452 THE Cloud Store list row, as a shared part. The Store's Apps list (libs:appstore StoreRow.Head) and every other list that
 * must look like it (Cloud Drive's Git table) draw their row from here: the same scaled spacing steps (StoreDensity SCALE 0.7,
 * TEXT_SCALE 0.78), the same type ramp, the same status glyphs and palette. StoreDensity's SCALE / TEXT_SCALE are these constants.
 */
object KitStoreRow {
    const val SCALE = 0.7f
    const val TEXT_SCALE = 0.78f

    /** A Store spacing step (dp before SCALE). */
    fun step(n: Int): Dp = (n * SCALE).dp

    val padH: Dp = step(12)
    val padV: Dp = step(8)
    val gap: Dp = step(8)
    val tight: Dp = step(4)

    val caption = (11f * TEXT_SCALE).sp
    val meta = (12f * TEXT_SCALE).sp
    val body = (13f * TEXT_SCALE).sp
    val title = (14f * TEXT_SCALE).sp

    // The Store's palette (dark by design): status ink and the quick-verb fills.
    val UP = Color(0xFF48BB78)
    val UPDATE = Color(0xFFF6AD55)
    val MISSING = Color(0xFF9F7AEA)
    val BAD = Color(0xFFE53E3E)
    val BLUE_FILL = Color(0xFF2B6CB0)
    val AMBER_FILL = Color(0xFFED8936)
    val RULE = Color(0xFF2A2A33)
}

/** The row's line: full width, the Store's side and vertical padding, a plain tap (no ripple, as in the Store). */
@Composable
fun KitStoreRowLine(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.fillMaxWidth().let { if (onClick != null) it.clickable(interactionSource = null, indication = null, onClick = onClick) else it }
            .padding(horizontal = KitStoreRow.padH, vertical = KitStoreRow.padV),
        verticalAlignment = Alignment.CenterVertically, content = content,
    )
}

/** The row's state glyph (✓ ⬆ ◯ · ⚠), in the state's ink. */
@Composable
fun KitStoreDot(glyph: String, color: Color, modifier: Modifier = Modifier, endPad: Dp = KitStoreRow.gap) {
    Text(glyph, color = color, fontSize = KitStoreRow.body, modifier = modifier.padding(end = endPad))
}

/** The row's name: title size, bold, one line. */
@Composable
fun KitStoreName(text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    Text(text, color = color, fontSize = KitStoreRow.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
}

/** One cell or caption of a row: caption size, one line. */
@Composable
fun KitStoreCaption(text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified, mono: Boolean = false, align: TextAlign? = null) {
    Text(text, color = color, fontSize = KitStoreRow.caption, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = align,
        fontFamily = if (mono) FontFamily.Monospace else null, modifier = modifier)
}

/** The Store's quick verb (a small filled box with a bold label) at the end of a row. */
@Composable
fun KitStoreQuick(label: String, fill: Color, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Text(
        label, color = Color.White, fontSize = KitStoreRow.body, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 1,
        modifier = modifier.background(fill).let { if (onClick != null) it.clickable(interactionSource = null, indication = null, onClick = onClick) else it }
            .padding(horizontal = KitStoreRow.padH, vertical = KitStoreRow.tight),
    )
}

package com.diegonmarcos.superapp.launcher

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.extinstall.extInstallRing
import com.diegonmarcos.superapp.settings.GridColumns
import com.diegonmarcos.superapp.ui.Haptics
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/** The icon edge in dp: [GridColumns.iconPx] over the screen minus [pageDp], split [cols] ways, minus [chromeDp]. */
internal fun tileIconDp(widthPx: Int, density: Float, cols: Int, pageDp: Int, chromeDp: Int): Dp {
    val cell = GridColumns.cellPx(widthPx - (pageDp * density).toInt(), cols)
    val px = GridColumns.iconPx((32 * density).toInt(), cell, (chromeDp * density).toInt(), (16 * density).toInt())
    // Hard ceiling: the glyph is never larger than the 32 dp the View tile drew, whatever the arithmetic above says.
    return (px / density).coerceAtMost(TILE_ICON_MAX_DP).dp
}

/** item_tile.xml's icon edge: the most a launcher tile glyph may be. */
internal const val TILE_ICON_MAX_DP = 32f

/**
 * What the tiles REALLY measured on this device (px from layout, divided by the density they were laid out at), kept for
 * Configs ▸ Observability ▸ X-Ray (its DIAGNOSTICS group) and its remote read. Written from the composition, read from anywhere.
 */
object TileMetrics {
    data class Sample(val id: String, val tileWDp: Float, val tileHDp: Float, val iconWDp: Float, val iconHDp: Float,
                      val density: Float, val atMs: Long)

    private val tiles = java.util.concurrent.ConcurrentHashMap<String, FloatArray>() // w,h,icon w,icon h,density
    @Volatile private var last: String? = null

    internal fun tile(id: String, w: Int, h: Int, density: Float) {
        val a = tiles.getOrPut(id) { FloatArray(5) }; a[0] = w / density; a[1] = h / density; a[4] = density; last = id
    }

    internal fun icon(id: String, w: Int, h: Int, density: Float) {
        val a = tiles.getOrPut(id) { FloatArray(5) }; a[2] = w / density; a[3] = h / density; a[4] = density; last = id
    }

    /** The most recently measured tile, or null before any grid has been drawn. */
    fun latest(): Sample? = last?.let { id -> tiles[id]?.let { Sample(id, it[0], it[1], it[2], it[3], it[4], 0L) } }

    /** The largest glyph any tile measured: the number that answers "are the icons huge". */
    fun largestIconDp(): Float = tiles.values.maxOfOrNull { maxOf(it[2], it[3]) } ?: 0f

    fun count(): Int = tiles.size

    fun clear() { tiles.clear(); last = null }
}

/** Height of the inline divider: a thin rule shorter than the glyph it sits beside. */
internal val SEP_HEIGHT: Dp = 16.dp

internal fun tileSepTag(id: String) = "tilesep:$id"

/**
 * A `separator` page as a thin inline divider (#438, #1069): NOT a row child and NOT a tile, so it takes no column and the
 * icons keep theirs. A tile draws it as an overlay on its own leading/trailing edge (`modifier` carries the `align`),
 * centred vertically on the ICON line ([iconCentreY] = distance from the tile's top to the icon's centre) and horizontally
 * on the boundary ([edgeShift] = half the gap to the neighbour). Never a control, hidden from accessibility.
 */
@Composable
internal fun TileDivider(tag: String, iconCentreY: Dp, edgeShift: Dp, modifier: Modifier = Modifier) {
    Box(modifier.offset(x = edgeShift, y = iconCentreY - SEP_HEIGHT / 2).size(1.dp, SEP_HEIGHT)
        .background(LocalKitPalette.current.textPrimary.copy(alpha = 0.4f))
        .clearAndSetSemantics { testTag = tag })
}

/** Group heading between tile rows: the declared words, upper-cased. */
@Composable
internal fun TileGroupHeader(text: String, modifier: Modifier = Modifier, compact: Boolean = false) {
    Text(text.uppercase(), color = LocalKitPalette.current.textSecondary, fontSize = 11.sp,
        letterSpacing = 0.12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
        modifier = modifier.fillMaxWidth().padding(start = 14.dp, top = if (compact) 6.dp else 14.dp, end = 14.dp,
            bottom = if (compact) 1.dp else 6.dp))
}

/** The heading one level BELOW [TileGroupHeader]: indented and a shade quieter. */
@Composable
internal fun TileSubgroupHeader(text: String, modifier: Modifier = Modifier, compact: Boolean = false) {
    Text(text.uppercase(), color = LocalKitPalette.current.textSecondary, fontSize = 10.sp,
        letterSpacing = 0.10.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
        modifier = modifier.fillMaxWidth().padding(start = 26.dp, top = if (compact) 1.dp else 6.dp, end = 14.dp,
            bottom = if (compact) 0.dp else 4.dp))
}

internal fun tileTag(id: String) = "tile:$id"
internal fun tileIconTag(id: String) = "tileicon:$id"

/** The live build's tile measured 70 dp tall on the A37 (grid-fit@a37-1.0.png, 195 px at 2.8125); the owner asked for half,
 *  same column width. The plate hugs the glyph: a 24 dp rounded square around a 20 dp icon. The ONLY launcher tile. */
internal val TILE_PLATE: Dp = 24.dp
internal val TILE_ICON: Dp = 20.dp
/** Whole tile: half the old 70 dp. Plate 24 + 1 + a 10 sp line. */
internal val CHIP_TILE_HEIGHT: Dp = 35.dp
/** Gap between tile rows. */
internal val CHIP_GAP: Dp = 4.dp

/**
 * The launcher tile: a 20 dp glyph on a faint 24 dp plate that hugs it (never stretched across the column), and a
 * one-line 9 sp label UNDER the plate. Every tile grid draws this; there is no other tile.
 */
@Composable
internal fun CompactTile(tileId: String, label: String, @DrawableRes iconRes: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val p = LocalKitPalette.current
    val view = LocalView.current
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, label = "tile-press")
    Column(
        modifier.height(CHIP_TILE_HEIGHT)
            .onSizeChanged { TileMetrics.tile(tileId, it.width, it.height, density) }
            .scale(scale)
            .clickable(interactionSource = src, indication = LocalIndication.current) { Haptics.tap(view); onClick() }
            .testTag(tileTag(tileId)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(TILE_PLATE).clip(RoundedCornerShape(7.dp)).background(p.textPrimary.copy(alpha = 0.08f))
            .extInstallRing(tileId, TILE_ICON, iconTop = 2.dp).testTag("tileplate:$tileId"),
            contentAlignment = Alignment.Center) {
            Icon(painterResource(iconRes), contentDescription = null, tint = p.textPrimary,
                modifier = Modifier.size(TILE_ICON).testTag(tileIconTag(tileId))
                    .onSizeChanged { TileMetrics.icon(tileId, it.width, it.height, density) })
        }
        Text(label, color = p.textPrimary, fontSize = 9.sp, lineHeight = 10.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            // Unbounded: at fontScale 1.15 the line is ~1.5 dp taller than what 35 dp leaves; it spills into the 4 dp row gap
            // instead of being cut through its descenders ("Notifv").
            modifier = Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true)
                .padding(top = 1.dp, start = 1.dp, end = 1.dp).testTag("tilelabel:$tileId"))
    }
}

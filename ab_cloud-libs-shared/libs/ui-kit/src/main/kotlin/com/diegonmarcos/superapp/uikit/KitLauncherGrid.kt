package com.diegonmarcos.superapp.uikit

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * #1531 the Android launcher home-screen grid as shared pieces: the tile (icon over a one-line ellipsized label), the folder tile
 * (a rounded square holding a 2x2 or 3x3 preview of its first members) and the tap-to-open folder popup (a titled card with its
 * own grid). The numbers are the ones the Cloud SuperApp's Phone > Apps grid draws with (52 dp icon, 11 sp label, 6 dp cell
 * padding, 16 dp folder corner, 28 dp popup card), kept in [KitLauncher] so a page and its test read the same spec.
 */
object KitLauncher {
    const val COLUMNS = 8
    const val ROWS = 8
    const val PAGE = COLUMNS * ROWS
    /** A folder popup is narrower than the home screen, so it has its own, smaller column count. */
    const val POPUP_COLUMNS = 4
    const val ICON_DP = 52
    const val LABEL_SP = 11
    const val CELL_PAD_DP = 6
    const val FOLDER_CORNER_DP = 16
    const val POPUP_CORNER_DP = 28
    const val POPUP_MAX_DP = 560
    const val POPUP_FILL: Long = 0xF21A1A24L

    /** Edge of the folder preview grid: 2 (2x2) up to four members, 3 (3x3) from five. */
    fun previewEdge(members: Int): Int = if (members > 4) 3 else 2

    /** How many members the folder tile previews. */
    fun previewCount(members: Int): Int = minOf(members, previewEdge(members) * previewEdge(members))

    fun rows(items: Int, cols: Int = COLUMNS): Int = (items + cols - 1) / cols

    /** The icon edge for a cell [cellDp] wide: the wanted size, never more than what is left after the padding, never below the floor. */
    fun iconDp(cellDp: Float, want: Float = ICON_DP.toFloat(), chrome: Float = 2f * CELL_PAD_DP, floor: Float = 24f): Float =
        minOf(want, cellDp - chrome).coerceAtLeast(floor)
}

/** One launcher tile; [icon] draws into the square it is given. A disabled tile is dimmed and does nothing. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun KitLauncherTile(
    label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit, onLongClick: (() -> Unit)? = null,
    icon: @Composable (Dp) -> Unit,
) {
    val p = LocalKitPalette.current
    BoxWithConstraints(modifier) {
        val sz = KitLauncher.iconDp(maxWidth.value).dp
        Column(
            Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.4f)
                .combinedClickable(enabled = enabled || onLongClick != null, onClick = { if (enabled) onClick() }, onLongClick = onLongClick)
                .padding(KitLauncher.CELL_PAD_DP.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(sz)) { icon(sz) }
            Text(label, color = p.textPrimary, fontSize = KitLauncher.LABEL_SP.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        }
    }
}

/** A folder tile: a rounded square with the first members' icons as a 2x2 / 3x3 preview. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun KitLauncherFolderTile(
    label: String, previews: List<@Composable (Dp) -> Unit>, modifier: Modifier = Modifier, onOpen: () -> Unit, onLongClick: (() -> Unit)? = null,
) {
    val p = LocalKitPalette.current
    BoxWithConstraints(modifier) {
        val sz = KitLauncher.iconDp(maxWidth.value).dp
        val edge = KitLauncher.previewEdge(previews.size)
        val pad = (sz.value * 0.1f).dp
        val mini = (sz - pad * 2) / edge
        val shape = RoundedCornerShape(minOf(KitLauncher.FOLDER_CORNER_DP.toFloat(), sz.value / 3f).dp)
        Column(
            Modifier.fillMaxWidth().combinedClickable(onClick = onOpen, onLongClick = onLongClick).padding(KitLauncher.CELL_PAD_DP.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(sz).clip(shape).background(p.surface).border(1.dp, p.hairline, shape).padding(pad)) {
                Column {
                    for (r in 0 until edge) Row {
                        for (c in 0 until edge) {
                            Box(Modifier.size(mini).padding(1.dp)) { previews.getOrNull(r * edge + c)?.invoke(mini - 2.dp) }
                        }
                    }
                }
            }
            Text(label, color = p.textPrimary, fontSize = KitLauncher.LABEL_SP.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        }
    }
}

/** [items] in rows of [cols], each cell given a weighted modifier; the last row is padded so the cells keep their width. */
@Composable
fun <T> KitLauncherRows(items: List<T>, cols: Int = KitLauncher.POPUP_COLUMNS, cell: @Composable (T, Modifier) -> Unit) {
    for (row in items.chunked(cols)) {
        Row(Modifier.fillMaxWidth()) {
            for (item in row) cell(item, Modifier.weight(1f))
            for (k in row.size until cols) Spacer(Modifier.weight(1f))
        }
    }
}

/** The folder popup: a rounded card floating over the dimmed page with the folder's title and its own grid; dismissed from the scrim. */
@Composable
fun KitLauncherFolderPopup(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val p = LocalKitPalette.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.padding(20.dp).clip(RoundedCornerShape(KitLauncher.POPUP_CORNER_DP.dp)).background(Color(KitLauncher.POPUP_FILL)).padding(16.dp)) {
            Text(title, color = p.textPrimary, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(vertical = 16.dp))
            Column(Modifier.heightIn(max = KitLauncher.POPUP_MAX_DP.dp).verticalScroll(rememberScrollState())) { content() }
        }
    }
}

package com.diegonmarcos.capitalfinterm.ui.fin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.diegonmarcos.capitalfinterm.FinLogic

/**
 * The EM Bonds CALCULATOR matrix (#1462, tag fin:matrix:<panel>): the header row ([columns]) and every row of [rows] on ONE phone screen, no scrolling,
 * in 20dp rows. Columns are fractions of [width] (FinLogic.matrixWidths) and the numerals are the ramp's small mono (numSmall) shrunk to the widest cell,
 * set in dp so the user's font scale cannot break the fit. [heat] tints a cell red .. green, [bold] marks (row, column) cells, row -1 the header.
 */
@Composable
fun FinMatrix(panelId: String, columns: List<String>, rows: List<List<String>>, heat: List<List<Float?>>, bold: List<Pair<Int, Int>>, width: Dp) {
    val p = LocalFinPalette.current
    val n = columns.size
    if (n == 0) return
    val ws = FinLogic.matrixWidths(width.value, n)
    val longest = maxOf(1, rows.maxOfOrNull { r -> r.drop(2).maxOfOrNull { it.length } ?: 0 } ?: 0, columns.drop(2).maxOfOrNull { it.length } ?: 0)
    val dp = FinLogic.matrixFont(ws.getOrElse(2) { ws.last() }, longest)
    val size = with(LocalDensity.current) { dp.dp.toSp() }
    val base = FinType.numSmall.copy(fontSize = size, lineHeight = size * 1.25f)
    @Composable
    fun line(r: Int, cells: List<String>) {
        Row(Modifier.fillMaxWidth().height(20.dp), verticalAlignment = Alignment.CenterVertically) {
            for (i in 0 until n) {
                val tint = if (r >= 0) heat.getOrNull(r)?.getOrNull(i) else null
                val fill = if (tint == null) Color.Transparent else lerp(p.surfaceRaised, if (tint > 0) p.up else p.down, FinLogic.HEAT_MAX_BLEND * kotlin.math.abs(tint))
                val label = i < 2
                val st = (if (label) base.copy(color = p.text2) else base.copy(color = p.text)).let { if ((r to i) in bold) it.copy(fontWeight = FontWeight.Bold, color = p.text) else it }
                Box(Modifier.width(ws[i].dp).height(20.dp).background(fill), contentAlignment = if (label) Alignment.CenterStart else Alignment.CenterEnd) {
                    Text(FinLogic.cell(cells.getOrNull(i)), Modifier.fillMaxWidth(), style = st, textAlign = if (label) TextAlign.Start else TextAlign.End, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
                }
            }
        }
    }
    Column(Modifier.fillMaxWidth().testTag("fin:matrix:$panelId")) {
        line(-1, columns)
        rows.forEachIndexed { r, cells -> line(r, cells) }
    }
}

package com.diegonmarcos.capitalfinterm.ui.fin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
 * The dense matrix (tag fin:matrix:<panel>): the header row ([columns]) and every row of [rows] on ONE phone screen, no scrolling, in 20dp rows. Columns are
 * fractions of [width] (FinLogic.matrixWidths; the first [labels].size are label columns, left aligned) and the numerals are the ramp's small mono shrunk to the
 * widest body cell, the header and each label column shrunk to their own widest text, all in dp so the user's font scale cannot break the fit. A header
 * cell may hold a line break (the header row is then as many 20dp lines as its tallest cell). [heat] tints a cell red .. green, [bold] marks (row, column)
 * cells, row -1 the header. [onRow] makes every body row tappable (PM Vol: a portfolio row opens its page).
 */
@Composable
fun FinMatrix(
    panelId: String, columns: List<String>, rows: List<List<String>>, heat: List<List<Float?>>, bold: List<Pair<Int, Int>>, width: Dp,
    labels: List<Float> = FinLogic.MATRIX_LABELS, onRow: ((Int) -> Unit)? = null,
) {
    val p = LocalFinPalette.current
    val n = columns.size
    if (n == 0) return
    val nl = labels.size
    val ws = FinLogic.matrixWidths(width.value, n, labels)
    val body = FinLogic.matrixFont(ws.getOrElse(nl) { ws.last() }, maxOf(1, rows.maxOfOrNull { r -> r.drop(nl).maxOfOrNull { it.length } ?: 0 } ?: 0))
    val head = FinLogic.matrixFont(ws.getOrElse(nl) { ws.last() }, maxOf(1, columns.drop(nl).maxOfOrNull { c -> c.lines().maxOf { it.length } } ?: 0))
    val headLines = columns.maxOf { it.lines().size }
    @Composable
    fun line(r: Int, cells: List<String>) {
        val h = (if (r < 0) 20 * headLines else 20).dp
        val tap = if (r >= 0 && onRow != null) Modifier.clickable { onRow(r) } else Modifier
        Row(Modifier.fillMaxWidth().height(h).then(tap), verticalAlignment = Alignment.CenterVertically) {
            for (i in 0 until n) {
                val tint = if (r >= 0) heat.getOrNull(r)?.getOrNull(i) else null
                val fill = if (tint == null) Color.Transparent else lerp(p.surfaceRaised, if (tint > 0) p.up else p.down, FinLogic.HEAT_MAX_BLEND * kotlin.math.abs(tint))
                val label = i < nl
                val fit = if (r < 0) head else if (label) minOf(body, FinLogic.matrixFont(ws[i], rows.maxOfOrNull { it.getOrNull(i)?.length ?: 0 } ?: 1)) else body
                val size = with(LocalDensity.current) { fit.dp.toSp() }
                val base = FinType.numSmall.copy(fontSize = size, lineHeight = size * 1.25f)
                val st = (if (label) base.copy(color = p.text2) else base.copy(color = p.text)).let { if ((r to i) in bold) it.copy(fontWeight = FontWeight.Bold, color = p.text) else it }
                Box(Modifier.width(ws[i].dp).height(h).background(fill), contentAlignment = if (label) Alignment.CenterStart else Alignment.CenterEnd) {
                    Text(FinLogic.cell(cells.getOrNull(i)), Modifier.fillMaxWidth(), style = st, textAlign = if (label) TextAlign.Start else TextAlign.End, maxLines = if (r < 0) headLines else 1, softWrap = false, overflow = TextOverflow.Clip)
                }
            }
        }
    }
    Column(Modifier.fillMaxWidth().testTag("fin:matrix:$panelId")) {
        line(-1, columns)
        rows.forEachIndexed { r, cells -> line(r, cells) }
    }
}

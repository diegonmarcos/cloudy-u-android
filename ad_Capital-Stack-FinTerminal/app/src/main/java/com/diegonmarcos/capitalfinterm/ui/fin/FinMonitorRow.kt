package com.diegonmarcos.capitalfinterm.ui.fin

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.diegonmarcos.capitalfinterm.FinLogic

/**
 * Market-monitor row (spec 2.2, tag fin:row:<symbol>): symbol (88dp) | sparkline 56x20 | last 72dp | chg 64dp | chg% 64dp, fixed
 * widths so a list aligns; 40dp compact, 56dp tall with the name under the symbol. Under 360dp the absolute change is dropped.
 * The row background flashes when its last price changes (spec 5.2).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FinMonitorRow(q: FinQuote, stale: Boolean, width: Dp, tall: Boolean = false, divider: Boolean = true, hPad: Dp = 12.dp, onClick: () -> Unit = {}, onLongClick: (() -> Unit)? = null) {
    val p = LocalFinPalette.current
    val narrow = width < 360.dp
    Column(Modifier.fillMaxWidth().finFlash(q.last, q.dir).combinedClickable(onClick = onClick, onLongClick = onLongClick).testTag("fin:row:${q.symbol}")) {
        Row(Modifier.fillMaxWidth().height(if (tall) FinDim.rowTall else FinDim.rowCompact).padding(horizontal = hPad), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(FinDim.symbolCol)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(q.symbol, color = p.text, style = FinType.symbol, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (stale) { Spacer(Modifier.width(4.dp)); FinDot(p.amber) }
                }
                if (tall && q.name.isNotBlank()) Text(q.name, color = p.text2, style = FinType.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                if (q.history.size >= 2) FinSparkline(q.history, Modifier.size(56.dp, 20.dp))
            }
            val a = if (stale) 0.7f else 1f
            Text(FinLogic.cell(q.last), Modifier.width(FinDim.lastCol).alpha(a), color = if (FinLogic.missing(q.last)) p.text3 else p.text,
                style = FinType.numStrong, textAlign = TextAlign.End, maxLines = 1)
            if (!narrow) FinChange(FinLogic.change(q.chg), Modifier.width(FinDim.numCol).alpha(a), lowerBetter = q.lowerBetter)
            FinChange(if (FinLogic.missing(q.pct)) FinLogic.cell(q.pct) else FinLogic.change(q.pct) + "%", Modifier.width(FinDim.numCol).alpha(a), lowerBetter = q.lowerBetter)
        }
        if (divider) Box(Modifier.padding(start = hPad).fillMaxWidth().height(1.dp).background(p.hairline))
    }
}

/** Three skeleton rows of a monitor list (88x12, 56x20, 72x12, 64x12 blocks). */
@Composable
fun FinMonitorSkeleton(rows: Int = 3) {
    Column {
        repeat(rows) {
            Row(Modifier.fillMaxWidth().height(FinDim.rowCompact).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                FinSkeleton(64.dp, 12.dp); Spacer(Modifier.width(24.dp)); FinSkeleton(56.dp, 20.dp); Spacer(Modifier.weight(1f))
                FinSkeleton(56.dp, 12.dp); Spacer(Modifier.width(12.dp)); FinSkeleton(48.dp, 12.dp)
            }
        }
    }
}

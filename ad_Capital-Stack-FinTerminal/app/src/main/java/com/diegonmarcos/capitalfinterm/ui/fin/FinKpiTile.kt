package com.diegonmarcos.capitalfinterm.ui.fin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.diegonmarcos.capitalfinterm.FinFormat
import com.diegonmarcos.capitalfinterm.FinLogic

/** One instrument as every quote component draws it: symbol, optional name, last, change, change %, date and history. */
data class FinQuote(
    val symbol: String,
    val name: String = "",
    val last: String,
    val chg: String,
    val pct: String,
    val date: String,
    val history: List<Double> = emptyList(),
    val lowerBetter: Boolean = false,
) {
    /** The direction of the move: the percent when given, else the absolute change. */
    val dir: FinFormat.Dir get() = if (!FinLogic.missing(pct)) FinFormat.dirOf(pct) else FinFormat.dirOf(chg)
    val pctValue: Double? get() = FinFormat.parseNum(pct)
}

/**
 * KPI tile (spec 2.8, tag fin:kpi:<id>): 72dp, 8dp radius, surfaceRaised, no outline. Label + sparkline, value + signed change on
 * one baseline, as-of date. No change: the slot stays empty (never "-"). Stale: value at 70 % and an amber dot before the date.
 */
@Composable
fun FinKpiTile(q: FinQuote?, label: String, unit: String, stale: Boolean, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val p = LocalFinPalette.current
    val shape = RoundedCornerShape(FinDim.tileRadius)
    val base = modifier.height(FinDim.kpi).clip(shape).background(p.surfaceRaised)
    if (q == null) {
        Column(base.padding(10.dp).testTag("fin:kpi:loading"), verticalArrangement = Arrangement.SpaceBetween) {
            FinSkeleton(56.dp, 10.dp); FinSkeleton(72.dp, 18.dp); FinSkeleton(48.dp, 10.dp)
        }
        return
    }
    Column(
        (if (onClick != null) base.clickable(onClick = onClick) else base).padding(horizontal = 10.dp, vertical = 8.dp).testTag("fin:kpi:${q.symbol}"),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label.uppercase(), Modifier.weight(1f), color = p.text2, style = FinType.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (q.history.size >= 2) FinSparkline(q.history, Modifier.size(40.dp, 16.dp))
        }
        Row(verticalAlignment = Alignment.Bottom) {
            val missing = FinLogic.missing(q.last)
            val ink = finFlashInk(q.last, q.dir, if (missing) p.text3 else p.text)
            Text(FinLogic.cell(q.last), Modifier.weight(1f).alpha(if (stale) 0.7f else 1f), color = ink, style = FinType.kpi, maxLines = 1)
            val change = if (unit == "bp" && !FinLogic.missing(q.chg)) FinLogic.change(q.chg) + " bp"
                else if (!FinLogic.missing(q.pct)) FinLogic.change(q.pct) + "%" else if (!FinLogic.missing(q.chg)) FinLogic.change(q.chg) else ""
            if (change.isNotEmpty()) FinChange(change, Modifier.padding(bottom = 2.dp), lowerBetter = q.lowerBetter)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (stale) { FinDot(p.amber); Spacer(Modifier.width(4.dp)) }
            Text(if (FinLogic.missing(q.last)) "no data" else q.date.take(16).replace('T', ' '), color = p.text3, style = FinType.caption, maxLines = 1)
        }
    }
}

/** A row of KPI tiles, 2 per row on a phone and 3 from 480dp (spec 2.8). */
@Composable
fun FinKpiGrid(tiles: List<@Composable (Modifier) -> Unit>, perRow: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(FinDim.gap)) {
        tiles.chunked(perRow).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(FinDim.gap)) {
                row.forEach { t -> t(Modifier.weight(1f)) }
                repeat(perRow - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

/** A loading strip of [n] KPI skeleton tiles. */
@Composable
fun FinKpiSkeleton(n: Int, perRow: Int) {
    FinKpiGrid(List<@Composable (Modifier) -> Unit>(n) { { m -> FinKpiTile(null, "", "", false, m.fillMaxWidth()) } }, perRow)
}

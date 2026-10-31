package com.diegonmarcos.capitalfinterm.ui.fin

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.capitalfinterm.FinFormat
import com.diegonmarcos.capitalfinterm.FinLogic
import com.diegonmarcos.capitalfinterm.FinLogic.Col
import java.util.Locale

/**
 * Data table (spec 2.3, tag fin:table:<panel>): a 32dp surfaceRaised header in label type, sortable by tapping (numbers descending
 * first, text ascending, the third tap restores the source order); 36dp zebra rows; typed columns (text left, numbers right in
 * tabular mono, changes signed and coloured); when the columns do not fit, the body scrolls sideways with the FIRST column pinned
 * and a 16dp fade at the right edge. Eight rows, then "Show all N" expands the card in place. A changed cell flashes.
 */
@Composable
fun FinTable(
    panelId: String,
    columns: List<String>,
    rows: List<List<String>>,
    links: List<String>,
    declared: List<String>,
    width: Dp,
    limit: Int = 8,
    details: List<String> = emptyList(),
    onOpen: (String) -> Unit,
) {
    val p = LocalFinPalette.current
    val types = remember(columns, rows, declared) { FinLogic.colTypes(declared, columns, rows) }
    // a HEAT column's scale: its largest absolute value (FinLogic.heat clamps value / scale to -1..1)
    val heatMax = remember(rows, types) { types.indices.map { i -> if (types[i] != Col.HEAT) 0.0 else rows.mapNotNull { FinFormat.parseNum(it.getOrNull(i))?.let { v -> kotlin.math.abs(v) } }.maxOrNull() ?: 0.0 } }
    val n = types.size
    if (n == 0) return
    var sortCol by rememberSaveable(panelId) { mutableIntStateOf(-1) }
    var sortStep by rememberSaveable(panelId) { mutableIntStateOf(0) }
    var expanded by rememberSaveable(panelId) { mutableIntStateOf(0) }
    val order = remember(rows, sortCol, sortStep) { FinLogic.sortOrder(rows, sortCol, types.getOrElse(sortCol) { Col.TEXT }, sortStep) }
    val shown = FinLogic.visibleRows(rows.size, expanded == 1, limit)
    // Content-sized columns (FinLogic.layout): the pinned name fits its one-line name, every number column its longest cell.
    val lay = remember(columns, rows, width) { FinLogic.layout(columns, rows, width.value) }
    val ws = lay.widths.map { it.dp }
    val firstW = ws.getOrElse(0) { 96.dp }
    val fits = lay.fits
    val scroll = rememberScrollState()
    var detail by remember(panelId) { mutableStateOf<Int?>(null) }

    @Composable
    fun headerCell(i: Int, w: Dp) {
        val t = types[i]
        val right = t != Col.TEXT && t != Col.LINK
        Row(
            Modifier.width(w).fillMaxHeight().clickable {
                if (sortCol != i) { sortCol = i; sortStep = 1 } else sortStep = (sortStep + 1) % 3
            }.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (right) androidx.compose.foundation.layout.Arrangement.End else androidx.compose.foundation.layout.Arrangement.Start,
        ) {
            Text(columns.getOrElse(i) { "" }.uppercase(Locale.ROOT), color = p.text2, style = FinType.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false))
            if (sortCol == i && sortStep != 0) {
                val numeric = t != Col.TEXT && t != Col.LINK && t != Col.DATE
                val down = (sortStep == 1) == numeric
                Icon(if (down) Icons.Filled.ArrowDownward else Icons.Filled.ArrowUpward, null, Modifier.padding(start = 2.dp).size(12.dp), tint = p.accent)
            }
        }
    }

    @Composable
    fun bodyCell(r: Int, i: Int, w: Dp) {
        val raw = rows[r].getOrNull(i)
        val t = types[i]
        val link = links.getOrNull(r).orEmpty()
        val mod = Modifier.width(w).padding(horizontal = 8.dp)
        when {
            i == 0 && link.isNotEmpty() -> Text(FinLogic.cell(raw), mod, color = p.accent, style = FinType.body.copy(fontSize = 12.sp), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            t == Col.TEXT || t == Col.LINK -> Text(FinLogic.cell(raw), mod, color = if (FinLogic.missing(raw)) p.text3 else p.text,
                style = FinType.body.copy(fontSize = 12.sp), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            t == Col.DATE -> Text(FinLogic.cell(raw), mod, color = p.text2, style = FinType.caption, textAlign = TextAlign.End, maxLines = 1)
            t == Col.HEAT -> {
                val s = FinLogic.cell(raw)
                val h = FinLogic.heat(FinFormat.parseNum(s), heatMax[i])
                val fill = lerp(p.surfaceRaised, if (h > 0) p.down else p.up, FinLogic.HEAT_MAX_BLEND * kotlin.math.abs(h))
                Box(mod.background(fill)) {
                    Text(s, Modifier.fillMaxWidth(), color = if (FinLogic.missing(raw)) p.text3 else p.text, style = FinType.num, textAlign = TextAlign.End, maxLines = 1, softWrap = false)
                }
            }
            t == Col.CHG || t == Col.PCT -> {
                val s = FinLogic.change(raw)
                Box(mod.finFlash(s, FinFormat.dirOf(s))) { FinChange(s, Modifier.fillMaxWidth()) }
            }
            else -> {
                val s = FinLogic.cell(raw)
                Box(mod.finFlash(s, FinFormat.Dir.FLAT)) {
                    Text(s, Modifier.fillMaxWidth(), color = if (FinLogic.missing(raw)) p.text3 else p.text, style = FinType.num, textAlign = TextAlign.End, maxLines = 1, softWrap = false)
                }
            }
        }
    }

    Column(Modifier.fillMaxWidth().animateContentSize(tween(200)).testTag("fin:table:$panelId")) {
        Box {
            Row {
                // The pinned first column.
                Column(Modifier.width(firstW)) {
                    Row(Modifier.height(FinDim.tableHeader).background(p.surfaceRaised)) { headerCell(0, firstW) }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(p.hairline))
                    for (k in 0 until shown) {
                        val r = order[k]
                        Row(Modifier.heightIn(min = FinDim.tableRow).background(if (k % 2 == 1) p.surfaceRaised.copy(alpha = 0.5f) else Color.Transparent)
                            .let { m -> links.getOrNull(r).orEmpty().let { l -> if (l.isNotEmpty()) m.clickable { onOpen(l) } else if (details.isNotEmpty()) m.clickable { detail = r } else m } }
                            .height(FinDim.tableRow), verticalAlignment = Alignment.CenterVertically) { bodyCell(r, 0, firstW) }
                    }
                }
                Column(Modifier.horizontalScroll(scroll, enabled = !fits)) {
                    Row(Modifier.height(FinDim.tableHeader).background(p.surfaceRaised)) { for (i in 1 until n) headerCell(i, ws[i]) }
                    Box(Modifier.width(ws.drop(1).fold(0.dp) { a, b -> a + b }).height(1.dp).background(p.hairline))
                    for (k in 0 until shown) {
                        val r = order[k]
                        Row(Modifier.height(FinDim.tableRow).background(if (k % 2 == 1) p.surfaceRaised.copy(alpha = 0.5f) else Color.Transparent),
                            verticalAlignment = Alignment.CenterVertically) { for (i in 1 until n) bodyCell(r, i, ws[i]) }
                    }
                }
            }
            // the scroll hint: a 32dp fade and a chevron while more columns lie to the right
            if (!fits && scroll.value < scroll.maxValue) {
                Box(Modifier.align(Alignment.CenterEnd).width(32.dp).height(FinDim.tableHeader + FinDim.tableRow * shown).testTag("fin:tablefade:$panelId")
                    .background(Brush.horizontalGradient(listOf(Color.Transparent, p.surface))), contentAlignment = Alignment.TopEnd) {
                    Text("\u203A", Modifier.height(FinDim.tableHeader).padding(end = 4.dp), color = p.accent, style = FinType.body.copy(fontSize = 18.sp))
                }
            }
        }
        detail?.let { r ->
            // the full-name detail view: the declared full name, then every column of the row
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { detail = null },
                confirmButton = { androidx.compose.material3.TextButton(onClick = { detail = null }) { Text("Close") } },
                title = { Text(details.getOrNull(r).orEmpty().ifEmpty { FinLogic.cell(rows[r].getOrNull(0)) }, style = FinType.body) },
                text = {
                    Column(Modifier.testTag("fin:rowdetail:$panelId")) {
                        for (i in 1 until n) Row(Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(columns.getOrElse(i) { "" }, Modifier.weight(1f), color = p.text2, style = FinType.label)
                            Text(FinLogic.cell(rows[r].getOrNull(i)), color = p.text, style = FinType.num)
                        }
                    }
                },
            )
        }
        if (rows.size > limit) {
            Box(Modifier.fillMaxWidth().height(FinDim.tableRow).clickable { expanded = 1 - expanded }.padding(horizontal = 8.dp).testTag("fin:showall:$panelId"),
                contentAlignment = Alignment.CenterStart) {
                Text(if (expanded == 1) "Show less" else "Show all ${rows.size}", color = p.accent, style = FinType.body)
            }
        }
    }
}

/** Header + 5 skeleton rows. */
@Composable
fun FinTableSkeleton() {
    val p = LocalFinPalette.current
    Column {
        Box(Modifier.fillMaxWidth().height(FinDim.tableHeader).background(p.surfaceRaised))
        repeat(5) {
            Row(Modifier.fillMaxWidth().height(FinDim.tableRow).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                FinSkeleton(88.dp, 12.dp); Box(Modifier.weight(1f)); FinSkeleton(56.dp, 12.dp); Box(Modifier.width(16.dp)); FinSkeleton(48.dp, 12.dp)
            }
        }
    }
}

/** News rows (spec 3.4): 64dp, a 2-line headline, then the source/date caption; a 20dp monogram; tap opens the article. */
@Composable
fun FinNewsList(panelId: String, rows: List<List<String>>, links: List<String>, limit: Int, onOpen: (String) -> Unit) {
    val p = LocalFinPalette.current
    var expanded by rememberSaveable(panelId) { mutableIntStateOf(0) }
    val shown = FinLogic.visibleRows(rows.size, expanded == 1, limit)
    Column(Modifier.fillMaxWidth().animateContentSize(tween(200)).testTag("fin:news:$panelId")) {
        rows.take(shown).forEachIndexed { i, r ->
            val link = links.getOrNull(i).orEmpty()
            val rest = r.drop(1).filterNot { FinLogic.missing(it) }
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).let { if (link.isNotEmpty()) it.clickable { onOpen(link) } else it }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.Top) {
                val mono = (rest.firstOrNull { it.any(Char::isLetter) } ?: r.firstOrNull().orEmpty()).take(2).uppercase(Locale.ROOT)
                Box(Modifier.size(20.dp).background(p.surfaceRaised, androidx.compose.foundation.shape.RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
                    Text(mono, color = p.text2, style = FinType.caption.copy(fontSize = 9.sp))
                }
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(FinLogic.cell(r.firstOrNull()), color = p.text, style = FinType.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(rest.joinToString(" · "), color = p.text2, style = FinType.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (i < shown - 1) Box(Modifier.padding(start = 30.dp).fillMaxWidth().height(1.dp).background(p.hairline))
        }
        if (rows.size > limit) {
            Box(Modifier.fillMaxWidth().height(FinDim.tableRow).clickable { expanded = 1 - expanded }, contentAlignment = Alignment.CenterStart) {
                Text(if (expanded == 1) "Show less" else "Show all ${rows.size}", color = p.accent, style = FinType.body)
            }
        }
    }
}

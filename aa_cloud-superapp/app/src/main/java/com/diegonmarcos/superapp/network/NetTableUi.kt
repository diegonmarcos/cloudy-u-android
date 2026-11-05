package com.diegonmarcos.superapp.network

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.uikit.KitStoreCaption
import com.diegonmarcos.superapp.uikit.KitStoreRow

/**
 * #1523 The network dropdown's table, drawn in the ui-kit's dense Store row style (KitStoreRow spacing and caption size, no
 * text-size literal): a fixed label column, then either one value (key-value group) or one right-aligned monospace cell per
 * column (grid group, label over unit in the header), so units and decimals line up. One line per row, never wrapped.
 */
private val LABEL_W = KitStoreRow.step(92)
private val INK = Color.White
private val DIM = Color(0xAAFFFFFF)
private val FAINT = Color(0x77FFFFFF)
private val ACCENT = Color(0xFF7FB8FF)

@Composable
private fun Line(tag: String, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = KitStoreRow.step(1)).testTag(tag), content = content)
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Cell(v: NetValue, grid: Boolean, onFix: () -> Unit) {
    val fix = v is NetValue.NA && v.fix
    KitStoreCaption(
        if (grid) v.cell() else v.text(),
        Modifier.weight(1f).let { if (fix && !grid) it.clickable(onClick = onFix) else it },
        color = if (fix) ACCENT else if (v is NetValue.V) INK else FAINT, mono = true, align = if (grid) TextAlign.End else TextAlign.Start,
    )
}

/**
 * The declared groups, in order. [testing] / [onSpeedTest] draw the Speed test action under the group with id `test`; [onFix]
 * opens Perms for a "needs ... Fix in Perms ›" cell.
 */
@Composable
internal fun NetTableView(groups: List<NetGroup>, testing: Boolean, onSpeedTest: () -> Unit, onFix: () -> Unit) {
    for (g in groups) Column(Modifier.fillMaxWidth().padding(top = KitStoreRow.padV).testTag("netpop:group:${g.id}")) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(KitStoreRow.RULE))
        Box(Modifier.padding(top = KitStoreRow.tight)) { PopTitle(g.title) }
        if (g.columns.isNotEmpty()) {
            for (line in 0..1) Line("netpop:head:${g.id}:$line") {
                Box(Modifier.width(LABEL_W))
                for (c in g.columns) KitStoreCaption(if (line == 0) c.label else c.unit, Modifier.weight(1f), color = DIM, mono = true, align = TextAlign.End)
            }
        }
        for (r in g.rows) Line("netpop:row:${r.id}") {
            KitStoreCaption(r.label, Modifier.width(LABEL_W), color = DIM, mono = true)
            for (c in r.cells) Cell(c, g.columns.isNotEmpty(), onFix)
        }
        if (g.columns.isNotEmpty()) for (n in g.notes()) KitStoreCaption(n, color = FAINT, mono = true)
        if (g.id == "test") KitStoreCaption(
            if (testing) "Testing..." else "Speed test ›", color = ACCENT, mono = true,
            modifier = Modifier.clickable(enabled = !testing, onClick = onSpeedTest).padding(vertical = KitStoreRow.tight).testTag("netpop:link:Speed test"),
        )
    }
}

package com.diegonmarcos.superapp.fleetconfigs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.diegonmarcos.superapp.themebg.ThemeColors

/**
 * #1415 the public building blocks of Configs v2 for an app's OWN Configs pages (Cloud Dialer's SIM, Status, Usage):
 * the same card, row and table General and About draw, sized so nothing runs off the screen. Every text cell has a
 * weight and wraps (a label never wider than [LABEL_WEIGHT] of the row; a long value wraps under itself), so a row is
 * never wider than the card whatever the device returns.
 */
public object ConfigsRowSpec {
    public const val LABEL_WEIGHT: Float = 0.42f
    public const val VALUE_WEIGHT: Float = 0.58f
}

/** A Configs card: title with an icon, then [body]. */
@Composable
public fun ConfigsCard(
    title: String, c: ThemeColors, modifier: Modifier = Modifier, icon: ImageVector = Icons.Filled.Tune,
    body: @Composable ColumnScope.() -> Unit,
) {
    SectionCard(title, icon, c, modifier, body)
}

/** One label / value row. Both cells wrap; [onClick] makes the row a button (e.g. tap to reveal a masked id). */
@Composable
public fun ConfigsRow(label: String, value: String, c: ThemeColors, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().let { if (onClick != null) it.clickable(role = Role.Button, onClick = onClick) else it }.padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(label, Modifier.weight(ConfigsRowSpec.LABEL_WEIGHT), color = c.inkDim, fontSize = 13.sp)
        Text(
            value.ifEmpty { "–" }, Modifier.weight(ConfigsRowSpec.VALUE_WEIGHT), color = c.ink, fontSize = 13.sp,
            fontWeight = FontWeight.Medium, textAlign = TextAlign.End, softWrap = true,
        )
    }
}

/** A small heading inside a card (a period of the Usage table, a group of Status rows). */
@Composable
public fun ConfigsSubheading(text: String, c: ThemeColors) {
    Text(text, Modifier.padding(top = 6.dp), color = c.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
}

/**
 * A table: a header row, then [rows]. Every column has an equal weight, each cell is one line with an ellipsis, numbers
 * align to the end; a table fits its card's width on any screen (the label column wraps instead).
 */
@Composable
public fun ConfigsTable(header: List<String>, rows: List<List<String>>, c: ThemeColors, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(10.dp)
    Column(modifier.fillMaxWidth().clip(shape).border(1.dp, c.islandBorder, shape)) {
        TableLine(header, c, head = true)
        rows.forEach { TableLine(it, c, head = false) }
    }
}

@Composable
private fun TableLine(cells: List<String>, c: ThemeColors, head: Boolean) {
    Row(
        Modifier.fillMaxWidth().let { if (head) it.background(c.tabStrip) else it }.padding(horizontal = 8.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        cells.forEachIndexed { i, cell ->
            Text(
                cell, Modifier.weight(if (i == 0) 1.4f else 1f),
                color = if (head) c.inkDim else c.ink, fontSize = 12.sp,
                fontWeight = if (head) FontWeight.SemiBold else FontWeight.Normal,
                textAlign = if (i == 0) TextAlign.Start else TextAlign.End,
                maxLines = if (i == 0) 2 else 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

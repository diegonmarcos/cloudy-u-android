@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.diegonmarcos.superapp.adbdebug

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The page's one button: a flat bordered box with NO minimum height (Material's Button reserves a 40 dp
 * row and a 48 dp touch target, which is what made the old page sparse). [primary] fills it.
 */
@Composable
internal fun DenseButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, primary: Boolean = false) {
    val fg = LocalContentColor.current
    val shape = RoundedCornerShape(4.dp)
    val filled = primary && enabled
    Box(
        modifier.clip(shape)
            .background(if (filled) Color(0xFF2B6CB0) else Color.Transparent)
            .border(1.dp, fg.copy(alpha = if (enabled) 0.45f else 0.18f), shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 12.sp, maxLines = 1, color = if (filled) Color.White else fg.copy(alpha = if (enabled) 1f else 0.4f))
    }
}

/**
 * The "Privileged channel" mode selector, drawn inside the ADB Shell page. Local server first (the
 * default where the app owns a server), then the owner's order.
 */
@Composable
fun ChannelModeBar(onChanged: (ChannelMode) -> Unit = {}) {
    val ctx = LocalContext.current
    var cur by remember { mutableStateOf(ChannelModePrefs.current(ctx)) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            // Local server is the default profile of every app (#1709); an app with no server of its own reaches the owner's.
            val modes = listOf(ChannelMode.LOCAL_SERVER, ChannelMode.EMBEDDED_ONLY, ChannelMode.SHIZUKU, ChannelMode.AUTO)
            for (m in modes) {
                DenseButton(m.title(), primary = m == cur, onClick = { cur = m; ChannelModePrefs.set(ctx, m); onChanged(m) })
            }
        }
        Text(cur.hint(), fontSize = 11.sp, modifier = Modifier.padding(top = 1.dp))
    }
}

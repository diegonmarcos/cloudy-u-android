package com.diegonmarcos.capitalfinterm.ui.fin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Watchlist row (spec 2.9, tag fin:watch:<symbol>): a tall monitor row in a swipe box. Swipe to the start (left) removes it
 * (the caller shows "removed · Undo"); swipe to the end (right) opens the alert rule for the symbol. Its alert rules follow as
 * 32dp sub-rows (bell + rule); a tap on a rule removes it. A long press opens the row menu (move up/down, alert, remove).
 */
@Composable
fun FinWatchRow(q: FinQuote, rules: List<String>, stale: Boolean, width: Dp, onOpen: () -> Unit, onRemove: () -> Unit, onAlert: () -> Unit, onRemoveRule: (String) -> Unit, onLongClick: (() -> Unit)? = null) {
    val p = LocalFinPalette.current
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { v ->
            when (v) {
                SwipeToDismissBoxValue.EndToStart -> { onRemove(); true }
                SwipeToDismissBoxValue.StartToEnd -> { onAlert(); false }
                SwipeToDismissBoxValue.Settled -> true
            }
        },
        positionalThreshold = { it * 0.4f },
    )
    Column(Modifier.testTag("fin:watch:${q.symbol}")) {
        SwipeToDismissBox(
            state = state,
            backgroundContent = {
                val dir = state.dismissDirection
                val alert = dir == SwipeToDismissBoxValue.StartToEnd
                Box(Modifier.fillMaxSize().background(if (alert) p.accent else p.down).padding(horizontal = 20.dp),
                    contentAlignment = if (alert) Alignment.CenterStart else Alignment.CenterEnd) {
                    Icon(if (alert) Icons.Outlined.NotificationsActive else Icons.Outlined.Delete, null, Modifier.size(24.dp), tint = p.canvas)
                }
            },
        ) {
            Box(Modifier.background(p.surface)) { FinMonitorRow(q, stale, width, tall = true, divider = rules.isEmpty(), onClick = onOpen, onLongClick = onLongClick) }
        }
        rules.forEach { r ->
            Row(Modifier.fillMaxWidth().height(32.dp).clickable { onRemoveRule(r) }.padding(start = 24.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.NotificationsActive, null, Modifier.size(14.dp), tint = p.accent)
                Spacer(Modifier.width(6.dp))
                Text(r, Modifier.weight(1f), color = p.text2, style = FinType.numSmall)
                Text("×", color = p.text3, style = FinType.body)
            }
        }
    }
}

/** Empty watchlist (spec 2.9): 120dp, star glyph, "No symbols yet", an "Add symbol" chip. */
@Composable
fun FinWatchEmpty(onAdd: () -> Unit) {
    val p = LocalFinPalette.current
    Column(Modifier.fillMaxWidth().height(120.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Outlined.StarOutline, null, Modifier.size(32.dp), tint = p.text3)
        Text("No symbols yet", color = p.text2, style = FinType.body, modifier = Modifier.padding(vertical = 6.dp))
        FinChip("Add symbol", true, onClick = onAdd)
    }
}

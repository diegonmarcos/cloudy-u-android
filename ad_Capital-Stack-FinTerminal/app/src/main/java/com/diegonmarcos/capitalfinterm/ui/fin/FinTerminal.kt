package com.diegonmarcos.capitalfinterm.ui.fin

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.diegonmarcos.capitalfinterm.FinFormat
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * #1722 the terminal-style chrome and panels (the design reference: black canvas, an amber command line with GO, red function-menu buttons, a market
 * monitor of tiles that flash on a change, panels with an amber function code in a grey header strip, tabs whose selected one is amber, a subhead of
 * label / value pairs, a status line). Every colour is a role of [FinPalette] (res/values/colors.xml), every size a step of [FinType] / [FinDim].
 */

/** The test tags of the terminal chrome. */
object FinTags {
    const val COMMAND = "fin:command"
    const val COMMAND_INPUT = "fin:command:input"
    const val COMMAND_GO = "fin:command:go"
    const val COMMAND_MSG = "fin:command:msg"
    const val MONITOR = "fin:monitor"
    const val STATUS = "fin:status"
    const val FUNCTIONS = "fin:functions"
    fun menu(id: String): String = "fin:menu:$id"
    fun tab(group: String, id: String): String = "fin:tab:$group:$id"
    fun panel(code: String): String = "fin:panel:$code"
    fun function(fn: String): String = "fin:function:$fn"
}

/** The header strip of a panel: the amber function code in a black box, the white title, then meta text or tabs at the right. */
@Composable
fun FinPanelHead(code: String, title: String, modifier: Modifier = Modifier, meta: String = "", trailing: (@Composable RowScope.() -> Unit)? = null) {
    val p = LocalFinPalette.current
    Row(
        modifier.fillMaxWidth().height(FinDim.panelHead).background(Brush.verticalGradient(listOf(p.headTop, p.head))),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (code.isNotBlank()) {
            Box(Modifier.fillMaxHeight().background(p.canvas).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                Text(code.uppercase(Locale.ROOT), color = p.accent, style = FinType.code, maxLines = 1)
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(p.hairline))
        }
        Text(title, Modifier.weight(1f).padding(horizontal = 8.dp), color = Color.White, style = FinType.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (meta.isNotBlank()) Text(meta, Modifier.padding(end = 8.dp), color = p.text2, style = FinType.caption, maxLines = 1)
        trailing?.invoke(this)
    }
}

/** A terminal panel: a black box inside a 1dp rule, its header strip, an optional subhead, then the body. */
@Composable
fun FinPanel(
    code: String, title: String, modifier: Modifier = Modifier, meta: String = "", subhead: List<FinStat> = emptyList(),
    trailing: (@Composable RowScope.() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit,
) {
    val p = LocalFinPalette.current
    Column(modifier.fillMaxWidth().background(p.canvas).border(1.dp, p.hairline).padding(1.dp).testTag(FinTags.panel(code.ifBlank { title }))) {
        FinPanelHead(code, title, meta = meta, trailing = trailing)
        if (subhead.isNotEmpty()) FinSubhead(subhead)
        content()
    }
}

/** One label / value pair of a subhead; [dir] colours the value by direction (null = the text colour). */
data class FinStat(val label: String, val value: String, val dir: FinFormat.Dir? = null)

/** The subhead of a panel: muted labels with their bold values, one scrollable line under the header. */
@Composable
fun FinSubhead(items: List<FinStat>, modifier: Modifier = Modifier) {
    val p = LocalFinPalette.current
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            items.forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.label, color = p.text2, style = FinType.caption, maxLines = 1)
                    Spacer(Modifier.width(4.dp))
                    Text(s.value, color = if (s.dir == null) p.text else p.dir(s.dir), style = FinType.label, maxLines = 1)
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.head2))
    }
}

/** A strip of tabs (periods, modes): the selected one is amber with black ink, the others muted; each a square button of the strip's height. */
@Composable
fun FinTabs(group: String, options: List<Pair<String, String>>, selected: String, modifier: Modifier = Modifier, height: Dp = FinDim.panelHead, onSelect: (String) -> Unit) {
    val p = LocalFinPalette.current
    Row(modifier.height(height)) {
        options.forEach { (id, label) ->
            val on = id == selected
            Box(Modifier.width(1.dp).fillMaxHeight().background(p.head2))
            Box(
                Modifier.fillMaxHeight().background(if (on) p.accent else Color.Transparent).clickable { onSelect(id) }.padding(horizontal = 10.dp).testTag(FinTags.tab(group, id)),
                contentAlignment = Alignment.Center,
            ) { Text(label.uppercase(Locale.ROOT), color = if (on) p.canvas else p.text2, style = FinType.label, maxLines = 1) }
        }
    }
}

/** One red function-menu button: its number in the lighter ink, then its word. */
@Composable
fun FinMenuButton(id: String, number: String, label: String, onClick: () -> Unit) {
    val p = LocalFinPalette.current
    Row(Modifier.height(22.dp).background(p.menu).clickable(onClick = onClick).padding(horizontal = 8.dp).testTag(FinTags.menu(id)), verticalAlignment = Alignment.CenterVertically) {
        Text(number, color = p.menuInk, style = FinType.label, maxLines = 1)
        Spacer(Modifier.width(4.dp))
        Text(label, color = Color.White, style = FinType.label, maxLines = 1)
    }
}

/** The clock of the command line: the device's time to the second, and its zone. */
@Composable
private fun FinClock() {
    val p = LocalFinPalette.current
    val fmt = remember { DateTimeFormatter.ofPattern("HH:mm:ss") }
    var now by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) { while (true) { now = LocalTime.now(); delay(1000) } }
    Column(horizontalAlignment = Alignment.End) {
        Text(now.format(fmt), color = p.text, style = FinType.label, maxLines = 1)
        Text(runCatching { ZoneId.systemDefault().id.substringAfterLast('/') }.getOrDefault(""), color = p.text2, style = FinType.caption.copy(lineHeight = FinType.label.lineHeight), maxLines = 1)
    }
}

/**
 * The command line: an amber field for a function mnemonic with its GO button (the keyboard's Go key runs it too), the red function-menu buttons, and
 * the clock. A message (an unknown mnemonic) is shown in the down colour under the row.
 */
@Composable
fun FinCommandBar(value: String, placeholder: String, message: String, menu: List<Triple<String, String, String>>, onValue: (String) -> Unit, onGo: () -> Unit, onMenu: (String) -> Unit) {
    val p = LocalFinPalette.current
    Column(Modifier.fillMaxWidth().background(p.canvas).testTag(FinTags.COMMAND)) {
        Row(Modifier.fillMaxWidth().height(FinDim.command).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.height(26.dp).border(1.dp, p.accent)) {
                Box(Modifier.width(104.dp).fillMaxHeight().background(p.accent).padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(placeholder, color = p.canvas.copy(alpha = 0.55f), style = FinType.command, maxLines = 1, overflow = TextOverflow.Clip)
                    BasicTextField(
                        value = value,
                        onValueChange = { onValue(it.uppercase(Locale.ROOT).take(24)) },
                        singleLine = true,
                        textStyle = FinType.command.copy(color = p.canvas),
                        cursorBrush = SolidColor(p.canvas),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { onGo() }),
                        modifier = Modifier.fillMaxWidth().testTag(FinTags.COMMAND_INPUT),
                    )
                }
                Box(Modifier.fillMaxHeight().background(p.canvas).clickable(onClick = onGo).padding(horizontal = 8.dp).testTag(FinTags.COMMAND_GO), contentAlignment = Alignment.Center) {
                    Text("<GO>", color = p.accent, style = FinType.code, maxLines = 1)
                }
            }
            Spacer(Modifier.width(8.dp))
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                menu.forEach { (id, number, label) -> FinMenuButton(id, number, label) { onMenu(id) } }
            }
            Spacer(Modifier.width(8.dp))
            FinClock()
        }
        if (message.isNotBlank()) {
            Text(message, Modifier.fillMaxWidth().padding(horizontal = 10.dp).padding(bottom = 3.dp).testTag(FinTags.COMMAND_MSG), color = p.down, style = FinType.caption, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.hairline))
    }
}

/** One tile of the market monitor: what it is, its last price and its change in % (null = the source has not answered). */
data class FinMonitorTile(val symbol: String, val label: String, val last: String, val pct: Double?)

/** The market monitor: a scrollable strip of tiles (amber name, price, signed change); a tile's background flashes up or down when its price changes. */
@Composable
fun FinMonitor(tiles: List<FinMonitorTile>, onOpen: (FinMonitorTile) -> Unit) {
    val p = LocalFinPalette.current
    Column(Modifier.fillMaxWidth().testTag(FinTags.MONITOR)) {
        Row(Modifier.fillMaxWidth().height(FinDim.monitor).horizontalScroll(rememberScrollState())) {
            tiles.forEach { t ->
                val pc = t.pct
                val change = pc?.let { String.format(Locale.ROOT, "%+.2f%%", it) } ?: FinFormat.DASH
                val dir = if (pc == null || pc == 0.0) FinFormat.Dir.FLAT else if (pc > 0.0) FinFormat.Dir.UP else FinFormat.Dir.DOWN
                Column(
                    Modifier.widthIn(min = 92.dp).fillMaxHeight().finFlash(t.last, dir).clickable { onOpen(t) }.padding(horizontal = 8.dp, vertical = 3.dp).testTag("fin:tile:" + t.symbol),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(t.label, color = p.accent, style = FinType.label.copy(lineHeight = FinType.numSmall.lineHeight), maxLines = 1)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t.last, color = p.text, style = FinType.numStrong, maxLines = 1)
                        Spacer(Modifier.width(6.dp))
                        Text(change, color = if (pc == null) p.text3 else p.dir(dir), style = FinType.numSmall, maxLines = 1)
                    }
                }
                Box(Modifier.width(1.dp).fillMaxHeight().background(p.head2))
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.hairline))
    }
}

/** The status line: a square dot in the up colour when live, then label / value pairs in the muted ink. */
@Composable
fun FinStatusLine(live: Boolean, liveWord: String, items: List<Pair<String, String>>) {
    val p = LocalFinPalette.current
    Column(Modifier.fillMaxWidth().background(p.canvas).testTag(FinTags.STATUS)) {
        Row(Modifier.fillMaxWidth().height(FinDim.status).horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).background(if (live) p.up else p.yellow))
                Spacer(Modifier.width(5.dp))
                Text(liveWord, color = p.text2, style = FinType.caption, maxLines = 1)
            }
            items.forEach { (k, v) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(k, color = p.text2, style = FinType.caption, maxLines = 1)
                    Spacer(Modifier.width(4.dp))
                    Text(v, color = p.text, style = FinType.caption, maxLines = 1)
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.hairline))
    }
}

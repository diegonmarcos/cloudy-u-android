package com.diegonmarcos.capitalfinterm.ui.fin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.diegonmarcos.capitalfinterm.FinSearchIndex

/**
 * Search overlay (spec 2.10, tag fin:search): an expanded bottom sheet over the fin scrim, the 48dp field focused at once. Results
 * grouped (Watchlist, Equities & indices, FX, Rates & yields, Crypto, Commodities, Economic events, Dashboards), 48dp rows with a
 * 32dp asset-class badge; prefix on symbol, substring on name, 30 at most. An empty field shows the recent searches as chips.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FinSearch(catalogue: List<FinSearchIndex.Item>, onDismiss: () -> Unit, remote: (suspend (String) -> List<FinSearchIndex.Item>)? = null, onPick: (FinSearchIndex.Item) -> Unit) {
    val p = LocalFinPalette.current
    val ctx = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var recent by remember { mutableStateOf(FinPrefs.recent(ctx)) }
    val focus = remember { FocusRequester() }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // remote symbol search: after a short pause the declared remote list adds its matches (the local catalogue ranks first)
    var extra by remember { mutableStateOf(emptyList<FinSearchIndex.Item>()) }
    LaunchedEffect(query) {
        extra = emptyList()
        if (remote != null && query.trim().length >= 2) { kotlinx.coroutines.delay(300); extra = runCatching { remote(query) }.getOrDefault(emptyList()) }
    }
    val results = remember(query, catalogue, extra) { FinSearchIndex.search(catalogue + extra, query) }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    fun pick(i: FinSearchIndex.Item) { FinPrefs.addRecent(ctx, i.symbol); onPick(i) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = p.surface, scrimColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f),
        modifier = Modifier.testTag("fin:search")) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f).padding(horizontal = 12.dp)) {
            Row(Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(12.dp)).background(p.surfaceRaised).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Search, null, Modifier.size(20.dp), tint = p.text2)
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text("Symbol, index, pair, country, event", color = p.text3, style = FinType.body)
                    BasicTextField(query, { query = it }, Modifier.fillMaxWidth().focusRequester(focus).testTag("fin:search:field"), singleLine = true,
                        textStyle = FinType.body.copy(color = p.text), cursorBrush = SolidColor(p.accent),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { results.firstOrNull()?.let { pick(it) } }))
                }
                if (query.isNotEmpty()) Icon(Icons.Outlined.Close, "clear", Modifier.size(20.dp).clickable { query = "" }, tint = p.text2)
            }
            Spacer(Modifier.height(8.dp))
            if (query.isBlank()) {
                if (recent.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        recent.forEach { s -> FinChip(s, false) { catalogue.firstOrNull { it.symbol == s }?.let { pick(it) } ?: run { query = s } } }
                        Text("Clear", Modifier.clickable { FinPrefs.clearRecent(ctx); recent = emptyList() }.padding(8.dp), color = p.accent, style = FinType.body)
                    }
                }
            } else if (results.isEmpty()) {
                Text("No match for '$query'", color = p.text2, style = FinType.body, modifier = Modifier.padding(vertical = 12.dp))
                Text("Open Config > Sources to add a symbol source", color = p.accent, style = FinType.caption)
            }
            LazyColumn(Modifier.fillMaxWidth()) {
                results.groupBy { it.group }.forEach { (group, list) ->
                    item(key = "h:$group") {
                        Box(Modifier.fillMaxWidth().height(28.dp), contentAlignment = Alignment.CenterStart) { Text(group.uppercase(), color = p.text2, style = FinType.label) }
                    }
                    items(list, key = { "r:" + it.group + it.symbol + it.target }) { i ->
                        Row(Modifier.fillMaxWidth().height(48.dp).clickable { pick(i) }.testTag("fin:search:${i.symbol}"), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(32.dp).clip(RoundedCornerShape(6.dp)).background(p.surfaceRaised), contentAlignment = Alignment.Center) {
                                Text(i.badge, color = p.text2, style = FinType.label)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(i.symbol, color = p.text, style = FinType.symbol, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (i.name.isNotBlank()) Text(i.name, color = p.text2, style = FinType.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (i.last.isNotBlank()) {
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(i.last, color = p.text, style = FinType.numSmall)
                                    if (i.pct.isNotBlank()) FinChange(i.pct, style = FinType.numSmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

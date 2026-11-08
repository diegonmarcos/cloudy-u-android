package com.diegonmarcos.superapp.ai

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The sources of a KG-Store item the page shows for one row: id, label, detail, how its switch works. */
private class KgRow(val item: AiKgItem, val on: Boolean, val enabled: Boolean, val toggle: () -> Unit)

/**
 * KG-Store as a full page, in the Model Catalogue's layout: a header with Close, a search box, a tab strip (Web Search, Store / Releases,
 * Google Workspace, Google Personal, Other internet MCPs; declared in ai-design.json::kg_store) and ONE scrolling list under it. Each row is
 * a source with its detail and a switch. A search with text looks across every tab. Close, Back and the system back return to the chat.
 */
@Composable
fun AiKgStorePage(chat: AiChatState, theme: AiTheme, design: AiDesign, modifier: Modifier = Modifier) {
    val c = theme.colors
    val t = theme.type
    val m = theme.metrics
    val s = theme.strings
    val done = { chat.page = ""; Unit }
    val tabs = design.kgStoreTabs
    var tab by remember { mutableStateOf(tabs.firstOrNull()?.id.orEmpty()) }
    var query by remember { mutableStateOf("") }
    BackHandler { done() }
    LaunchedEffect(Unit) {
        if (chat.mcpServers.isEmpty()) chat.mcpServers = withContext(Dispatchers.IO) { runCatching { chat.host().mcpServers() }.getOrDefault(emptyList()) }
    }
    val store = design.mcpBuckets.firstOrNull { it.control == "kg_store" }
    // The other internet MCPs: those the gateway lists in the KG-Store bucket, and none already on a named tab.
    val named = tabs.flatMap { it.items }.filter { it.kind == "mcp" }.map { it.id }.toSet()
    val listed = chat.mcpServers.filter { design.mcpBucket(it.name)?.id == store?.id && it.name !in named }.map { it.name }
    val others = (listed + store?.known.orEmpty().filter { it !in named && it !in listed }).map { AiKgItem(it, it, if (it in listed) "internet MCP" else s.notListed, "mcp") }
    val listedAll = chat.mcpServers.map { it.name }.toSet()
    fun rowsOf(tb: AiKgTab): List<KgRow> = (tb.items + if (tb.dynamic) others else emptyList()).map { i ->
        when (i.kind) {
            "web" -> KgRow(i, chat.web, true) { chat.toggleWeb() }
            "mcp" -> KgRow(i, i.id in chat.mcpOn, i.id in listedAll) { chat.toggleMcp(i.id) }
            else -> KgRow(i, i.id in chat.sourcesOn, true) { chat.toggleSource(i.id) }
        }
    }
    val q = query.trim()
    Column(modifier.fillMaxSize().testTag(AiTags.KGSTORE_PAGE)) {
        Header(theme, s.kgStoreTitle, AiTags.KGSTORE_CLOSE, { done() }) {
            val on = tabs.sumOf { tb -> rowsOf(tb).count { it.on } }
            Text("$on on", color = c.text2, style = t.style(t.small), modifier = Modifier.padding(horizontal = m.small))
        }
        val shape = RoundedCornerShape(m.cardRadius)
        BasicTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            textStyle = t.style(t.chatInput).copy(color = c.text), cursorBrush = SolidColor(c.accent),
            modifier = Modifier.fillMaxWidth().padding(horizontal = m.gutter).clip(shape).background(c.field).border(m.hairline, c.tileBorder, shape).testTag(AiTags.KGSTORE_SEARCH),
            decorationBox = { inner ->
                Box(Modifier.padding(horizontal = m.cardPad, vertical = m.gap)) {
                    if (query.isEmpty()) Text(s.searchKgStore, color = c.text2, style = t.style(t.chatInput), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    inner()
                }
            },
        )
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = m.gutter, vertical = m.tiny), horizontalArrangement = Arrangement.spacedBy(m.gap)) {
            tabs.forEach { tb ->
                Text(
                    tb.label, Modifier.clip(shape).background(if (tb.id == tab) c.field else Color.Transparent)
                        .clickable { tab = tb.id; query = "" }.padding(horizontal = m.cardPad, vertical = m.gap).testTag(AiTags.kgTab(tb.id)),
                    color = if (tb.id == tab) c.accent else c.text2, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = t.style(t.small, if (tb.id == tab) FontWeight.Bold else FontWeight.Medium),
                )
            }
        }
        val shown = if (q.isEmpty()) rowsOf(tabs.first { it.id == tab }) else tabs.flatMap { rowsOf(it) }.filter { r -> r.item.label.contains(q, true) || r.item.detail.contains(q, true) }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = m.gutter, vertical = m.gap)) {
            Text(
                (if (q.isEmpty()) tabs.first { it.id == tab }.label else q) + " · ${shown.size} · ${shown.count { it.on }} on",
                color = c.text2, style = t.style(t.label), modifier = Modifier.padding(bottom = m.small),
            )
            if (shown.isEmpty()) Text(s.noKgItems, color = c.text2, style = t.style(t.small))
            shown.forEach { r ->
                Row(Modifier.fillMaxWidth().clickable(enabled = r.enabled) { r.toggle() }.padding(vertical = m.small).testTag(AiTags.kgItem(r.item.id)), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.item.label, color = if (r.enabled) c.text else c.text2, style = t.style(t.chatInput, FontWeight.SemiBold))
                        if (r.item.detail.isNotEmpty()) Text(r.item.detail + if (!r.enabled) " · " + s.notListed else "", color = c.text2, style = t.style(t.small))
                    }
                    Switch(r.on, { r.toggle() }, enabled = r.enabled, colors = SwitchDefaults.colors(checkedTrackColor = c.accent, uncheckedTrackColor = c.field))
                }
            }
        }
    }
}

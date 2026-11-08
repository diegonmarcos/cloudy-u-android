package com.diegonmarcos.superapp.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow

/**
 * The hamburger's drawer: EVERY chat, searchable, with "+ New" on top and the chats organised in named groups (collapsible
 * headers, "+ New group", a per-chat menu to move it to a group or out of it). Groups and membership persist in [AiSessionGroups].
 * The narrow sessions rail beside the thread shows only the open chat's group; this lists them all.
 */
@Composable
internal fun AllChatsDrawer(s: ForumSessions, theme: AiTheme, strings: ForumStrings, metrics: ForumMetrics, icon: ForumIcon, onClose: () -> Unit) {
    val c = theme.colors
    val m = theme.metrics
    val groups = s.groups
    var query by remember { mutableStateOf("") }
    var naming by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    val q = query.trim()
    val shown = s.list.filter { q.isEmpty() || it.title.contains(q, ignoreCase = true) }
    fun open(sess: AiSession) { s.onOpen(sess); onClose() }

    @Composable
    fun Row1(sess: AiSession) {
        var menu by remember { mutableStateOf(false) }
        val label = sess.title.ifBlank { strings.untitled }
        Row(
            Modifier.fillMaxWidth().sizeIn(minHeight = metrics.touch).clickable { open(sess) }.testTag(ForumTags.allChat(sess.id)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(metrics.accentBar).fillMaxHeight().background(if (sess.id == s.currentId) c.accent else Color.Transparent))
            Text(
                label, color = if (sess.id == s.currentId) c.accent else c.text, style = theme.type.style(theme.type.small, FontWeight.Medium),
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = m.gap),
            )
            if (groups != null) Box {
                Box(
                    Modifier.sizeIn(minWidth = metrics.touch, minHeight = metrics.touch).clickable { menu = true }
                        .semantics { contentDescription = strings.sessionActions }.testTag(ForumTags.chatMenu(sess.id)),
                    contentAlignment = Alignment.Center,
                ) { icon("more", m.icon, c.text2, null) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    groups.groups.forEach { g ->
                        DropdownMenuItem(
                            text = { Text(strings.moveTo + " " + g.name, style = theme.type.style(theme.type.small), color = if (groups.groupOf(sess.id) == g.id) c.accent else c.text) },
                            onClick = { groups.assign(sess.id, g.id); menu = false }, modifier = Modifier.testTag(ForumTags.moveTo(sess.id, g.id)),
                        )
                    }
                    if (groups.groupOf(sess.id) != null) DropdownMenuItem(
                        text = { Text(strings.ungroup, style = theme.type.style(theme.type.small), color = c.text) },
                        onClick = { groups.assign(sess.id, null); menu = false }, modifier = Modifier.testTag(ForumTags.ungroup(sess.id)),
                    )
                }
            }
        }
    }

    Box(Modifier.fillMaxSize().testTag(ForumTags.ALLCHATS)) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = metrics.scrimAlpha)).clickable(onClick = onClose).testTag(ForumTags.ALLCHATS_SCRIM))
        Column(Modifier.width(metrics.drawerWidth).fillMaxHeight().background(c.chatBar).padding(m.small)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(strings.allChats, color = c.text, style = theme.type.style(theme.type.chat, FontWeight.SemiBold), modifier = Modifier.weight(1f).padding(horizontal = m.gap))
                Text(
                    "+ " + strings.newSession, color = c.accent, style = theme.type.style(theme.type.small, FontWeight.SemiBold),
                    modifier = Modifier.sizeIn(minHeight = metrics.touch).clickable { s.onNew(); onClose() }.padding(horizontal = m.gap, vertical = m.gap).testTag(ForumTags.ALLCHATS_NEW),
                )
            }
            val field = RoundedCornerShape(m.chatBarRadius)
            BasicTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                textStyle = TextStyle(color = c.text, fontSize = theme.type.chatInput), cursorBrush = SolidColor(c.accent),
                modifier = Modifier.fillMaxWidth().clip(field).background(c.field).border(m.hairline, c.tileBorder, field).testTag(ForumTags.ALLCHATS_SEARCH),
                decorationBox = { inner ->
                    Box(Modifier.padding(horizontal = m.bubblePadH, vertical = m.bubblePadV)) {
                        if (query.isEmpty()) Text(strings.searchChats, color = c.text2, style = TextStyle(fontSize = theme.type.chatInput))
                        inner()
                    }
                },
            )
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(m.tiny)) {
                if (groups != null) groups.groups.forEach { g ->
                    val members = shown.filter { groups.groupOf(it.id) == g.id }
                    if (q.isNotEmpty() && members.isEmpty()) return@forEach
                    Row(
                        Modifier.fillMaxWidth().sizeIn(minHeight = metrics.touch).clickable { groups.toggle(g.id) }.testTag(ForumTags.group(g.id)),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.testTag(ForumTags.groupToggle(g.id)).padding(horizontal = m.gap)) { Text(if (g.collapsed) "▸" else "▾", color = c.text2) }
                        Text(g.name, color = c.text2, style = theme.type.style(theme.type.label, FontWeight.Bold), modifier = Modifier.weight(1f))
                        Text(members.size.toString(), color = c.text2, style = theme.type.style(theme.type.label), modifier = Modifier.padding(end = m.gap))
                    }
                    if (!g.collapsed || q.isNotEmpty()) members.forEach { Row1(it) }
                }
                if (groups != null) Text(strings.ungrouped, color = c.text2, style = theme.type.style(theme.type.label, FontWeight.Bold), modifier = Modifier.padding(horizontal = m.gap, vertical = m.small))
                shown.filter { groups == null || groups.groupOf(it.id) == null }.forEach { Row1(it) }
            }
            if (groups != null) {
                if (naming) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.small)) {
                    BasicTextField(
                        value = name, onValueChange = { name = it }, singleLine = true,
                        textStyle = TextStyle(color = c.text, fontSize = theme.type.chatInput), cursorBrush = SolidColor(c.accent),
                        modifier = Modifier.weight(1f).clip(field).background(c.field).border(m.hairline, c.tileBorder, field).padding(horizontal = m.bubblePadH, vertical = m.bubblePadV).testTag(ForumTags.GROUP_FIELD)
                            .semantics { contentDescription = strings.groupName },
                    )
                    Text(
                        strings.addGroup, color = c.accent, style = theme.type.style(theme.type.small, FontWeight.SemiBold),
                        modifier = Modifier.sizeIn(minHeight = metrics.touch).clickable { groups.add(name); name = ""; naming = false }.padding(m.gap).testTag(ForumTags.GROUP_ADD),
                    )
                } else Text(
                    "+ " + strings.newGroup, color = c.accent, style = theme.type.style(theme.type.small, FontWeight.SemiBold),
                    modifier = Modifier.fillMaxWidth().sizeIn(minHeight = metrics.touch).clickable { naming = true }.padding(m.gap).testTag(ForumTags.NEW_GROUP),
                )
            }
        }
    }
}

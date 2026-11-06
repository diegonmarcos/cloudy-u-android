package com.diegonmarcos.superapp.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.time.ZoneId

/** One topic (= one conversation/thread): its [chat] is the live AI chat; [badge] is the short text in the rail avatar. */
data class ForumTopic(
    val id: String,
    val label: String,
    val badge: String,
    val chat: AiChatState,
    /** The incoming (assistant) sender's name shown over its bubbles. */
    val senderName: String = label,
    /** Whether incoming messages carry the green "Admin" badge (the agent). */
    val admin: Boolean = true,
)

/** The forum's words; English by default, a host passes its own resources. */
@Immutable
data class ForumStrings(
    val topics: String = "Topics",
    val all: String = "All",
    val newTopic: String = "New Topic",
    val admin: String = "Admin",
    /** "%1$d" is the count. */
    val messages: String = "%1\$d messages",
    val message: String = "Message",
    val today: String = "Today",
    val yesterday: String = "Yesterday",
    val emptyTitle: String = "No messages yet",
    val emptySub: String = "Say something to start the thread.",
    val thinking: String = "Typing…",
    val errorPrefix: String = "No answer: %1\$s",
    val back: String = "Back",
    val more: String = "More",
    val call: String = "Call",
    val info: String = "Info",
    val emoji: String = "Emoji",
    val attach: String = "Attach",
    val voice: String = "Voice message",
    val send: String = "Send",
    val openTopics: String = "Topics",
)

/** The forum's own sizes (dp tokens, defined once; the surface spells no literal). */
@Immutable
data class ForumMetrics(
    val railWidth: Dp = 72.dp,
    val railAvatar: Dp = 48.dp,
    val msgAvatar: Dp = 36.dp,
    val touch: Dp = 48.dp,
    val accentBar: Dp = 3.dp,
    val pillRadius: Dp = 14.dp,
    val tickSize: Dp = 14.dp,
    val tickStroke: Dp = 1.5.dp,
    val tabletRail: Dp = 88.dp,
    val tabletThreadMin: Dp = 360.dp,
    val twoPaneMin: Dp = 640.dp,
)

/** The test tags the forum is driven by. */
object ForumTags {
    const val SURFACE = "forum_surface"
    const val RAIL = "forum_rail"
    const val NEW_TOPIC = "forum_new_topic"
    const val BOARD = "forum_board"
    const val HEADER = "forum_header"
    const val BACK = "forum_back"
    const val LIST = "forum_list"
    const val INPUT = "forum_input"
    const val SEND = "forum_send"
    const val MIC = "forum_mic"
    const val EMOJI = "forum_emoji"
    const val ATTACH = "forum_attach"
    const val EMPTY = "forum_empty"
    const val ERROR = "forum_error"
    const val THINKING = "forum_thinking"
    fun topic(id: String) = "forum_topic_$id"
    fun separator(label: String) = "forum_sep_$label"
    fun bubble(index: Int) = "forum_bubble_$index"
    fun receipt(index: Int) = "forum_receipt_$index"
    fun adminBadge(index: Int) = "forum_admin_$index"
}

/** How the surface draws one named icon; a host maps the name to its own icon set. Names: board, back, more, call, info, emoji, attach, mic, send, new. */
typealias ForumIcon = @Composable (name: String, size: Dp, tint: Color, description: String?) -> Unit

/** A plain Unicode fallback so a host with no icon set still renders every control. */
val ForumGlyphs: ForumIcon = { name, size, tint, description ->
    androidx.compose.material3.Text(
        when (name) {
            "board", "topics" -> "☰"; "back" -> "‹"; "more" -> "⋮"; "call" -> "☎"
            "info" -> "ⓘ"; "emoji" -> "☺"; "attach" -> "📎"; "mic" -> "🎙"
            "send" -> "↑"; "new" -> "+"; else -> "•"
        },
        color = tint, fontSize = size.value.sp,
        modifier = if (description != null) Modifier.semantics { contentDescription = description } else Modifier,
    )
}

/**
 * The forum/topics chat surface: a narrow left rail of topics (board icon, All, the topics with their avatar badges and
 * the accent selection bar, + New Topic), and the open topic's thread — a header (# topic · N messages, back, call, info,
 * more), the message list (centered date pills, incoming grey bubbles on the left with the sender name and the Admin badge,
 * outgoing accent bubbles on the right with the timestamp and the sent/delivered/read ticks, consecutive messages grouped),
 * and the composer (emoji, the Message field, attach, and the mic that becomes Send once there is text). On a wide screen
 * (tablet) the rail and the thread stand side by side. All sizes come from [theme] and [metrics]; colours from [theme].
 */
@Composable
fun AiForumChat(
    topics: List<ForumTopic>,
    selectedId: String,
    onSelect: (String) -> Unit,
    theme: AiTheme,
    modifier: Modifier = Modifier,
    strings: ForumStrings = ForumStrings(),
    metrics: ForumMetrics = ForumMetrics(),
    icon: ForumIcon = ForumGlyphs,
    /** The green "Admin" badge colour; defaults to the theme accent when the host has no green token. */
    adminColor: Color = theme.colors.accent,
    onNewTopic: (() -> Unit)? = null,
    onCall: (() -> Unit)? = null,
    onInfo: (() -> Unit)? = null,
    onMore: (() -> Unit)? = null,
    onAttach: (() -> Unit)? = null,
    zone: ZoneId = ZoneId.systemDefault(),
    now: Long = System.currentTimeMillis(),
) {
    val topic = topics.firstOrNull { it.id == selectedId } ?: topics.firstOrNull()
    BoxWithConstraints(modifier.fillMaxSize().testTag(ForumTags.SURFACE)) {
        val twoPane = maxWidth >= metrics.twoPaneMin
        Row(Modifier.fillMaxSize()) {
            // On a narrow screen the rail is always visible (Telegram's forum rail); on a tablet it is the first pane.
            TopicsRail(topics, topic?.id, onSelect, theme, strings, metrics, icon, if (twoPane) metrics.tabletRail else metrics.railWidth, onNewTopic)
            if (topic != null) {
                Thread(topic, theme, strings, metrics, icon, adminColor, onCall, onInfo, onMore, onAttach, zone, now, Modifier.weight(1f))
            } else {
                Box(Modifier.weight(1f).fillMaxSize())
            }
        }
    }
}

@Composable
private fun TopicsRail(
    topics: List<ForumTopic>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    theme: AiTheme,
    strings: ForumStrings,
    metrics: ForumMetrics,
    icon: ForumIcon,
    width: Dp,
    onNewTopic: (() -> Unit)?,
) {
    val c = theme.colors
    val m = theme.metrics
    Column(
        Modifier.width(width).fillMaxHeight().background(c.chatBar).testTag(ForumTags.RAIL),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(metrics.touch).clickable(onClick = {}).testTag(ForumTags.BOARD),
            contentAlignment = Alignment.Center,
        ) { icon("board", m.icon, c.text2, strings.topics) }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            topics.forEachIndexed { i, t ->
                // "All" is the first topic's slot in Telegram's rail; here the first topic doubles as All + # General.
                RailEntry(if (i == 0) strings.all else t.badge, t.label, t.id == selectedId, theme, metrics, { onSelect(t.id) }, ForumTags.topic(t.id))
            }
        }
        if (onNewTopic != null) Box(
            Modifier.sizeIn(minWidth = metrics.touch, minHeight = metrics.touch).clickable(onClick = onNewTopic).testTag(ForumTags.NEW_TOPIC),
            contentAlignment = Alignment.Center,
        ) { icon("new", m.iconLg, c.accent, strings.newTopic) }
    }
}

@Composable
private fun RailEntry(badge: String, label: String, selected: Boolean, theme: AiTheme, metrics: ForumMetrics, onClick: () -> Unit, tag: String) {
    val c = theme.colors
    val m = theme.metrics
    Row(
        Modifier.fillMaxWidth().sizeIn(minHeight = metrics.touch).clickable(onClick = onClick)
            .semantics { contentDescription = label }.testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The selection accent bar on the left edge.
        Box(Modifier.width(metrics.accentBar).fillMaxHeight().background(if (selected) c.accent else Color.Transparent))
        Spacer(Modifier.width(m.small))
        Box(
            Modifier.padding(vertical = m.small).size(metrics.railAvatar).clip(CircleShape)
                .background(if (selected) c.userBubble else c.tile).border(m.hairline, if (selected) c.accent else c.tileBorder, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.Text(
                badge, color = if (selected) c.accent else c.text2,
                style = theme.type.style(theme.type.small, FontWeight.SemiBold), maxLines = 1, textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun Thread(
    topic: ForumTopic,
    theme: AiTheme,
    strings: ForumStrings,
    metrics: ForumMetrics,
    icon: ForumIcon,
    adminColor: Color,
    onCall: (() -> Unit)?,
    onInfo: (() -> Unit)?,
    onMore: (() -> Unit)?,
    onAttach: (() -> Unit)?,
    zone: ZoneId,
    now: Long,
    modifier: Modifier,
) {
    val chat = topic.chat
    val c = theme.colors
    Column(modifier.fillMaxHeight()) {
        ThreadHeader(topic, chat.session.messages.size, theme, strings, metrics, icon, onCall, onInfo, onMore)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (chat.session.messages.isEmpty() && !chat.sending) EmptyThread(theme, strings)
            else MessageList(topic, theme, strings, metrics, adminColor, zone, now)
        }
        Composer(chat, theme, strings, metrics, icon, onAttach)
    }
}

@Composable
private fun ThreadHeader(
    topic: ForumTopic,
    count: Int,
    theme: AiTheme,
    strings: ForumStrings,
    metrics: ForumMetrics,
    icon: ForumIcon,
    onCall: (() -> Unit)?,
    onInfo: (() -> Unit)?,
    onMore: (() -> Unit)?,
) {
    val c = theme.colors
    val m = theme.metrics
    Row(
        Modifier.fillMaxWidth().background(c.chatBar).padding(horizontal = m.small).sizeIn(minHeight = metrics.touch).testTag(ForumTags.HEADER),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(metrics.touch).clickable(onClick = {}).testTag(ForumTags.BACK), contentAlignment = Alignment.Center) {
            icon("back", m.icon, c.text2, strings.back)
        }
        Column(Modifier.weight(1f).padding(horizontal = m.small)) {
            androidx.compose.material3.Text(
                "# " + topic.label, color = c.text, style = theme.type.style(theme.type.chat, FontWeight.SemiBold),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            androidx.compose.material3.Text(
                strings.messages.format(count), color = c.text2, style = theme.type.style(theme.type.small),
            )
        }
        if (onCall != null) HeaderIcon(icon, "call", strings.call, theme, metrics, onCall)
        if (onInfo != null) HeaderIcon(icon, "info", strings.info, theme, metrics, onInfo)
        HeaderIcon(icon, "more", strings.more, theme, metrics, onMore ?: {})
    }
}

@Composable
private fun HeaderIcon(icon: ForumIcon, name: String, desc: String, theme: AiTheme, metrics: ForumMetrics, onClick: () -> Unit) {
    Box(Modifier.size(metrics.touch).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        icon(name, theme.metrics.icon, theme.colors.text2, desc)
    }
}

@Composable
private fun EmptyThread(theme: AiTheme, strings: ForumStrings) {
    val c = theme.colors
    val m = theme.metrics
    Column(
        Modifier.fillMaxSize().padding(m.gutter).testTag(ForumTags.EMPTY),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        androidx.compose.material3.Text(strings.emptyTitle, color = c.text, style = theme.type.style(theme.type.greetingSub, FontWeight.SemiBold), textAlign = TextAlign.Center)
        Spacer(Modifier.size(m.small))
        androidx.compose.material3.Text(strings.emptySub, color = c.text2, style = theme.type.style(theme.type.chatInput), textAlign = TextAlign.Center)
    }
}

@Composable
private fun MessageList(topic: ForumTopic, theme: AiTheme, strings: ForumStrings, metrics: ForumMetrics, adminColor: Color, zone: ZoneId, now: Long) {
    val chat = topic.chat
    val c = theme.colors
    val m = theme.metrics
    val list = rememberLazyListState()
    val rows = remember(chat.session.messages, now) {
        AiForum.rows(chat.session.messages, zone, now, chat.session.updated.takeIf { it > 0 } ?: now, strings.today, strings.yesterday)
    }
    LaunchedEffect(chat.session.messages.size, chat.sending) {
        val n = rows.size + (if (chat.sending || chat.error != null) 1 else 0)
        if (n > 0) list.animateScrollToItem(n - 1)
    }
    LazyColumn(
        Modifier.fillMaxSize().testTag(ForumTags.LIST), state = list,
        contentPadding = PaddingValues(horizontal = m.gutter, vertical = m.gap),
        verticalArrangement = Arrangement.spacedBy(m.tiny),
    ) {
        items(count = rows.size) { i ->
            when (val row = rows[i]) {
                is AiForum.Row.Separator -> DatePill(row.label, theme, metrics)
                is AiForum.Row.Message -> MessageBubble(topic, row, chat, theme, strings, metrics, adminColor, zone)
            }
        }
        if (chat.sending && chat.streaming.isEmpty()) item { ThinkingBubble(topic, theme, strings, metrics, adminColor) }
        if (chat.sending && chat.streaming.isNotEmpty()) item {
            IncomingBubble(topic, chat.streaming, "", null, true, true, theme, strings, metrics, adminColor, -1)
        }
        chat.error?.let { e ->
            item {
                androidx.compose.material3.Text(
                    strings.errorPrefix.format(e), color = c.negative, style = theme.type.style(theme.type.small),
                    modifier = Modifier.fillMaxWidth().padding(m.small).testTag(ForumTags.ERROR), textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun DatePill(label: String, theme: AiTheme, metrics: ForumMetrics) {
    val c = theme.colors
    val m = theme.metrics
    Box(Modifier.fillMaxWidth().padding(vertical = m.small), contentAlignment = Alignment.Center) {
        androidx.compose.material3.Text(
            label, color = c.text2, style = theme.type.style(theme.type.label, FontWeight.Medium),
            modifier = Modifier.clip(RoundedCornerShape(metrics.pillRadius)).background(c.tile)
                .padding(horizontal = m.cardPad, vertical = m.tiny).semantics { contentDescription = label }
                .testTag(ForumTags.separator(label)),
        )
    }
}

@Composable
private fun MessageBubble(topic: ForumTopic, row: AiForum.Row.Message, chat: AiChatState, theme: AiTheme, strings: ForumStrings, metrics: ForumMetrics, adminColor: Color, zone: ZoneId) {
    val msg = row.msg
    val time = AiForum.time(AiForum.instant(msg, chat.session.updated.takeIf { it > 0 } ?: msg.at), zone)
    if (row.outgoing) OutgoingBubble(msg, time, AiForum.receipt(chat.session.messages, row.index, chat.sending), row.firstInGroup, row.lastInGroup, theme, metrics, row.index)
    else IncomingBubble(topic, msg.content, time, if (topic.admin && row.firstInGroup) strings.admin else null, row.firstInGroup, row.lastInGroup, theme, strings, metrics, adminColor, row.index)
}

@Composable
private fun IncomingBubble(
    topic: ForumTopic, content: String, time: String, adminBadge: String?,
    firstInGroup: Boolean, lastInGroup: Boolean, theme: AiTheme, strings: ForumStrings, metrics: ForumMetrics, adminColor: Color, index: Int,
) {
    val c = theme.colors
    val m = theme.metrics
    val r = m.bubbleRadius
    val tail = if (lastInGroup) m.bubbleTail else r
    Row(Modifier.fillMaxWidth().padding(top = if (firstInGroup) m.gap else 0.dp), verticalAlignment = Alignment.Bottom) {
        // The avatar once, at the group's last (bottom) bubble, like Telegram.
        Box(Modifier.size(metrics.msgAvatar), contentAlignment = Alignment.Center) {
            if (lastInGroup) Box(
                Modifier.size(metrics.msgAvatar).clip(CircleShape).background(c.ai),
                contentAlignment = Alignment.Center,
            ) { androidx.compose.material3.Text(topic.badge, color = c.onAi, style = theme.type.style(theme.type.label, FontWeight.Bold), maxLines = 1) }
        }
        Spacer(Modifier.width(m.gap))
        Column(
            Modifier.fillMaxWidth(theme.metrics.bubbleMaxFraction).clip(RoundedCornerShape(r, r, r, tail)).background(c.tile)
                .padding(horizontal = m.bubblePadH, vertical = m.bubblePadV).testTag(if (index >= 0) ForumTags.bubble(index) else ForumTags.THINKING),
            verticalArrangement = Arrangement.spacedBy(m.tiny),
        ) {
            if (firstInGroup) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.chipGap)) {
                androidx.compose.material3.Text(topic.senderName, color = c.accent, style = theme.type.style(theme.type.small, FontWeight.SemiBold))
                if (adminBadge != null) androidx.compose.material3.Text(
                    adminBadge, color = c.onAi,
                    style = theme.type.style(theme.type.label, FontWeight.Bold),
                    modifier = Modifier.clip(RoundedCornerShape(metrics.pillRadius)).background(adminColor).padding(horizontal = m.chipGap, vertical = m.hairline)
                        .semantics { contentDescription = adminBadge }.testTag(if (index >= 0) ForumTags.adminBadge(index) else ForumTags.THINKING + "_admin"),
                )
            }
            androidx.compose.material3.Text(content, color = c.text, style = TextStyle(fontSize = theme.type.chat))
            if (time.isNotBlank()) androidx.compose.material3.Text(time, color = c.text2, style = theme.type.style(theme.type.label), modifier = Modifier.align(Alignment.End))
        }
        Spacer(Modifier.width(metrics.msgAvatar))
    }
}

@Composable
private fun OutgoingBubble(msg: AiMsg, time: String, receipt: AiForum.Receipt?, firstInGroup: Boolean, lastInGroup: Boolean, theme: AiTheme, metrics: ForumMetrics, index: Int) {
    val c = theme.colors
    val m = theme.metrics
    val r = m.bubbleRadius
    val tail = if (lastInGroup) m.bubbleTail else r
    Row(Modifier.fillMaxWidth().padding(top = if (firstInGroup) m.gap else 0.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.Bottom) {
        Spacer(Modifier.width(metrics.msgAvatar))
        Column(
            Modifier.fillMaxWidth(theme.metrics.bubbleMaxFraction).clip(RoundedCornerShape(r, r, tail, r)).background(c.userBubble)
                .padding(horizontal = m.bubblePadH, vertical = m.bubblePadV).testTag(ForumTags.bubble(index)),
            verticalArrangement = Arrangement.spacedBy(m.tiny),
        ) {
            androidx.compose.material3.Text(msg.content, color = c.onUserBubble, style = TextStyle(fontSize = theme.type.chat))
            Row(horizontalArrangement = Arrangement.spacedBy(m.chipGap), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.align(Alignment.End)) {
                if (time.isNotBlank()) androidx.compose.material3.Text(time, color = c.onUserBubble.copy(alpha = 0.7f), style = theme.type.style(theme.type.label))
                if (receipt != null) ReceiptTicks(receipt, c.accent, c.onUserBubble.copy(alpha = 0.7f), theme.colors.onAi, metrics, index)
            }
        }
    }
}

/** Telegram-style ticks: one check for SENT, two grey checks for DELIVERED, two accent checks for READ. Drawn, not glyphs, so they render the same everywhere. */
@Composable
private fun ReceiptTicks(receipt: AiForum.Receipt, read: Color, grey: Color, onAccent: Color, metrics: ForumMetrics, index: Int) {
    val double = receipt != AiForum.Receipt.SENT
    val tint = if (receipt == AiForum.Receipt.READ) read else grey
    val desc = when (receipt) { AiForum.Receipt.SENT -> "Sent"; AiForum.Receipt.DELIVERED -> "Delivered"; AiForum.Receipt.READ -> "Read" }
    androidx.compose.foundation.Canvas(
        Modifier.size(metrics.tickSize).semantics { contentDescription = desc }.testTag(ForumTags.receipt(index)),
    ) {
        val sw = metrics.tickStroke.toPx()
        val w = size.width; val h = size.height
        val offsets = if (double) listOf(0f, w * 0.28f) else listOf(0f)
        for (dx in offsets) {
            drawLine(tint, Offset(dx + w * 0.05f, h * 0.55f), Offset(dx + w * 0.30f, h * 0.80f), sw, StrokeCap.Round)
            drawLine(tint, Offset(dx + w * 0.30f, h * 0.80f), Offset(dx + w * 0.62f, h * 0.30f), sw, StrokeCap.Round)
        }
    }
}

@Composable
private fun ThinkingBubble(topic: ForumTopic, theme: AiTheme, strings: ForumStrings, metrics: ForumMetrics, adminColor: Color) {
    IncomingBubble(topic, strings.thinking, "", if (topic.admin) strings.admin else null, true, true, theme, strings, metrics, adminColor, -1)
}

@Composable
private fun Composer(chat: AiChatState, theme: AiTheme, strings: ForumStrings, metrics: ForumMetrics, icon: ForumIcon, onAttach: (() -> Unit)?) {
    val c = theme.colors
    val m = theme.metrics
    val scope = rememberCoroutineScope()
    val hasText = chat.draft.isNotBlank()
    fun send() {
        val ask = chat.draft.trim()
        if (ask.isNotEmpty() && !chat.sending) { chat.draft = ""; scope.launch { chat.send(ask) } }
    }
    Row(
        Modifier.fillMaxWidth().background(c.chatBar).padding(horizontal = m.small, vertical = m.small),
        verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(m.tiny),
    ) {
        Box(Modifier.size(metrics.touch).clickable(onClick = {}).testTag(ForumTags.EMOJI), contentAlignment = Alignment.Center) {
            icon("emoji", m.icon, c.text2, strings.emoji)
        }
        Box(Modifier.weight(1f).heightIn(min = metrics.touch), contentAlignment = Alignment.CenterStart) {
            val shape = RoundedCornerShape(m.chatBarRadius)
            BasicTextField(
                value = chat.draft, onValueChange = { chat.draft = it }, minLines = 1, maxLines = 6,
                textStyle = TextStyle(color = c.text, fontSize = theme.type.chatInput),
                cursorBrush = SolidColor(c.accent),
                modifier = Modifier.fillMaxWidth().clip(shape).background(c.field).border(m.hairline, c.tileBorder, shape).testTag(ForumTags.INPUT),
                decorationBox = { inner ->
                    Box(Modifier.padding(horizontal = m.bubblePadH, vertical = m.bubblePadV)) {
                        if (chat.draft.isEmpty()) androidx.compose.material3.Text(strings.message, color = c.text2, style = TextStyle(fontSize = theme.type.chatInput))
                        inner()
                    }
                },
            )
        }
        if (onAttach != null) Box(Modifier.size(metrics.touch).clickable(onClick = onAttach).testTag(ForumTags.ATTACH), contentAlignment = Alignment.Center) {
            icon("attach", m.icon, c.text2, strings.attach)
        }
        // The mic becomes Send once there is text (Telegram's toggle).
        Box(
            Modifier.size(metrics.touch).clip(CircleShape).background(if (hasText) c.ai else SolidColor(Color.Transparent))
                .clickable(enabled = !chat.sending) { if (hasText) send() }
                .testTag(if (hasText) ForumTags.SEND else ForumTags.MIC),
            contentAlignment = Alignment.Center,
        ) {
            if (hasText) icon("send", m.icon, c.onAi, strings.send) else icon("mic", m.icon, c.text2, strings.voice)
        }
    }
}

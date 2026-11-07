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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.abs
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
import androidx.compose.ui.geometry.Size
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.platform.LocalContext
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

/**
 * The left rail as the user's CONVERSATIONS: one avatar per stored chat session ([list], newest first), the open one
 * [currentId] marked, a tap opens it ([onOpen]) and the top button starts a new one ([onNew]). When set, the topics
 * (personas / agents) move to a strip under the thread header instead of the rail.
 */
class ForumSessions(
    val list: List<AiSession>,
    val currentId: String,
    val onOpen: (AiSession) -> Unit,
    val onNew: () -> Unit,
)

/**
 * The AI tooling the composer carries (what the plain chat page has): the app's composer declaration ([decl]: the (+) attach
 * menu, Task, Agent, Model, Effort, Mode, MCP, More), the attach host (Files, Image, Camera with Perms), the model
 * catalogue and task catalogue pages that replace the thread, the consent card, the context line, inline image attachments
 * and Markdown answers. Null = the plain forum composer.
 */
class ForumTools(
    val decl: AiComposerDecl,
    val attach: AiAttachHost? = null,
    val onAttach: ((String) -> Unit)? = null,
    val catalogueFetch: CatalogueFetch = AiPublicFetch,
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
    /** The sessions rail (the left bar is the user's conversations): the new-session button and an unnamed session. */
    val newSession: String = "New chat",
    val sessions: String = "Chats",
    val untitled: String = "New chat",
    /** The topic strip (the personas / agents of a session) and the composer's attached-tools hint. */
    val participants: String = "Participants",
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
    /** The styled top bar: corner radius, shadow, avatar. */
    val headerRadius: Dp = 20.dp,
    val headerElevation: Dp = 6.dp,
    val headerAvatar: Dp = 40.dp,
    /** The persona strip's chip height and the model badge radius. */
    val chipHeight: Dp = 36.dp,
    /** The chat background doodle: tile size, stroke, and how faint it is (alpha) and the colour wash on top. */
    val doodleCell: Dp = 64.dp,
    val doodleStroke: Dp = 1.dp,
    val doodleAlpha: Float = 0.08f,
    val washAlpha: Float = 0.10f,
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
    const val BACKDROP = "forum_backdrop"
    const val SESSIONS_RAIL = "forum_sessions_rail"
    const val NEW_SESSION = "forum_new_session"
    const val TOPIC_STRIP = "forum_topic_strip"
    const val HEADER_AVATAR = "forum_header_avatar"
    const val TOOLS_BAR = "forum_tools_bar"
    fun session(id: String) = "forum_session_$id"
    fun modelBadge(index: Int) = "forum_model_$index"
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
    /** Non-null: the left rail lists the user's chat sessions and the topics become a strip under the header. */
    sessions: ForumSessions? = null,
    /** Non-null: the composer carries the full AI tooling (see [ForumTools]). */
    tools: ForumTools? = null,
    /** The doodle background behind the messages (drawn from the theme's colours, faint). */
    backdrop: Boolean = true,
) {
    val topic = topics.firstOrNull { it.id == selectedId } ?: topics.firstOrNull()
    BoxWithConstraints(modifier.fillMaxSize().testTag(ForumTags.SURFACE)) {
        val twoPane = maxWidth >= metrics.twoPaneMin
        val railWidth = if (twoPane) metrics.tabletRail else metrics.railWidth
        Row(Modifier.fillMaxSize()) {
            // On a narrow screen the rail is always visible (Telegram's forum rail); on a tablet it is the first pane.
            if (sessions != null) SessionsRail(sessions, theme, strings, metrics, icon, railWidth)
            else TopicsRail(topics, topic?.id, onSelect, theme, strings, metrics, icon, railWidth, onNewTopic)
            if (topic != null) {
                Thread(
                    topic, theme, strings, metrics, icon, adminColor, onCall, onInfo, onMore, onAttach, zone, now, Modifier.weight(1f),
                    topics = if (sessions != null) topics else emptyList(), onSelect = onSelect, tools = tools, backdrop = backdrop,
                )
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
    topics: List<ForumTopic> = emptyList(),
    onSelect: (String) -> Unit = {},
    tools: ForumTools? = null,
    backdrop: Boolean = true,
) {
    val chat = topic.chat
    val ctx = LocalContext.current
    val design = remember(tools != null) { if (tools != null) runCatching { AiDesign.load(ctx) }.getOrNull() else null }
    if (tools != null) LaunchedEffect(topic.id) {
        if (chat.models.isEmpty()) chat.models = withContext(Dispatchers.IO) { runCatching { chat.host().models() }.getOrDefault(emptyList()) }
        if ("task" in tools.decl.controls && chat.tasks.isEmpty()) chat.tasks = runCatching { AiCatalogue.load(ctx).tasks }.getOrDefault(emptyList()).ifEmpty { AiDesign.loadTasks(ctx) }
    }
    // The Model button and the Task button swap their full catalogue pages in for the thread, as in the plain chat page.
    if (tools != null && chat.page == AiChatState.PAGE_MODELS) { AiModelCatalogue(chat, theme, tools.catalogueFetch, modifier); return }
    if (tools != null && chat.page == AiChatState.PAGE_TASKS) { AiTaskCatalogue(chat, theme, tools.decl, modifier); return }
    Column(modifier.fillMaxHeight()) {
        ThreadHeader(topic, chat.session.messages.size, theme, strings, metrics, icon, onCall, onInfo, onMore, topics.isNotEmpty())
        if (topics.isNotEmpty()) TopicStrip(topics, topic.id, onSelect, theme, strings, metrics)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (backdrop) ChatBackdrop(theme, metrics)
            if (chat.session.messages.isEmpty() && !chat.sending) EmptyThread(theme, strings)
            else MessageList(topic, theme, strings, metrics, adminColor, zone, now, tools != null)
        }
        if (tools != null) chat.consent?.let { req ->
            AiConsentCard(req, onAllow = { chat.decide(true) }, onDeny = { chat.decide(false) }, theme = theme, modifier = Modifier.padding(horizontal = theme.metrics.small))
        }
        Composer(chat, theme, strings, metrics, icon, onAttach, tools, design)
    }
}

/** The top bar: a rounded, elevated, gradient bar with the avatar, the title (topic or session), who is in the thread and the actions. */
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
    sessionMode: Boolean,
) {
    val c = theme.colors
    val m = theme.metrics
    val chat = topic.chat
    val shape = RoundedCornerShape(bottomStart = metrics.headerRadius, bottomEnd = metrics.headerRadius)
    val title = if (sessionMode) chat.session.title.ifBlank { strings.untitled } else "# " + topic.label
    val who = listOf(topic.senderName, chat.model.substringAfterLast('/')).filter { it.isNotBlank() }.joinToString(" · ")
    val sub = if (chat.sending) strings.thinking else listOf(who, strings.messages.format(count)).filter { it.isNotBlank() }.joinToString(" · ")
    Row(
        Modifier.fillMaxWidth().shadow(metrics.headerElevation, shape).clip(shape)
            .background(Brush.horizontalGradient(listOf(c.chatBar, c.tile)))
            .border(m.hairline, c.tileBorder, shape)
            .padding(horizontal = m.small).sizeIn(minHeight = metrics.touch).testTag(ForumTags.HEADER),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(metrics.touch).clickable(onClick = {}).testTag(ForumTags.BACK), contentAlignment = Alignment.Center) {
            icon("back", m.icon, c.text2, strings.back)
        }
        Box(
            Modifier.size(metrics.headerAvatar).clip(CircleShape).background(c.ai).testTag(ForumTags.HEADER_AVATAR),
            contentAlignment = Alignment.Center,
        ) { androidx.compose.material3.Text(topic.badge, color = c.onAi, style = theme.type.style(theme.type.small, FontWeight.Bold), maxLines = 1) }
        Column(Modifier.weight(1f).padding(horizontal = m.gap)) {
            androidx.compose.material3.Text(
                title, color = c.text, style = theme.type.style(theme.type.chat, FontWeight.SemiBold),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            androidx.compose.material3.Text(
                sub, color = if (chat.sending) c.accent else c.text2, style = theme.type.style(theme.type.small),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (onCall != null) HeaderIcon(icon, "call", strings.call, theme, metrics, onCall)
        if (onInfo != null) HeaderIcon(icon, "info", strings.info, theme, metrics, onInfo)
        HeaderIcon(icon, "more", strings.more, theme, metrics, onMore ?: {})
    }
}

/** The topics (personas / agents) as a horizontal chip strip under the header, used when the left rail is the sessions. */
@Composable
private fun TopicStrip(topics: List<ForumTopic>, selectedId: String, onSelect: (String) -> Unit, theme: AiTheme, strings: ForumStrings, metrics: ForumMetrics) {
    val c = theme.colors
    val m = theme.metrics
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = m.small)
            .semantics { contentDescription = strings.participants }.testTag(ForumTags.TOPIC_STRIP),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.chipGap),
    ) {
        topics.forEach { t ->
            val sel = t.id == selectedId
            val shape = RoundedCornerShape(metrics.pillRadius)
            Box(
                Modifier.sizeIn(minHeight = metrics.touch).clickable { onSelect(t.id) }.semantics { contentDescription = t.label }.testTag(ForumTags.topic(t.id)),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    Modifier.clip(shape).background(if (sel) c.userBubble else c.tile).border(m.hairline, if (sel) c.accent else c.tileBorder, shape)
                        .padding(horizontal = m.cardPad, vertical = m.small),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.chipGap),
                ) {
                    androidx.compose.material3.Text(t.badge, color = if (sel) c.accent else c.text2, style = theme.type.style(theme.type.label, FontWeight.Bold), maxLines = 1)
                    androidx.compose.material3.Text(t.label, color = if (sel) c.text else c.text2, style = theme.type.style(theme.type.small, FontWeight.Medium), maxLines = 1)
                }
            }
        }
    }
}

/** The left rail as the user's chat sessions: a New-chat button on top, then one avatar per stored session, the open one marked. */
@Composable
private fun SessionsRail(s: ForumSessions, theme: AiTheme, strings: ForumStrings, metrics: ForumMetrics, icon: ForumIcon, width: Dp) {
    val c = theme.colors
    val m = theme.metrics
    Column(
        Modifier.width(width).fillMaxHeight().background(c.chatBar).semantics { contentDescription = strings.sessions }.testTag(ForumTags.SESSIONS_RAIL),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(metrics.touch).clickable(onClick = s.onNew).testTag(ForumTags.NEW_SESSION), contentAlignment = Alignment.Center) {
            icon("new", m.iconLg, c.accent, strings.newSession)
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            s.list.forEach { sess ->
                val label = sess.title.ifBlank { strings.untitled }
                RailEntry(sessionInitials(label), label, sess.id == s.currentId, theme, metrics, { s.onOpen(sess) }, ForumTags.session(sess.id))
            }
        }
    }
}

private fun sessionInitials(label: String): String =
    label.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1).uppercase() }.ifEmpty { "+" }

/**
 * The chat background: a faint doodle (circles, plusses, arcs, squares, zigzags, dots on a jittered grid, seeded so it is the
 * same every frame) over a soft accent wash. Drawn from the theme's colours at a low alpha, so it follows dark and light and
 * the bubbles and text stay readable. Sizes and alphas are [ForumMetrics].
 */
@Composable
private fun ChatBackdrop(theme: AiTheme, metrics: ForumMetrics) {
    val c = theme.colors
    Canvas(Modifier.fillMaxSize().testTag(ForumTags.BACKDROP)) {
        drawRect(Brush.verticalGradient(listOf(c.accent.copy(alpha = metrics.washAlpha), Color.Transparent, c.accent.copy(alpha = metrics.washAlpha / 2f))))
        val cell = metrics.doodleCell.toPx()
        val sw = metrics.doodleStroke.toPx()
        val ink = c.text2.copy(alpha = metrics.doodleAlpha)
        val style = Stroke(width = sw, cap = StrokeCap.Round)
        val cols = (size.width / cell).toInt() + 1
        val rows = (size.height / cell).toInt() + 1
        for (r in 0 until rows) for (k in 0 until cols) {
            val h = abs((r * 73856093) xor (k * 19349663) xor 83492791)
            val jx = ((h % 97) / 97f - 0.5f) * cell * 0.5f
            val jy = (((h / 97) % 89) / 89f - 0.5f) * cell * 0.5f
            val cx = (k + 0.5f) * cell + jx + (if (r % 2 == 0) 0f else cell * 0.5f)
            val cy = (r + 0.5f) * cell + jy
            val u = cell * 0.16f * (1f + (h % 3) * 0.25f)
            when ((h / 8633) % 6) {
                0 -> drawCircle(ink, u, Offset(cx, cy), style = style)
                1 -> {
                    drawLine(ink, Offset(cx - u, cy), Offset(cx + u, cy), sw, StrokeCap.Round)
                    drawLine(ink, Offset(cx, cy - u), Offset(cx, cy + u), sw, StrokeCap.Round)
                }
                2 -> drawArc(ink, 200f, 250f, false, Offset(cx - u, cy - u), Size(2f * u, 2f * u), style = style)
                3 -> drawRect(ink, Offset(cx - u, cy - u), Size(2f * u, 2f * u), style = style)
                4 -> {
                    drawLine(ink, Offset(cx - u, cy + u * 0.5f), Offset(cx, cy - u * 0.5f), sw, StrokeCap.Round)
                    drawLine(ink, Offset(cx, cy - u * 0.5f), Offset(cx + u, cy + u * 0.5f), sw, StrokeCap.Round)
                }
                else -> drawCircle(ink, sw * 1.5f, Offset(cx, cy))
            }
        }
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
private fun MessageList(topic: ForumTopic, theme: AiTheme, strings: ForumStrings, metrics: ForumMetrics, adminColor: Color, zone: ZoneId, now: Long, rich: Boolean = false) {
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
                is AiForum.Row.Message -> MessageBubble(topic, row, chat, theme, strings, metrics, adminColor, zone, rich)
            }
        }
        if (chat.sending && chat.streaming.isEmpty()) item { ThinkingBubble(topic, theme, strings, metrics, adminColor) }
        if (chat.sending && chat.streaming.isNotEmpty()) item {
            IncomingBubble(topic, chat.streaming, "", null, true, true, theme, strings, metrics, adminColor, -1, rich)
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
private fun MessageBubble(topic: ForumTopic, row: AiForum.Row.Message, chat: AiChatState, theme: AiTheme, strings: ForumStrings, metrics: ForumMetrics, adminColor: Color, zone: ZoneId, rich: Boolean = false) {
    val msg = row.msg
    val time = AiForum.time(AiForum.instant(msg, chat.session.updated.takeIf { it > 0 } ?: msg.at), zone)
    if (row.outgoing) OutgoingBubble(msg, time, AiForum.receipt(chat.session.messages, row.index, chat.sending), row.firstInGroup, row.lastInGroup, theme, metrics, row.index, if (rich) chat else null)
    else IncomingBubble(topic, msg.content, time, if (topic.admin && row.firstInGroup) strings.admin else null, row.firstInGroup, row.lastInGroup, theme, strings, metrics, adminColor, row.index, rich, msg, chat)
}

@Composable
private fun IncomingBubble(
    topic: ForumTopic, content: String, time: String, adminBadge: String?,
    firstInGroup: Boolean, lastInGroup: Boolean, theme: AiTheme, strings: ForumStrings, metrics: ForumMetrics, adminColor: Color, index: Int,
    rich: Boolean = false, msg: AiMsg? = null, chat: AiChatState? = null,
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
            if (rich && theme.markdown) AiMarkdownText(content, theme, Modifier.fillMaxWidth())
            else androidx.compose.material3.Text(content, color = c.text, style = TextStyle(fontSize = theme.type.chat))
            // Images the answer drew (inline, with Save / Share / Use as input) and files, then the agent · model badge.
            if (rich && msg != null && chat != null) AttachmentList(chat, theme, msg.attachments, actions = true)
            if (rich && msg != null && msg.meta.isNotBlank()) androidx.compose.material3.Text(
                msg.meta, color = c.accent, style = theme.type.style(theme.type.label, FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clip(RoundedCornerShape(metrics.pillRadius)).background(c.field).padding(horizontal = m.chipGap, vertical = m.hairline)
                    .testTag(ForumTags.modelBadge(index)),
            )
            if (time.isNotBlank()) androidx.compose.material3.Text(time, color = c.text2, style = theme.type.style(theme.type.label), modifier = Modifier.align(Alignment.End))
        }
        Spacer(Modifier.width(metrics.msgAvatar))
    }
}

@Composable
private fun OutgoingBubble(msg: AiMsg, time: String, receipt: AiForum.Receipt?, firstInGroup: Boolean, lastInGroup: Boolean, theme: AiTheme, metrics: ForumMetrics, index: Int, chat: AiChatState? = null) {
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
            // The picture / files the user sent travel in the bubble (image in).
            if (chat != null) AttachmentList(chat, theme, msg.attachments, actions = false)
            if (msg.content.isNotBlank() || chat == null) androidx.compose.material3.Text(msg.content, color = c.onUserBubble, style = TextStyle(fontSize = theme.type.chat))
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
private fun Composer(chat: AiChatState, theme: AiTheme, strings: ForumStrings, metrics: ForumMetrics, icon: ForumIcon, onAttach: (() -> Unit)?, tools: ForumTools? = null, design: AiDesign? = null) {
    val c = theme.colors
    val m = theme.metrics
    val scope = rememberCoroutineScope()
    val rich = tools != null && design != null
    val hasText = chat.draft.isNotBlank() || (rich && chat.attachments.isNotEmpty())
    fun send() {
        val ask = chat.draft.trim()
        if ((ask.isNotEmpty() || (rich && chat.attachments.isNotEmpty())) && !chat.sending) { chat.draft = ""; scope.launch { chat.send(ask) } }
    }
    Column(Modifier.fillMaxWidth().background(c.chatBar)) {
        if (tools != null && design != null) Box(Modifier.padding(horizontal = m.small)) { AttachChips(chat, theme, design) }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = m.small, vertical = m.small),
            verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(m.tiny),
        ) {
            Box(Modifier.size(metrics.touch).clickable { if (!chat.sending) chat.draft += "\uD83D\uDE42" }.testTag(ForumTags.EMOJI), contentAlignment = Alignment.Center) {
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
        // The AI tooling of the plain chat page: the context line, then (+) attach, Task, Agent, Model, Effort, Mode, MCP, More.
        if (tools != null && design != null) {
            AiContext.line(chat.session, chat.models.firstOrNull { it.id == chat.model }?.context ?: 0, theme.strings)?.let {
                androidx.compose.material3.Text(it, color = c.text2, style = theme.type.style(theme.type.small), modifier = Modifier.padding(horizontal = m.gutter).testTag(AiTags.CONTEXT))
            }
            AiComposerBar(
                chat, theme, design, tools.decl, Modifier.padding(horizontal = m.small, vertical = m.small).testTag(ForumTags.TOOLS_BAR),
                onAttach = tools.onAttach, attach = tools.attach,
            )
        }
    }
}

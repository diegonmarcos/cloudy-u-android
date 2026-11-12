package com.diegonmarcos.superapp.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.modelcatalogue.CatalogueFetch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The page's colours: the consumer's own tokens (Cloud Search: its glass theme; the browser: its kit palette). */
@Immutable
data class AiColors(
    val text: Color, val text2: Color, val accent: Color, val negative: Color,
    val field: Color, val tile: Color, val tileBorder: Color, val chatBar: Color, val glassBorder: Color,
    val userBubble: Color, val onUserBubble: Color, val onAi: Color,
    /** The assistant's avatar, send button and greeting. */
    val ai: Brush,
)

/** Sizes, defaulting to Cloud Search's mockup (1 px = 1 dp). */
@Immutable
data class AiMetrics(
    val hairline: Dp = 1.dp, val tiny: Dp = 2.dp, val small: Dp = 4.dp, val gap: Dp = 8.dp, val gutter: Dp = 16.dp,
    val cardRadius: Dp = 12.dp, val cardPad: Dp = 10.dp, val chipGap: Dp = 6.dp,
    val aiAvatar: Dp = 32.dp, val robot: Dp = 64.dp, val engineGap: Dp = 12.dp,
    val chatBarRadius: Dp = 28.dp, val sendButton: Dp = 40.dp,
    val bubbleRadius: Dp = 24.dp, val bubbleTail: Dp = 4.dp, val bubblePadH: Dp = 16.dp, val bubblePadV: Dp = 12.dp,
    val bubbleMaxFraction: Float = 0.85f, val modelMaxWidth: Dp = 160.dp,
    val iconXs: Dp = 11.dp, val iconSm: Dp = 14.dp, val icon: Dp = 18.dp, val iconLg: Dp = 30.dp,
)

/** The type scale, defaulting to Cloud Search's (rem × 16 = sp). */
@Immutable
data class AiType(
    val label: TextUnit = 9.sp, val small: TextUnit = 10.sp, val chat: TextUnit = 15.sp,
    val greeting: TextUnit = 24.sp, val greetingSub: TextUnit = 14.sp, val chatInput: TextUnit = 14.sp,
) {
    fun style(size: TextUnit, weight: FontWeight = FontWeight.Normal) = TextStyle(fontSize = size, fontWeight = weight)
}

/** The words, defaulting to English; a consumer with its own resources passes them. */
@Immutable
data class AiStrings(
    /** The feature's one name, everywhere: tab, panel, header. */
    val title: String = "AI",
    /** The empty chat's greeting (Cloud Code's); [helloSub] blank = the line "agent · model". */
    val hello: String = "How can I help?",
    val helloSub: String = "",
    val searchTheWeb: String = "Search the Web…",
    /** "%1$s" is the engine's label. */
    val searchWith: String = "Search %1\$s…",
    val webSearch: String = "Web search",
    val webNative: String = "web",
    val free: String = "free",
    val modelsUnavailable: String = "Model list unavailable (offline?)",
    val newChat: String = "New chat",
    val searchingWeb: String = "Searching the web…",
    val thinking: String = "Thinking…",
    val sources: String = "Sources",
    /** "%1$s" is the reason. */
    val chatError: String = "No answer: %1\$s",
    val askAnything: String = "Ask anything…",
    val send: String = "Send",
    /** The Agent field: "no agent" is the model directly. */
    val agentNone: String = "No agent",
    val sessions: String = "Sessions",
    val noSessions: String = "No sessions yet",
    val untitled: String = "Untitled",
    val today: String = "Today",
    val previous7Days: String = "Previous 7 days",
    val older: String = "Older",
    val consentTitle: String = "Allow this action?",
    val allow: String = "Allow",
    val deny: String = "Deny",
    val rename: String = "Rename",
    val delete: String = "Delete",
    val cancel: String = "Cancel",
    val save: String = "Save",
    val noModelsInTab: String = "No model in this tab",
    val catalogue: String = "Model catalogue",
    val taskCatalogue: String = "Task catalogue",
    /** A Medium / Small row that runs only on the phone or the fleet Ollama. */
    val localOnly: String = "phone / Ollama only",
    /** The Task Catalogue's last group: the app's own tasks the shared catalogue does not list. */
    val appTasks: String = "This app",
    /** A catalogue task this app does not offer. */
    val taskNotOffered: String = "not offered in this app",
    val core: String = "A Text · B Search · C Audio & Speech · D Visual Media",
    val noMcp: String = "The gateway lists no MCP server",
    /** The empty chat's second line when [helloSub] is blank and no agent is picked. */
    val noAgentLine: String = "No agent",
    val notListed: String = "not listed by the gateway",
    /** The label a collapsed toolbar row keeps next to its chevron. */
    val toolbarRow1: String = "Task · Specialist · Agent · Model · Effort · Mode",
    val toolbarRow2: String = "Repos · Plugins · MCPs · More",
    val searchKgStore: String = "Search sources: web, releases, Google, other MCPs…",
    val kgStoreTitle: String = "KG-Store",
    val noKgItems: String = "Nothing in this tab matches.",
    val noRepos: String = "No repository is declared for this app",
    val noUsage: String = "The backend has reported no usage yet. Tokens and cost appear here once an answer carries them.",
    /** The context-usage line: used / limit / percent, and the same without a reported limit; per-answer tokens. */
    val contextLine: String = "Context %s / %s tokens (%s%%)",
    val contextUsed: String = "Context %s tokens",
    val answerTokens: String = "%s in · %s out",
    val noSystem: String = "No standing system prompt.",
    val close: String = "Close",
    /** Attachments: the chip's remove button, the answer image's actions, and their outcomes. */
    val removeAttachment: String = "Remove",
    val saveImage: String = "Save",
    val shareImage: String = "Share",
    val reuseImage: String = "Use as input",
    val savedTo: String = "Saved: %s",
    val saveFailed: String = "Could not save the image",
    val attachFailed: String = "Could not attach: %s",
    /** The Model Catalogue's live search box and its states. */
    val searchModels: String = "Search OpenRouter models: name, id, image, audio…",
    val searchingModels: String = "Searching…",
    val searchOffline: String = "OpenRouter list unreachable: the bundled snapshot is searched",
    val noModelMatches: String = "No model matches",
    val searchResults: String = "OpenRouter search",
    val browseOnly: String = "browse only: not a chat model",
)

/** The icons the page draws, by name; the consumer maps each to its own icon set (or keeps the default glyphs). */
object AiIcons {
    const val ROBOT = "robot"
    const val GLOBE = "globe"
    const val CARET_DOWN = "caret_down"
    const val PLUS_CIRCLE = "plus_circle"
    const val ARROW_UP = "arrow_up"
    val ALL = listOf(ROBOT, GLOBE, CARET_DOWN, PLUS_CIRCLE, ARROW_UP)

    /** The default drawer: one glyph per name, no icon set needed. */
    val glyph: @Composable (name: String, size: Dp, tint: Color, description: String?) -> Unit = { name, size, tint, description ->
        Text(
            when (name) { ROBOT -> "✦"; GLOBE -> "◎"; CARET_DOWN -> "▾"; PLUS_CIRCLE -> "+"; else -> "↑" },
            color = tint, fontSize = size.value.sp,
            modifier = if (description != null) Modifier.semantics { contentDescription = description } else Modifier,
        )
    }
}

/** The default engine box: a single-line field that searches on the IME action. */
val AiDefaultEngineField: @Composable (engine: AiEngine, value: String, onValue: (String) -> Unit, placeholder: String, tag: String, onSearch: () -> Unit) -> Unit =
    { _, value, onValue, placeholder, tag, onSearch ->
        OutlinedTextField(
            value, onValue, Modifier.fillMaxWidth().testTag(tag), singleLine = true,
            placeholder = { Text(placeholder) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        )
    }

/**
 * How the page looks: colours, sizes, words, an icon drawer, and the engine box (the consumer's own
 * search field; [engineField] gets the box's tag and must apply it). Both have defaults, so an app with
 * no engines and no icon set hands in colours only.
 */
@Immutable
data class AiTheme(
    val colors: AiColors,
    val metrics: AiMetrics = AiMetrics(),
    val type: AiType = AiType(),
    val strings: AiStrings = AiStrings(),
    val icon: @Composable (name: String, size: Dp, tint: Color, description: String?) -> Unit = AiIcons.glyph,
    val engineField: @Composable (engine: AiEngine, value: String, onValue: (String) -> Unit, placeholder: String, tag: String, onSearch: () -> Unit) -> Unit = AiDefaultEngineField,
    /** True (the default, as in Cloud Code): the assistant's answers are drawn as Markdown ([AiMarkdownText]); false keeps plain text. */
    val markdown: Boolean = true,
)

/** The page in the app's Material colour scheme (Cloud Drive, Cloud Tools Small LLMs): no tokens of its own. */
@Composable
fun materialAiTheme(strings: AiStrings = AiStrings()): AiTheme {
    val c = MaterialTheme.colorScheme
    return AiTheme(
        colors = AiColors(
            text = c.onSurface, text2 = c.onSurfaceVariant, accent = c.primary, negative = c.error,
            field = c.surfaceVariant, tile = c.surfaceVariant, tileBorder = c.outlineVariant, chatBar = c.surfaceVariant,
            glassBorder = c.outlineVariant, userBubble = c.primaryContainer, onUserBubble = c.onPrimaryContainer,
            onAi = c.onPrimary, ai = SolidColor(c.primary),
        ),
        strings = strings,
    )
}


/**
 * The AI page, Cloud Code's: until the chat has a message the greeting ([suggestions] and one box per [engines] entry
 * under it); then the conversation in the same place, each answer streaming in as Markdown. At the bottom the consent
 * card for a mutating tool, the app's [aboveInput], then the composer: the input with the round send button, and under it
 * the icon row (Task, Agent, Model, Permission Mode, MCPs) of the controls the app enables in assets/ai/composer.json;
 * an app with no such file keeps Agent and Model. The Model button opens the shared Model Catalogue and the Task button the Task Catalogue (AiCataloguePages); [onCatalogue] swaps in the host's own model page.
 * [sessionsList]: a Sessions dropdown on top, over [AiHost.sessions]; [header] is the app's own chips on that line.
 * Sizes come from the shared design (assets/ai/ai-design.json); colours from [theme].
 */
@Composable
fun AiChatPage(
    chat: AiChatState,
    theme: AiTheme,
    modifier: Modifier = Modifier,
    engines: List<AiEngine> = emptyList(),
    query: (AiEngine, String) -> String? = { _, _ -> null },
    onCatalogue: (() -> Unit)? = null,
    /** The host's network door for the Model Catalogue's prices (OpenRouter's public lists, no key); default: a plain HTTPS GET. */
    catalogueFetch: CatalogueFetch = AiPublicFetch,
    sessionsList: Boolean = false,
    /** Suggested prompts under the greeting (a persona chat's): a tap fills the input, nothing is sent until the user does. */
    suggestions: List<String> = emptyList(),
    /** The app's composer declaration; null = the served composer dataset (#1547), else Agent and Model. */
    composer: AiComposerDecl? = null,
    /** Answers the (+) menu's Attach items by id (files, images, notes, drive); null hides Attach. */
    onAttach: ((String) -> Unit)? = null,
    /** The built-in Attach items (Files, Image, Camera) of the (+) menu, with the app's permission hooks (libs:perms); null = none. */
    attach: AiAttachHost? = null,
    /** The app's own controls just above the input. */
    aboveInput: @Composable ColumnScope.() -> Unit = {},
    /** The app's own chips on the top line (Cloud Search's Sessions). */
    header: @Composable RowScope.() -> Unit = {},
) {
    val ctx = LocalContext.current
    val design = remember { AiDesign.load(ctx) }
    // #1547 the app's composer declaration is a served dataset: the default (Agent, Model) shows while it loads and whenever it is not served.
    val decl by produceState(composer ?: AiComposerDecl("", listOf("agent", "model")), composer, ctx) {
        if (composer != null) value = composer else AiComposerDecl.observe(ctx).collect { if (it != null) value = it }
    }
    val m = theme.metrics
    val scope = rememberCoroutineScope()
    LaunchedEffect(decl) {
        if (chat.models.isEmpty()) chat.models = withContext(Dispatchers.IO) { runCatching { chat.host().models() }.getOrDefault(emptyList()) }
        if ("task" in decl.controls && chat.tasks.isEmpty()) chat.tasks = runCatching { AiCatalogue.load(ctx).tasks }.getOrDefault(emptyList()).ifEmpty { AiDesign.loadTasks(ctx) }
        if ("task" in decl.controls && chat.task.isEmpty() && decl.defaultTask.isNotEmpty()) chat.chooseTask(decl.defaultTask)
    }
    if (chat.page == AiChatState.PAGE_MODELS) { AiModelCatalogue(chat, theme, catalogueFetch, modifier); return }
    if (chat.page == AiChatState.PAGE_TASKS) { AiTaskCatalogue(chat, theme, decl, modifier); return }
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = m.gutter), verticalAlignment = Alignment.CenterVertically) {
            header()
            if (sessionsList) SessionsSelect(chat, theme)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (chat.session.messages.isEmpty() && !chat.sending) Welcome(engines, query, chat, theme, design, suggestions)
            else Conversation(chat, theme, design)
        }
        chat.consent?.let { req ->
            AiConsentCard(req, onAllow = { chat.decide(true) }, onDeny = { chat.decide(false) }, theme = theme, modifier = Modifier.padding(horizontal = m.small))
        }
        aboveInput()
        Composer(
            chat, theme, design, decl, onCatalogue, onAttach, attach,
            onSend = {
                val ask = chat.draft.trim()
                if ((ask.isNotEmpty() || chat.attachments.isNotEmpty()) && !chat.sending) {
                    chat.draft = ""
                    scope.launch { chat.send(ask) }
                }
            },
        )
    }
}

/**
 * The AI settings controls with no conversation: the very Agent and Model pickers the composer draws (so a choice made here
 * is the choice the chat uses) over the process's [chat]. A host's Configs page draws this and its own words around it.
 */
@Composable
fun AiSettingsPickers(chat: AiChatState, theme: AiTheme, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val design = remember { AiDesign.load(ctx) }
    AiComposerBar(chat, theme, design, AiComposerDecl("settings", listOf("agent", "model")), modifier)
}

/**
 * The consent card: the exact action in words, Allow and Deny. It is the ONE place a waiting action is decided:
 * nothing runs until Allow, and Deny tells the model "denied". [allowTag]/[denyTag]/[tag] let a host keep its own test tags.
 */
@Composable
fun AiConsentCard(
    request: AiConsentRequest,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
    theme: AiTheme,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tag: String = AiTags.CONSENT,
    allowTag: String = AiTags.CONSENT_ALLOW,
    denyTag: String = AiTags.CONSENT_DENY,
) {
    AiConsentCard(request.sentence, onAllow, onDeny, theme, modifier, enabled, tag, allowTag, denyTag)
}

/** The same card for a host that holds its own pending action (Cloud Browser's side panel): just the [sentence]. */
@Composable
fun AiConsentCard(
    sentence: String,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
    theme: AiTheme,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tag: String = AiTags.CONSENT,
    allowTag: String = AiTags.CONSENT_ALLOW,
    denyTag: String = AiTags.CONSENT_DENY,
) {
    val c = theme.colors
    val m = theme.metrics
    val shape = RoundedCornerShape(m.cardRadius)
    Column(
        modifier.fillMaxWidth().clip(shape).background(c.field).border(m.hairline, c.tileBorder, shape).padding(m.cardPad + m.small).testTag(tag),
        verticalArrangement = Arrangement.spacedBy(m.gap),
    ) {
        Text(theme.strings.consentTitle, color = c.text, style = theme.type.style(theme.type.chat, FontWeight.SemiBold))
        Text(sentence, color = c.text, style = theme.type.style(theme.type.chat))
        Row(horizontalArrangement = Arrangement.spacedBy(m.gap)) {
            OutlinedButton(onDeny, Modifier.testTag(denyTag), enabled = enabled) { Text(theme.strings.deny, color = c.text) }
            Button(onAllow, Modifier.testTag(allowTag), enabled = enabled, colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAi)) {
                Text(theme.strings.allow)
            }
        }
    }
}

/** The empty chat: Cloud Code's greeting with the "agent · model" line under it, then the app's suggestions and engine boxes; it gives way once a message is sent. */
@Composable
private fun Welcome(engines: List<AiEngine>, query: (AiEngine, String) -> String?, chat: AiChatState, theme: AiTheme, design: AiDesign, suggestions: List<String>) {
    val c = theme.colors
    val m = theme.metrics
    val s = theme.strings
    val host = chat.host()
    val agentLabel = host.agents().firstOrNull { it.id == chat.agent }?.label
    val sub = s.helloSub.ifBlank { (agentLabel ?: s.noAgentLine) + " · " + chat.model.substringAfterLast('/') }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = m.gutter, vertical = m.gutter),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(m.cardPad),
    ) {
        Text(s.hello, color = c.text, style = theme.type.style(design.metric("hello_font").sp, FontWeight.SemiBold), textAlign = TextAlign.Center)
        Text(sub, color = c.text2, style = theme.type.style((design.metric("meta_font") + 1f).sp), textAlign = TextAlign.Center)
        suggestions.forEach { q ->
            Text(
                q, color = c.text, style = theme.type.style(design.metric("chat_font").sp),
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(m.cardRadius)).background(c.field)
                    .clickable { chat.draft = q }.padding(m.cardPad).testTag(AiTags.suggestion(q)),
            )
        }
        // One box per declared engine (Cloud Search), in declared order.
        if (engines.isNotEmpty()) Column(Modifier.padding(top = m.gap), verticalArrangement = Arrangement.spacedBy(m.engineGap)) {
            engines.forEachIndexed { i, e ->
                var q by remember(e.id) { mutableStateOf("") }
                theme.engineField(
                    e, q, { q = it },
                    if (i == 0) s.searchTheWeb else s.searchWith.format(e.label),
                    AiTags.engine(e.id),
                ) { if (q.isNotBlank()) { query(e, q.trim())?.let(host::openUrl); q = "" } }
            }
        }
    }
}

@Composable
private fun Conversation(chat: AiChatState, theme: AiTheme, design: AiDesign) {
    val c = theme.colors
    val m = theme.metrics
    val list = rememberLazyListState()
    LaunchedEffect(chat.session.messages.size, chat.sending, chat.streaming.length) {
        val n = chat.session.messages.size + (if (chat.sending) 1 else 0)
        if (n > 0) list.animateScrollToItem(n - 1)
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = maxWidth * (design.metric("assistant_width_pct") / 100f)
        LazyColumn(
            Modifier.fillMaxSize(), state = list,
            contentPadding = PaddingValues(horizontal = m.gutter, vertical = design.metric("list_pad_v").dp),
            verticalArrangement = Arrangement.spacedBy(design.metric("list_gap").dp),
        ) {
            items(chat.session.messages.withIndex().toList(), key = { it.index }) { (_, msg) -> Bubble(chat, msg, width, theme, design) }
            if (chat.sending) {
                // The answer as it streams in; until the first piece, the thinking line.
                if (chat.streaming.isNotEmpty()) item { Bubble(chat, AiMsg("assistant", chat.streaming), width, theme, design) }
                else item { Thinking(if (chat.web) theme.strings.searchingWeb else theme.strings.thinking, theme, design) }
            }
            if (chat.citations.isNotEmpty()) item {
                Column(verticalArrangement = Arrangement.spacedBy(m.small)) {
                    Text(theme.strings.sources.uppercase(), color = c.text2, style = theme.type.style(theme.type.label, FontWeight.Bold))
                    chat.citations.forEach { u ->
                        Row(Modifier.clickable { chat.host().openUrl(u) }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.small)) {
                            theme.icon(AiIcons.GLOBE, m.iconSm, c.accent, null)
                            Text(u, color = c.accent, style = theme.type.style(theme.type.small), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            chat.error?.let { e -> item { Text(theme.strings.chatError.format(e), color = c.negative, style = theme.type.style(theme.type.small)) } }
        }
    }
}

/** User: a bubble on the right, its bottom-right corner small. Assistant: flat Markdown at the page's width with the "agent · model" line under it. */
@Composable
private fun Bubble(chat: AiChatState, msg: AiMsg, max: Dp, theme: AiTheme, design: AiDesign) {
    val c = theme.colors
    val r = design.metric("user_radius_top").dp
    val chatSp = design.metric("chat_font").sp
    if (msg.role == "user") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Column(Modifier.widthIn(max = max), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(theme.metrics.small)) {
                AttachmentList(chat, theme, msg.attachments, actions = false)
                if (msg.content.isNotBlank()) Text(
                    msg.content, color = c.onUserBubble,
                    style = TextStyle(fontSize = chatSp, lineHeight = chatSp * design.metric("chat_line_height")),
                    modifier = Modifier.clip(RoundedCornerShape(r, r, design.metric("user_radius_tail").dp, r))
                        .background(c.userBubble).padding(horizontal = design.metric("user_pad_h").dp, vertical = design.metric("user_pad_v").dp),
                )
            }
        }
    } else {
        Column(Modifier.widthIn(max = max).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(theme.metrics.tiny)) {
            if (theme.markdown) AiMarkdownText(msg.content, theme, Modifier.fillMaxWidth(), design.metric("chat_font").sp, design.metric("chat_line_height"))
            else Text(msg.content, color = c.text, style = TextStyle(fontSize = chatSp, lineHeight = chatSp * design.metric("chat_line_height")))
            AttachmentList(chat, theme, msg.attachments, actions = true)
            val line = listOf(msg.meta, AiContext.answer(msg, theme.strings.answerTokens)).filter { it.isNotBlank() }.joinToString(" · ")
            if (line.isNotBlank()) Text(line, color = c.text2, fontSize = design.metric("meta_font").sp)
        }
    }
}

/** While an answer is on its way: "Searching the web…" (Web on) or "Thinking…". */
@Composable
private fun Thinking(label: String, theme: AiTheme, design: AiDesign) {
    Text(label, color = theme.colors.accent, style = theme.type.style(design.metric("chat_font").sp, FontWeight.Medium))
}

/** The Sessions dropdown: the host's stored sessions, newest first; tapping one opens it in the chat. */
@Composable
private fun SessionsSelect(chat: AiChatState, theme: AiTheme) {
    val c = theme.colors
    val m = theme.metrics
    val t = theme.type
    var open by remember { mutableStateOf(false) }
    var all by remember { mutableStateOf<List<AiSession>>(emptyList()) }
    val shape = RoundedCornerShape(m.cardRadius)
    Box(Modifier.padding(end = m.small)) {
        Text(theme.strings.sessions, color = c.text, style = t.style(t.label, FontWeight.Medium),
            modifier = Modifier.clip(shape).background(c.field).border(m.hairline, c.tileBorder, shape)
                .clickable { all = chat.host().sessions(); open = true }.testTag(AiTags.SESSIONS).padding(horizontal = m.cardPad, vertical = m.gap))
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (all.isEmpty()) DropdownMenuItem(text = { Text(theme.strings.noSessions, style = t.style(t.small)) }, onClick = { open = false })
            all.forEach { s ->
                DropdownMenuItem(
                    text = { Text(s.title.ifBlank { theme.strings.untitled }, style = t.style(t.small), maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (s.id == chat.session.id) c.accent else c.text) },
                    onClick = { chat.open(s); open = false },
                    modifier = Modifier.testTag(AiTags.session(s.id)),
                )
            }
        }
    }
}


/**
 * Cloud Code's composer: a hairline on top, the input (a pill that grows to the design's max height) with the round send
 * button, then the icon row of the controls the app enabled.
 */
@Composable
private fun Composer(chat: AiChatState, theme: AiTheme, design: AiDesign, decl: AiComposerDecl, onCatalogue: (() -> Unit)?, onAttach: ((String) -> Unit)?, attach: AiAttachHost?, onSend: () -> Unit) {
    val c = theme.colors
    val m = theme.metrics
    val inputShape = RoundedCornerShape(design.metric("input_radius").dp)
    Column(
        Modifier.fillMaxWidth()
            .drawBehind { drawLine(c.tileBorder, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = m.hairline.toPx()) }
            .padding(top = design.metric("composer_pad_top").dp, start = design.metric("composer_pad_h").dp, end = design.metric("composer_pad_h").dp, bottom = design.metric("composer_pad_bottom").dp),
        verticalArrangement = Arrangement.spacedBy(design.metric("ctl_gap").dp),
    ) {
        AttachChips(chat, theme, design)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(design.metric("row_gap").dp)) {
            BasicTextField(
                value = chat.draft, onValueChange = { chat.draft = it }, minLines = 1, maxLines = 6,
                textStyle = TextStyle(color = c.text, fontSize = design.metric("chat_font").sp),
                cursorBrush = SolidColor(c.accent),
                modifier = Modifier.weight(1f).heightIn(max = design.metric("input_max_height").dp).clip(inputShape).background(c.field)
                    .border(m.hairline, c.tileBorder, inputShape).testTag(AiTags.CHAT_INPUT),
                decorationBox = { inner ->
                    Box(Modifier.padding(horizontal = design.metric("input_pad_h").dp, vertical = design.metric("input_pad_v").dp)) {
                        if (chat.draft.isEmpty()) Text(theme.strings.askAnything, color = c.text2, style = TextStyle(fontSize = design.metric("chat_font").sp))
                        inner()
                    }
                },
            )
            Box(
                Modifier.size(design.metric("send_size").dp).clip(CircleShape).background(c.ai)
                    .clickable(enabled = !chat.sending, onClick = onSend).testTag(AiTags.CHAT_SEND),
                contentAlignment = Alignment.Center,
            ) { theme.icon(AiIcons.ARROW_UP, m.icon, c.onAi, theme.strings.send) }
        }
        AiContext.line(chat.session, chat.models.firstOrNull { it.id == chat.model }?.context ?: 0, theme.strings)?.let {
            Text(it, color = c.text2, fontSize = design.metric("meta_font").sp, modifier = Modifier.testTag(AiTags.CONTEXT))
        }
        AiComposerBar(chat, theme, design, decl, onCatalogue = onCatalogue, onAttach = onAttach, attach = attach)
    }
}

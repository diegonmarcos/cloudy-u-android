package com.diegonmarcos.cloudsuperintel.ui

import com.diegonmarcos.cloudsuperintel.SiOpen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.diegonmarcos.cloudsuperintel.AiData
import com.diegonmarcos.cloudsuperintel.BuildConfig
import com.diegonmarcos.cloudsuperintel.Decl
import com.diegonmarcos.cloudsuperintel.R
import com.diegonmarcos.cloudsuperintel.SiAi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.diegonmarcos.superapp.ai.AiAttachHost
import com.diegonmarcos.superapp.ai.AiChatPage
import com.diegonmarcos.superapp.ai.AiForumChat
import com.diegonmarcos.superapp.ai.AiCatalogue
import com.diegonmarcos.superapp.ai.AiChatState
import com.diegonmarcos.superapp.ai.AiComposerDecl
import com.diegonmarcos.superapp.ai.AiDesign
import com.diegonmarcos.superapp.ai.AiModelCatalogue
import com.diegonmarcos.superapp.ai.AiTaskCatalogue
import com.diegonmarcos.superapp.ai.ForumSessions
import com.diegonmarcos.superapp.ai.ForumTools
import androidx.compose.runtime.mutableIntStateOf
import com.diegonmarcos.superapp.ai.ForumIcon
import com.diegonmarcos.superapp.ai.ForumStrings
import com.diegonmarcos.superapp.ai.ForumTopic
import com.diegonmarcos.superapp.ai.AiSessionGroups
import com.diegonmarcos.superapp.ai.AiSessionsPanel
import com.diegonmarcos.superapp.ai.AiSettingsPickers
import com.diegonmarcos.superapp.ai.AiStrings
import com.diegonmarcos.superapp.ai.AiTheme
import com.diegonmarcos.superapp.ai.materialAiTheme
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Mood
import androidx.compose.material.icons.outlined.Paid
import androidx.compose.material.icons.outlined.DataUsage
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Send
import androidx.compose.ui.res.colorResource
import com.diegonmarcos.cloudsuperintel.Persona
import com.diegonmarcos.superapp.bottomnav.TopBarDrawer
import com.diegonmarcos.superapp.bottomnav.rememberAccountLauncher
import com.diegonmarcos.superapp.bottomnav.islandEntries
import com.diegonmarcos.superapp.navbarbottom.NavBarBottomHost
import com.diegonmarcos.superapp.navbartop1.NavBarTopFirstRowScaffold
import com.diegonmarcos.superapp.navbartop1.titleHierarchy
import com.diegonmarcos.superapp.navbartop2.NavSectionLayout
import com.diegonmarcos.superapp.perms.PermsFixChip
import com.diegonmarcos.superapp.perms.rememberPermsMissing
import com.diegonmarcos.superapp.themebg.themeBackground

/** The test tags the shell is driven by. */
object SiTags {
    const val SHELL = "si_shell"
    const val CHAT = "si_chat"
    const val SECOND_ROW = "si_second_row"
    const val PAGE_TEXT = "si_page_text"
}

/** The chat page's look: the fleet AI default (Material colours) with Markdown answers on, in this app's words. */
@Composable
fun siAiTheme(): AiTheme = materialAiTheme(
    AiStrings(
        title = stringResource(R.string.app_name),
        hello = stringResource(R.string.chat_hello),
        helloSub = stringResource(R.string.chat_hello_sub),
        askAnything = stringResource(R.string.chat_hint),
        send = stringResource(R.string.chat_send),
        chatError = stringResource(R.string.chat_failed),
    ),
).copy(markdown = true)

/**
 * The shell: the shared first row (Profile at the top right opens Cloud Account; the hamburger opens the sessions
 * drawer), the second row with the page icons, the bottom nav, the shared page gradient and the shared scroll collapse,
 * all from libs (#1154, #1157). Which sections and pages exist is build.json::ui, read through [Decl.nav].
 */
@Composable
fun SiShell() {
    val ctx = LocalContext.current.applicationContext
    val personas = remember { SiAi.personas(ctx) }
    val siDefault = remember { SiAi.defaultId(ctx) }
    val notebooks = remember { NotebookModel(ctx) }
    val nav = Decl.nav
    val aiPages = remember { AiData.pages(AiData.asset(ctx, AiData.PAGES)) }
    var section by rememberSaveable { mutableStateOf(nav.default()?.id ?: Decl.SECTION_SI) }
    var page by rememberSaveable { mutableStateOf<String?>(null) }
    // #1630 Settings (the app's Configs) ends with the fleet-default tail: {own tabs} | Agents, Perms, Logcat, X-Ray App, About.
    val sec = nav.section(section)?.let { s ->
        if (s.id == Decl.SECTION_SETTINGS) s.copy(pages = com.diegonmarcos.superapp.fleetconfigs.FleetConfigsExtensions.withTail(s.pages)) else s
    }
    val pageId = page?.takeIf { p -> sec?.pages?.any { it.id == p } == true } ?: sec?.pages?.firstOrNull()?.id
    val theme = siAiTheme()
    // The chat the drawer, the Models page and the sessions follow: the open persona's, else the router's.
    val persona = personas.firstOrNull { it.id == pageId && (sec?.id == Decl.SECTION_SI || sec?.id == Decl.SECTION_CGCS) }
    // The SI tab's thread persona (the topic strip) is hoisted here so the drawer, the Tools pages and the sessions follow it.
    var forumPersona by rememberSaveable { mutableStateOf(siDefault) }
    val chatPersona = if (sec?.id == Decl.SECTION_SI) forumPersona else persona?.id
    val chat = remember(chatPersona) { SiAi.chat(ctx, chatPersona) }
    // The composer's declaration (assets/ai/composer.json): (+) attach, Task, Specialist, Agent, Model, Effort, Mode, Plugins-Skills, KG-Store, KG-Cloud-apps, KG-Repos, KG-Cloud-infra, Plugins-Perf, More.
    val composerDecl = remember { runCatching { AiComposerDecl.load(ctx) }.getOrDefault(AiComposerDecl("cloud-superintel", listOf("plus", "task", "specialist", "agent", "model", "effort", "mode", "skills", "kg_store", "kg_apps", "kg_repos", "kg_infra", "perf", "more"))) }
    // The AI Tools pages need the model list and the task list the chat page would have loaded.
    if (sec?.id == Decl.SECTION_TOOLS) LaunchedEffect(chat) {
        if (chat.models.isEmpty()) chat.models = withContext(Dispatchers.IO) { runCatching { chat.host().models() }.getOrDefault(emptyList()) }
        if (chat.tasks.isEmpty()) chat.tasks = withContext(Dispatchers.IO) { runCatching { AiCatalogue.load(ctx).tasks }.getOrDefault(emptyList()).ifEmpty { AiDesign.loadTasks(ctx) } }
    }
    val entries = nav.islandEntries()
    // The (+) menu's Files, Image and Camera: their Perms rows are libs:perms' (build.json::perms.needs), shown as "Fix in Perms" while missing.
    val attachHost = remember { AiAttachHost(missing = { ids -> rememberPermsMissing(ids).map { it.id } }, fix = { ids -> PermsFixChip(ids, ATTACH_FEATURE) }) }
    fun open(sectionId: String, pageId: String?) { section = sectionId; page = pageId }
    // #1543 a deep link (the SuperApp AI badge -> Cost): opened once, only when the nav declares that section.
    val asked = SiOpen.pending
    androidx.compose.runtime.LaunchedEffect(asked) {
        if (asked != null) {
            nav.section(asked.section)?.let { s -> open(s.id, asked.page?.takeIf { p -> s.pages.any { it.id == p } }) }
            SiOpen.pending = null
        }
    }

    Box(Modifier.fillMaxSize().themeBackground(dark = true).windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout))) {
        NavBarTopFirstRowScaffold(
            title = sec?.label ?: stringResource(R.string.app_name),
            hierarchy = sec?.let { titleHierarchy(it, pageId) }.orEmpty(),
            onNavigate = { e -> open(e.sectionId, if (e.depth == 0) null else e.id) },
            onAccount = rememberAccountLauncher(),
            onConfigs = { open(Decl.SECTION_SETTINGS, null) },
            onAi = { open(Decl.SECTION_SI, siDefault) },
            dark = true,
        ) {
            NavBarBottomHost(
                entries = entries,
                selectedId = section,
                onSelect = { open(it.id, null) },
                modifier = Modifier.testTag(SiTags.SHELL),
            ) {
                // The hamburger's left drawer: new chat, then the sessions (open, rename, delete).
                TopBarDrawer { close -> AiSessionsPanel(chat, theme, onPicked = { if (persona == null) open(Decl.SECTION_SI, siDefault); close() }) }
                val pageBody: @Composable () -> Unit = {
                    Box(Modifier.fillMaxSize()) {
                        when (sec?.id) {
                            Decl.SECTION_NOTEBOOK -> NotebookPage(notebooks, pageId.orEmpty(), theme, { page = it }, NotebookIds(Decl.PAGE_NB_NOTEBOOKS, Decl.PAGE_NB_SOURCES, Decl.PAGE_NB_CHAT, Decl.PAGE_NB_STUDIO))
                            // SI (the router tab) is the chat surface: the left rail is the user's chat SESSIONS, the personas are a
                            // strip under the header (the router is "General"), and the composer carries the full AI tooling
                            // ((+) attach, Task, Agent, Model, Effort, Mode, MCP, More; model catalogue; image in/out).
                            Decl.SECTION_SI -> SiForum(ctx, personas, theme, forumPersona, { forumPersona = it }, composerDecl, attachHost, Modifier.imePadding().testTag(SiTags.CHAT))
                            // Every CGC expert is the same shared single chat page, each over its own persona.
                            Decl.SECTION_CGCS -> persona?.let { p ->
                                LaunchedEffect(p.id) { withContext(Dispatchers.IO) { SiAi.tune(ctx, p, chat) } }
                                AiChatPage(
                                    chat, theme.copy(strings = theme.strings.copy(hello = p.label, helloSub = p.role)),
                                    Modifier.imePadding().testTag(SiTags.CHAT), suggestions = p.suggestions, attach = attachHost,
                                )
                            }
                            // The AIs and Cost pages are data (assets/ai/pages.json, by page id): the screen kind and the asset, no name here.
            else -> if (pageId != null && aiPages[pageId] != null) AiPage(aiPages.getValue(pageId)) else when (pageId) {
                                // The AI Tools section: the model catalogue (tabs, real model lists, OpenRouter search), the agent and model pickers,
                                // the task catalogue and the MCP servers: the tools the chat composer opens, one tap from the bottom nav.
                                Decl.PAGE_TOOLS_MODELS -> AiModelCatalogue(chat, theme, modifier = Modifier.fillMaxSize(), onClose = { open(Decl.SECTION_SI, forumPersona) })
                                Decl.PAGE_TOOLS_PICKERS -> TextPage(R.string.tools_pickers_title, stringResource(R.string.tools_pickers_body)) { AiSettingsPickers(chat, theme) }
                                Decl.PAGE_TOOLS_TASKS -> AiTaskCatalogue(chat, theme, composerDecl, Modifier.fillMaxSize())
                                Decl.PAGE_TOOLS_MCP -> aiPages["mcp"]?.let { AiPage(it) }
                                "perms", "agents", "logcat", "xray" -> com.diegonmarcos.superapp.fleetconfigs.FleetConfigsPageBody(pageId.orEmpty())
                                Decl.PAGE_MODELS -> TextPage(R.string.models_title, stringResource(R.string.models_body)) { AiSettingsPickers(chat, theme) }
                                Decl.PAGE_BACKEND -> TextPage(R.string.backend_title, stringResource(R.string.backend_body) + "\n\n" + stringResource(R.string.backend_gateway, BuildConfig.AI_GATEWAY_URL))
                                Decl.PAGE_ABOUT -> TextPage(R.string.about_title, stringResource(R.string.about_body, BuildConfig.VERSION_NAME, BuildConfig.GIT_SHORT_SHA))
                            }
                        }
                    }
                }
                // The section's child pages in the mode it declares or the user chose in Configs (one row, two rows, side menu).
                if (sec != null && sec.pages.isNotEmpty()) {
                    NavSectionLayout(sec, pageId, onSelect = { page = it.id }, modifier = Modifier.fillMaxSize().testTag(SiTags.SECOND_ROW), content = pageBody)
                } else pageBody()
            }
        }
    }
}

/** The libs:perms feature the attach menu's "Fix in Perms" chip reports. */
private const val ATTACH_FEATURE = "chat"

/**
 * The SI tab as a rich chat: the left rail is the user's chat SESSIONS (the real store, one avatar each, New chat on top), the
 * personas (the router is "General", each CGC expert a topic) are a strip under the styled header, the thread is that persona's
 * libs:ai chat, and the composer carries the full AI tooling (ForumTools). The forum, bubbles, receipts, background and composer
 * are libs:ai's (AiForumChat). #1563 had replaced the chat page's tooling with a bare composer; it is back, and the sessions
 * rail replaces the topics rail.
 */
@Composable
private fun SiForum(
    ctx: android.content.Context, personas: List<Persona>, theme: AiTheme, selected: String, onSelect: (String) -> Unit,
    decl: AiComposerDecl, attachHost: AiAttachHost, modifier: Modifier,
) {
    val topics = remember(personas) {
        personas.map { p ->
            ForumTopic(
                id = p.id,
                label = if (p.router) "General" else p.label,
                badge = if (p.router) "#" else p.label.take(3),
                chat = SiAi.chat(ctx, p.id),
                senderName = p.label,
            )
        }
    }
    val current = personas.firstOrNull { it.id == selected } ?: personas.first()
    val chat = topics.first { it.id == current.id }.chat
    LaunchedEffect(current.id) { withContext(Dispatchers.IO) { SiAi.tune(ctx, current, chat) } }
    var tick by remember { mutableIntStateOf(0) }
    // The hamburger opens the all-chats drawer (every chat, searchable, in named groups that persist).
    val groups = remember { AiSessionGroups.prefs(ctx, "cloud-superintel") }
    val stored = remember(tick, chat.session.id, chat.session.title, chat.session.messages.size, chat.sending) { chat.host().sessions() }
    // The open session is always on the rail, first, until it is stored (a fresh chat has no message yet).
    val list = if (stored.any { it.id == chat.session.id }) stored else listOf(chat.session) + stored
    AiForumChat(
        topics = topics,
        selectedId = current.id,
        onSelect = onSelect,
        theme = theme,
        modifier = modifier,
        strings = forumStrings(),
        icon = siForumIcon,
        adminColor = colorResource(R.color.si_ok),
        onNewTopic = null,
        onCall = {},
        onInfo = { chat.page = AiChatState.PAGE_MODELS },
        onMore = {},
        sessions = ForumSessions(list, chat.session.id, onOpen = { chat.open(it) }, onNew = { chat.fresh(); tick++ }, groups = groups),
        tools = ForumTools(decl, attach = attachHost),
    )
}

/** The forum's words from this app's resources. */
@Composable
private fun forumStrings(): ForumStrings = ForumStrings(
    topics = stringResource(R.string.forum_topics),
    all = stringResource(R.string.forum_all),
    newTopic = stringResource(R.string.forum_new_topic),
    newSession = stringResource(R.string.forum_new_session),
    sessions = stringResource(R.string.forum_sessions),
    untitled = stringResource(R.string.forum_new_session),
    participants = stringResource(R.string.forum_participants),
    admin = stringResource(R.string.forum_admin),
    messages = stringResource(R.string.forum_messages),
    message = stringResource(R.string.forum_message),
    today = stringResource(R.string.forum_today),
    yesterday = stringResource(R.string.forum_yesterday),
    emptyTitle = stringResource(R.string.forum_empty_title),
    emptySub = stringResource(R.string.forum_empty_sub),
    thinking = stringResource(R.string.forum_thinking),
    errorPrefix = stringResource(R.string.chat_failed),
    back = stringResource(R.string.forum_back),
    more = stringResource(R.string.forum_more),
    call = stringResource(R.string.forum_call),
    info = stringResource(R.string.forum_info),
    emoji = stringResource(R.string.forum_emoji),
    attach = stringResource(R.string.forum_attach),
    voice = stringResource(R.string.forum_voice),
    send = stringResource(R.string.chat_send),
)

/** The forum's icon names mapped to Material symbols (material-icons-extended), drawn tinted with a content description. */
private val siForumIcon: ForumIcon = { name, size, tint, description ->
    val vector = when (name) {
        "board", "topics" -> Icons.Outlined.Menu
        "back" -> Icons.Outlined.ChevronLeft
        "more" -> Icons.Outlined.MoreVert
        "call" -> Icons.Outlined.Call
        "info" -> Icons.Outlined.Info
        "emoji" -> Icons.Outlined.Mood
        "attach" -> Icons.Outlined.AttachFile
        "mic" -> Icons.Outlined.Mic
        "send" -> Icons.Outlined.Send
        "new" -> Icons.Outlined.AddCircleOutline
        "menu" -> Icons.Outlined.Menu
        "usage" -> Icons.Outlined.Paid
        "context" -> Icons.Outlined.DataUsage
        "files" -> Icons.Outlined.Folder
        "camera" -> Icons.Outlined.PhotoCamera
        else -> Icons.Outlined.Info
    }
    Icon(imageVector = vector, contentDescription = description, tint = tint, modifier = Modifier.size(size))
}

/** A plain settings page: a title, a paragraph, and the page's own controls under them. */
@Composable
private fun TextPage(title: Int, body: String, extra: @Composable () -> Unit = {}) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp).testTag(SiTags.PAGE_TEXT))
        extra()
    }
}

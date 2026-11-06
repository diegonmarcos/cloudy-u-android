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
import com.diegonmarcos.superapp.ai.ForumIcon
import com.diegonmarcos.superapp.ai.ForumStrings
import com.diegonmarcos.superapp.ai.ForumTopic
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
    val sec = nav.section(section)
    val pageId = page?.takeIf { p -> sec?.pages?.any { it.id == p } == true } ?: sec?.pages?.firstOrNull()?.id
    val theme = siAiTheme()
    // The chat the drawer, the Models page and the sessions follow: the open persona's, else the router's.
    val persona = personas.firstOrNull { it.id == pageId && (sec?.id == Decl.SECTION_SI || sec?.id == Decl.SECTION_CGCS) }
    val chat = remember(persona?.id) { SiAi.chat(ctx, persona?.id) }
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
                            // SI (the router tab) is the forum/topics chat surface: every persona is a topic, the router is
                            // "# General", and the content sits above the unchanged bottom nav (#1563).
                            Decl.SECTION_SI -> SiForum(ctx, personas, theme, Modifier.imePadding().testTag(SiTags.CHAT))
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
 * The SI tab as a forum/topics chat (#1563): the left topics rail is every persona (the router is "# General",
 * each CGC expert a topic), the thread is that topic's libs:ai chat, and the whole surface sits in the content
 * area above the unchanged bottom nav. Selecting a topic switches the thread; the router's preferred model is
 * tuned once. The forum, bubbles, receipts and composer are libs:ai's (AiForumChat).
 */
@Composable
private fun SiForum(ctx: android.content.Context, personas: List<Persona>, theme: AiTheme, modifier: Modifier) {
    val siDefault = remember { SiAi.defaultId(ctx) }
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
    var selected by rememberSaveable { mutableStateOf(siDefault) }
    val current = personas.firstOrNull { it.id == selected } ?: personas.first()
    LaunchedEffect(current.id) { withContext(Dispatchers.IO) { SiAi.tune(ctx, current, topics.first { it.id == current.id }.chat) } }
    AiForumChat(
        topics = topics,
        selectedId = selected,
        onSelect = { selected = it },
        theme = theme,
        modifier = modifier,
        strings = forumStrings(),
        icon = siForumIcon,
        adminColor = colorResource(R.color.si_ok),
        onNewTopic = null,
        onCall = {},
        onInfo = {},
        onMore = {},
    )
}

/** The forum's words from this app's resources. */
@Composable
private fun forumStrings(): ForumStrings = ForumStrings(
    topics = stringResource(R.string.forum_topics),
    all = stringResource(R.string.forum_all),
    newTopic = stringResource(R.string.forum_new_topic),
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

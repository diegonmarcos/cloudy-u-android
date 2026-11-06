package com.diegonmarcos.cloudagenda.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.diegonmarcos.cloudagenda.data.AgentConfig
import com.diegonmarcos.cloudagenda.data.Attachment
import com.diegonmarcos.cloudagenda.data.MockTask
import com.diegonmarcos.cloudagenda.data.MockTasks
import com.diegonmarcos.cloudagenda.data.NotesModel
import com.diegonmarcos.cloudagenda.data.Structure
import com.diegonmarcos.cloudagenda.data.ThemeMode
import com.diegonmarcos.superapp.ai.AiAgent
import com.diegonmarcos.superapp.ai.AiChatState
import com.diegonmarcos.superapp.ai.AiModel
import androidx.compose.ui.res.stringResource
import com.diegonmarcos.cloudagenda.R
import com.diegonmarcos.superapp.fleetconfigs.FleetConfigsPageBody
import com.diegonmarcos.superapp.themebg.ThemeBackground
import com.diegonmarcos.superapp.navbarbottom.NavBarBottomHost
import com.diegonmarcos.superapp.navbartop1.NavBarTopFirstRowScaffold
import com.diegonmarcos.superapp.navbartop1.titleHierarchy
import com.diegonmarcos.superapp.navbartop2.NavBarTopSecondRow
import com.diegonmarcos.superapp.bottomnav.TopBarDecl
import com.diegonmarcos.superapp.bottomnav.TopBarDrawer
import com.diegonmarcos.superapp.bottomnav.rememberAccountLauncher
import com.diegonmarcos.superapp.bottomnav.NavDecl
import com.diegonmarcos.superapp.bottomnav.NavPage
import com.diegonmarcos.superapp.bottomnav.islandEntries
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Everything the shell needs from the app around it. MainActivity supplies the real one; the layout-tree tests a fake. */
interface TasksEnv {
    val controller: TasksController
    val themeMode: ThemeMode
    fun saveThemeMode(mode: ThemeMode)
    val host: TasksHost
    /** A link a system picker returned while the editor was open; the page takes it into the draft once. */
    val pickedLink: Attachment?
    fun consumePicked()
    /** The mock fixture (assets/mock-tasks.json, FAKE) behind the collection pages and the Calendar, Gantt, Kanban and Mindmap views. */
    val mock: List<MockTask>
    /** Cloud Me's own structure for the Inboxes, Backlogs, Library and Projects tabs (data/Structure.kt); the mock items hang under its nodes. */
    val structure: Structure
    /** Inboxes > Notes: the card notes (data/Notes.kt), persisted in the prefs file declared by data/notes/notes.json. */
    val notes: NotesModel
    val chat: AiChatState
    val agents: List<AiAgent>
    fun loadModels(): List<AiModel>
    val secret: String
    val secretAvailable: Boolean
    /** Stores the declaration and its secret, and (re)schedules the worker. */
    fun saveAgent(config: AgentConfig, secret: String)
    /** One pass of the agent, blocking; the result is in the declaration's status afterwards. */
    fun runAgent()
}

private val PageMapSaver = listSaver<androidx.compose.runtime.snapshots.SnapshotStateMap<String, String>, String>(
    save = { m -> m.entries.flatMap { listOf(it.key, it.value) } },
    restore = { l -> mutableStateMapOf<String, String>().also { m -> l.chunked(2).forEach { m[it[0]] = it[1] } } },
)

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/** The app: theme, then the shell. */
@Composable
fun TasksApp(env: TasksEnv, nav: NavDecl = Declarations.nav) {
    var mode by remember { mutableStateOf(env.themeMode) }
    TasksTheme(mode) {
        val dark = LocalDarkTheme.current
        val view = LocalView.current
        SideEffect {
            view.context.activity()?.window?.let { WindowCompat.getInsetsController(it, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            } }
        }
        TasksShell(env, nav, mode, onThemeMode = { mode = it; env.saveThemeMode(it) })
    }
}

/**
 * The shell: the fleet's top bar (libs:bottomnav AppTopBar: hamburger and page-managed drawer, title, AI panel,
 * Settings dropdown), the page, and the fleet's bottom island. Navigation is the declaration (build.json::ui) read
 * through NavDecl: the island is ui.bottom_nav, a section's pages are its tab strip (PageTabs). Settings is a
 * declared section that is not on the island: the top bar's Settings > Configs opens it, Back (or any island item) leaves.
 */
@Composable
fun TasksShell(env: TasksEnv, nav: NavDecl, themeMode: ThemeMode, onThemeMode: (ThemeMode) -> Unit, topBar: TopBarDecl? = Declarations.topBar) {
    val dark = LocalDarkTheme.current

    var section by rememberSaveable { mutableStateOf(nav.default()?.id ?: Ids.TASKS) }
    var returnTo by rememberSaveable { mutableStateOf(nav.default()?.id ?: Ids.TASKS) }
    val pageOf = rememberSaveable(saver = PageMapSaver) { mutableStateMapOf<String, String>() }

    val current = nav.section(section)
    val pages = current?.pages.orEmpty()
    val page = current?.page(pageOf[section])?.id.orEmpty()
    val onIsland = section in nav.bottomNav
    val today = remember { java.time.LocalDate.now() }

    fun go(id: String) { section = id }
    BackHandler(enabled = section == Ids.SETTINGS) { go(returnTo) }

    val content: @Composable () -> Unit = {
        Column(Modifier.fillMaxSize()) {
            if (pages.size > 1) {
                NavBarTopSecondRow(
                    pages = pages, selectedId = page, onSelect = { pageOf[section] = it.id },
                    underTopChrome = false, surface = nav.stripSurface(section, dark, page),
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (section) {
                    // The collection sections list their rows in the Cloud Me Tasks layout (one page each).
                    // Inboxes is Notes | Synced: the card notes, then the synced inbox sources (what Inboxes showed before).
                    Ids.INBOXES -> when (page) {
                        Ids.PAGE_NOTES -> NotesPage(env.notes)
                        Ids.PAGE_SYNCED -> CollectionPage(Ids.INBOXES, env.structure, env.mock, today)
                    }
                    Ids.BACKLOGS -> CollectionPage(Ids.PAGE_BACKLOGS, env.structure, env.mock, today)
                    Ids.LIBRARY -> CollectionPage(Ids.PAGE_LIBRARY, env.structure, env.mock, today)
                    Ids.PROJECTS -> CollectionPage(Ids.PAGE_PROJECTS, env.structure, env.mock, today)
                    Ids.TASKS -> {
                        if (page == Ids.PAGE_LIST) TasksScreen(env.controller, env.host, env.pickedLink, onPickedConsumed = env::consumePicked)
                        else {
                            SectionPagesDrawer(pages, page) { pageOf[section] = it.id }
                            when (page) {
                                Ids.PAGE_CALENDAR -> CalendarView(env.mock + MockTasks.fromSnapshot(env.controller.snapshot, today), today)
                                Ids.PAGE_GANTT -> GanttView(env.mock, today)
                                Ids.PAGE_KANBAN -> KanbanView(env.mock, today)
                                Ids.PAGE_MINDMAP -> MindmapView(env.mock)
                            }
                        }
                    }
                    Ids.SETTINGS -> {
                        SectionPagesDrawer(pages, page) { pageOf[section] = it.id }
                        SettingsContent(env, page, themeMode, onThemeMode) { pageOf[Ids.SETTINGS] = Ids.PAGE_APPEARANCE }
                    }
                }
            }
        }
    }

    NavBarBottomHost(
        entries = nav.islandEntries(),
        selectedId = if (onIsland) section else null,
        onSelect = { entry -> go(entry.id) },
        dark = dark,
    ) {
        Box(Modifier.fillMaxSize().statusBarsPadding()) {
            // #1157 the first row is libs:ui-nav-bars's (Profile top-right launches Cloud Account, the title island lists the section and its pages).
            if (topBar == null) content() else NavBarTopFirstRowScaffold(
                title = stringResource(R.string.app_name),
                hierarchy = current?.let { titleHierarchy(it, page) }.orEmpty(),
                onNavigate = { e -> go(e.sectionId); if (e.depth > 0) pageOf[e.sectionId] = e.id },
                onAccount = rememberAccountLauncher(),
                onConfigs = { if (section != Ids.SETTINGS) returnTo = section; go(Ids.SETTINGS) },
                aiPanel = { AiPanel(env.chat) },
                content = content,
            )
        }
    }
}

/** The drawer of a section with no content of its own: its pages, so the hamburger always has somewhere to go. */
@Composable
private fun SectionPagesDrawer(pages: List<NavPage>, selected: String, onSelect: (NavPage) -> Unit) {
    TopBarDrawer { close ->
        val p = LocalKitPalette.current
        Column(Modifier.verticalScroll(rememberScrollState()).padding(top = 24.dp)) {
            for (pg in pages) {
                Text(
                    pg.label, color = if (pg.id == selected) p.accent else p.textPrimary, style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { onSelect(pg); close() }.padding(horizontal = 20.dp, vertical = 14.dp)
                        .testTag("drawer_page_" + pg.id),
                )
            }
        }
    }
}

@Composable
private fun SettingsContent(env: TasksEnv, page: String, themeMode: ThemeMode, onThemeMode: (ThemeMode) -> Unit, onAppConfigs: () -> Unit) {
    val controller = env.controller
    val dark = LocalDarkTheme.current
    when (page) {
        // #1443 the fleet's shared Configs pages (libs:ui-nav-bars) come first: General | Perms | About.
        Ids.PAGE_GENERAL, Ids.PAGE_PERMS, Ids.PAGE_ABOUT -> Box(Modifier.fillMaxSize().background(ThemeBackground.colors(dark).top).testTag("tasks:configs:page:" + page)) {
            FleetConfigsPageBody(page, dark, onAppConfigs)
        }
        Ids.PAGE_APPEARANCE -> AppearancePage(themeMode, onThemeMode)
        Ids.PAGE_AGENT -> AgentPageHost(env)
        Ids.PAGE_INBOX -> {
            val target = controller.agent.targetListId.takeIf { controller.snapshot.list(it) != null } ?: controller.selectedListId
            InboxPage(
                controller.proposals,
                onApprove = { controller.decide(it.id, true, target.ifBlank { controller.snapshot.lists.firstOrNull()?.id }) },
                onReject = { controller.decide(it.id, false, null) },
            )
        }
        Ids.PAGE_FEEDS -> LegacyWeb(tab = "configs", Modifier.fillMaxSize())
    }
}

@Composable
private fun AgentPageHost(env: TasksEnv) {
    val controller = env.controller
    var models by remember { mutableStateOf(emptyList<AiModel>()) }
    var mailReason by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            models = runCatching { env.loadModels() }.getOrDefault(emptyList())
            mailReason = runCatching { env.host.mail.unavailableReason() }.getOrElse { it.message }
        }
    }
    AgentPage(
        config = controller.agent, snapshot = controller.snapshot, agents = env.agents, models = models, mailReason = mailReason,
        secret = env.secret, secretAvailable = env.secretAvailable, running = running,
        onSave = { cfg, secret -> env.saveAgent(cfg, secret) },
        onRunNow = {
            scope.launch {
                running = true
                withContext(Dispatchers.IO) { runCatching { env.runAgent() } }
                running = false
                controller.refreshAgent()
            }
        },
    )
}

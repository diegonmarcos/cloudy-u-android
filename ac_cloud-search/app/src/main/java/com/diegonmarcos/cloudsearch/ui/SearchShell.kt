package com.diegonmarcos.cloudsearch.ui

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.diegonmarcos.cloudsearch.BuildConfig
import com.diegonmarcos.cloudsearch.R
import com.diegonmarcos.cloudsearch.core.Filters
import com.diegonmarcos.cloudsearch.core.SearchConfig
import com.diegonmarcos.cloudsearch.data.Account
import com.diegonmarcos.cloudsearch.data.Services
import com.diegonmarcos.superapp.bottomnav.NavDecl
import com.diegonmarcos.superapp.bottomnav.PageTabsTags
import com.diegonmarcos.superapp.bottomnav.TopBarDrawer
import com.diegonmarcos.superapp.bottomnav.islandEntries
import com.diegonmarcos.superapp.bottomnav.rememberAccountLauncher
import com.diegonmarcos.superapp.navbarbottom.BottomTags
import com.diegonmarcos.superapp.navbarbottom.NavBarBottomHost
import com.diegonmarcos.superapp.navbartop1.HierarchyEntry
import com.diegonmarcos.superapp.navbartop1.NavBarTopFirstRowScaffold
import com.diegonmarcos.superapp.navbartop1.TopFirstRowTags
import com.diegonmarcos.superapp.navbartop1.titleHierarchy
import com.diegonmarcos.superapp.navbartop2.NavBarTopSecondRow
import com.diegonmarcos.superapp.navbartop2.NavSectionLayout
import com.diegonmarcos.superapp.ai.AiChatState
import com.diegonmarcos.superapp.ai.AiTags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which of the mockup's menus is open over the shell (one at a time, like its closeAllMenus()). */
/** build.json::ui as baked into BuildConfig (#868): the island's items and each vertical's sub-page strip. */
val NAV: NavDecl by lazy {
    NavDecl.fromBuildConfig(BuildConfig.UI_SECTIONS_B64, BuildConfig.UI_BOTTOM_NAV, BuildConfig.UI_DEFAULT_SECTION, BuildConfig.UI_STYLE)
}

/** #1472 the verticals this app shows: the assistant and every page of a section; build.json::ui.not_shown's stay declared for Cloud KG Store only. */
fun appVerticals(cfg: SearchConfig): List<SearchConfig.Vertical> =
    cfg.verticals.filter { v -> v.subpages.any { cfg.subpage(it)?.kind == "assistant" } || NAV.sections.any { s -> s.pages.any { it.id == v.id } } }

enum class Menu { CATEGORIES, FILTERS, SESSIONS, PROFILE }

/** Everything the shell remembers while it is up; the durable half lives in [Services.prefs]. */
class SearchState(val services: Services) {
    val cfg: SearchConfig = services.cfg
    /** The vertical whose page is the assistant (Search › AI Chat); every other vertical is a page of Image, Videos or Media. */
    val chatVertical: String = cfg.verticals.first { v -> v.subpages.any { cfg.subpage(it)?.kind == "assistant" } }.id
    /** #1197 the island's selection: a ui.sections id (the six tabs, or the parked chat and media). */
    var section by mutableStateOf(NAV.default()?.id ?: "chat")
    var agentsPage by mutableStateOf(NAV.section("research")?.page("agenthub")?.pages?.firstOrNull()?.id ?: "agents")
    /** #1197 Research's second row (Reports, Models, Agents). */
    var researchPage by mutableStateOf(NAV.section("research")?.pages?.firstOrNull()?.id ?: "reports")
    /** #1197 the CGC section: one page per index and mode (Cloud CGC and Me CGC, each Semantic and Graph RAG) and what each index remembers. */
    var cgcPage by mutableStateOf(NAV.section("cgc")?.pages?.firstOrNull()?.id ?: "cloud_semantic")
    private val cgcModels = mutableMapOf<String, CgcModel>()
    fun cgc(indexId: String): CgcModel = cgcModels.getOrPut(indexId) { CgcModel(cfg.cgc!!.index(indexId)!!) }
    /**
     * #913b Search's strip: AI Chat (its page id is the assistant vertical's id; where a query from outside lands), then
     * Engines (#1197), Apps, Messages and Code (once the Cloud page).
     */
    var chatPage by mutableStateOf(chatVertical)
    /** The agent whose screen (config, runs, results, drafts) is open on the Agents page, or null for the list. */
    var agentOpen by mutableStateOf<String?>(null)
    /** The report open in the Reports detail view, or null for the list. */
    var reportOpen by mutableStateOf<String?>(null)
    val agentsModel = AgentsModel(this)
    var vertical by mutableStateOf(cfg.defaultVertical)
    var dark by mutableStateOf(services.prefs.dark)
    var city by mutableStateOf(services.prefs.city)
    var menu by mutableStateOf<Menu?>(null)
    /** Saved Items (the menu's own view of every starred result) is showing instead of a vertical. */
    var saved by mutableStateOf(false)
    var busy by mutableStateOf(0)
    private val subpages = mutableStateMapOf<String, String>()
    val filters = mutableStateMapOf<String, Filters>()
    val listings = mutableMapOf<String, ListingModel>()
    val chat = AiChatState(services.ai)

    fun v(): SearchConfig.Vertical = cfg.vertical(vertical) ?: cfg.verticals.first()
    fun subpageOf(v: SearchConfig.Vertical): String = subpages[v.id]?.takeIf { it in v.subpages } ?: v.subpages.first()
    /** #1199 a sub-page's id in the nav declaration is unique per vertical ("jobs-listing"), so the title path cannot resolve to another vertical's page. */
    fun navId(v: SearchConfig.Vertical, sub: String): String = "${v.id}-$sub"
    /** Takes the declared sub-page id or its nav id; the legacy bare id ("listing") still names the same sub-page. */
    fun showSubpage(v: SearchConfig.Vertical, id: String) { subpages[v.id] = id.removePrefix("${v.id}-") }
    fun filtersOf(v: String): Filters = filters[v] ?: Filters()
    fun listing(v: String): ListingModel = listings.getOrPut(v) { ListingModel(services.prefs.lastQuery(v)) }

    /**
     * The island section a vertical lives under: Search (id chat) for the assistant, else the section (Image, Videos, Media) whose strip
     * lists it (build.json::ui.sections; test-search-shell.sh S10 holds every vertical to exactly one of them).
     */
    fun sectionOf(verticalId: String): String =
        if (verticalId == chatVertical) "chat" else verticalSections.firstOrNull { s -> NAV.section(s)?.pages?.any { it.id == verticalId } == true } ?: verticalSections.first()

    /** #1472 the verticals this app shows: the assistant and every page of a section (build.json::ui.not_shown keeps the rest for Cloud KG Store). */
    val shownVerticals: List<SearchConfig.Vertical> = appVerticals(cfg)

    /** The sections whose pages are search verticals: Image, Videos and Media. */
    val verticalSections: List<String> = NAV.sections.map { it.id }.filter { s -> s != "chat" && NAV.section(s)?.pages?.let { p -> p.isNotEmpty() && p.all { cfg.vertical(it.id) != null } } == true }

    /** #1472 the sections whose pages are all sample pages (build.json::sample_pages): Scrappers and 3D Models. */
    val sampleSections: List<String> = NAV.sections.map { it.id }.filter { s -> NAV.section(s)?.pages?.let { p -> p.isNotEmpty() && p.all { SamplePages.page(it.id) != null } } == true }
    /** The page each sample section shows. */
    val samplePage = mutableStateMapOf<String, String>()

    /** The mockup's switchTopic(): a vertical opens on its first subpage, menus close. */
    fun open(id: String) {
        vertical = id; section = sectionOf(id); saved = false; menu = null
        if (id == chatVertical) chatPage = chatVertical
        cfg.vertical(id)?.let { subpages[it.id] = it.subpages.first() }
    }

    /** A tap on an island item (ui.bottom_nav). Image, Videos and Media keep the vertical they were on; Search keeps its page. */
    fun openSection(id: String) {
        saved = false; menu = null
        when (id) {
            "chat" -> { section = id; vertical = chatVertical }
            in verticalSections -> {
                section = id
                if (sectionOf(vertical) != id) vertical = NAV.section(id)?.pages?.firstOrNull()?.id ?: cfg.verticals.first { it.id != chatVertical }.id
            }
            else -> section = id
        }
    }

    /** Opens an agent's screen: Research's Agents page (the deep link a test or a fleet "open" uses). */
    fun openAgent(id: String) {
        openSection("research"); researchPage = "agenthub"; agentsPage = NAV.section("research")?.page("agenthub")?.pages?.firstOrNull()?.id ?: "agents"; agentOpen = id
    }
    /**
     * #937 a query from outside (MainActivity: SuperApp's Search "<text>" in Cloud Search row, a search
     * intent, a share): the Search page (the search vertical, under Chat) opens on a new conversation
     * and the query is sent at once, as if typed into its input. One answer at a time, like the input:
     * a query that arrives while an answer is on its way is sent once that answer is in.
     */
    fun ask(q: String, scope: CoroutineScope) {
        openSection("chat")
        chatPage = chatVertical
        if (chat.sending) {
            scope.launch { snapshotFlow { chat.sending }.first { !it }; ask(q, scope) }
            return
        }
        chat.fresh()
        scope.launch { chat.send(q) }
    }
    fun toggle(m: Menu) { menu = if (menu == m) null else m }
    fun toggleDark() { dark = !dark; services.prefs.dark = dark }
    fun pickCity(id: String) { city = id; services.prefs.city = id; listings.values.forEach { it.stale = true } }
}

val LocalState = staticCompositionLocalOf<SearchState> { error("SearchShell provides the state") }

/** The test tags the Robolectric smoke test drives the shell by. */
object Tags {
    const val SHELL = "search_shell"
    const val WEB_STRIP = "search_web_strip"
    const val CHAT_STRIP = "search_chat_strip"
    /** #1161 the first row is libs:ui-nav-bars's: its tags are the row's own. */
    const val ISLAND = TopFirstRowTags.ISLAND
    const val PROFILE = TopFirstRowTags.PROFILE
    const val CONFIGS = TopFirstRowTags.CONFIGS
    const val PROFILE_MENU = "search_profile_menu"
    const val CATEGORIES = "search_categories"
    const val FILTERS = "search_filters"
    const val SESSIONS_MENU = "search_sessions_menu"
    const val CLOSE_MENU = "search_close_menu"
    const val THEME = "search_theme_toggle"
    const val TOKEN = "search_token_status"
    const val TOKEN_CHECK = "search_token_check"
    const val SEARCH_BOX = "search_box"
    const val MORE_FILTERS = "search_more_filters"
    const val APPLY_FILTERS = "search_apply_filters"
    const val SORT = "search_sort"
    const val CHAT_INPUT = AiTags.CHAT_INPUT
    const val CHAT_SEND = AiTags.CHAT_SEND
    const val DRAWER_NEW = AiTags.DRAWER_NEW
    const val SESSIONS = "search_sessions"
    const val MODEL = AiTags.MODEL
    const val WEB = AiTags.WEB
    const val SAVED = "search_saved"
    /** libs:ui-nav-bars's item tag. */
    fun nav(id: String) = BottomTags.item(id)
    /** A sub-page pill of the page-tab strip. */
    fun subpage(id: String) = PageTabsTags.tab(id)
    fun page(kind: String) = "search_page_$kind"
    fun card(key: String) = "search_card_$key"
    fun output(calc: String, id: String) = "search_out_${calc}_$id"
    fun field(calc: String, id: String) = "search_field_${calc}_$id"
    fun engine(id: String) = AiTags.engine(id)
    fun source(id: String) = "search_source_$id"
    fun drawer(id: String) = "search_drawer_$id"
    fun chip(id: String) = "search_chip_$id"
    fun city(id: String) = "search_city_$id"
    fun option(id: String) = "search_option_$id"
}

@Composable
fun SearchShell(state: SearchState) {
    CompositionLocalProvider(LocalState provides state) {
        SearchTheme(state.dark) {
            Box(Modifier.fillMaxSize().testTag(Tags.SHELL)) {
                // #1161 the shared nav pattern: libs:ui-nav-bars's bar and page background, libs:ui-nav-bars's
                // row (Profile top-right opens Cloud Account), libs:ui-nav-bars's strips, one scroll-collapse.
                NavBarBottomHost(
                    entries = NAV.islandEntries(),
                    selectedId = if (state.saved) null else state.section,
                    onSelect = { state.openSection(it.id) },
                    modifier = Modifier.imePadding(),
                    dark = state.dark,
                ) {
                    Box(Modifier.fillMaxSize().statusBarsPadding()) { Chrome(state, state.v()) }
                }
                Scrim(state.menu != null) { state.menu = null }
                SideMenu(state.menu == Menu.FILTERS, Tags.FILTERS) { FiltersMenu(state) }
                SideMenu(state.menu == Menu.SESSIONS, Tags.SESSIONS_MENU) { SessionsMenu(state) }
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
                    ProfilePopover(state.menu == Menu.PROFILE) { ProfileMenu(state) }
                }
            }
        }
    }
}

/** The first row over the page; the title dropdown lists, after the path, the categories (every vertical) and Saved Items. */
@Composable
private fun Chrome(state: SearchState, v: SearchConfig.Vertical) {
    val g = LocalGlass.current
    val section = NAV.section(state.section)
    val text = when {
        state.busy > 0 || state.chat.sending || state.agentsModel.running != null -> stringResource(R.string.island_busy)
        state.saved -> stringResource(R.string.saved_items)
        state.section == "chat" && state.chatPage != state.chatVertical -> stringResource(R.string.cloud_title)
        state.section == "chat" || state.section in state.verticalSections -> v.title
        else -> section?.label ?: v.title
    }
    val pageId = when {
        state.section == "chat" -> state.chatPage
        state.section == "research" -> state.researchPage
        state.section in state.sampleSections -> state.samplePage[state.section] ?: section?.pages?.firstOrNull()?.id
        state.section == "cgc" -> state.cgcPage
        // #1199 the page ids are unique now, so the path follows the sub-page the state holds, not a strip that may be scrolled out of the list
        state.section in state.verticalSections -> if (v.subpages.size > 1) state.navId(v, state.subpageOf(v)) else state.vertical
        else -> null
    }
    // #1697 Configs (the vertical dots) opens the fleet strip: Profile (what the old Profile popover held: the AI token, theme, city)
    // | Agents, Perms, Logcat, X-Ray App, About, opened on Profile. The popover itself stays one tap away: General > App.
    var fleetConfigsOpen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    androidx.compose.runtime.remember(state) {
        com.diegonmarcos.superapp.fleetconfigs.FleetConfigsExtensions.register(com.diegonmarcos.superapp.fleetconfigs.FleetConfigsTab("profile", "Profile", "person") { _ ->
            androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(Metrics.gutter)) { ProfileMenu(state) }
        })
    }
    if (fleetConfigsOpen) com.diegonmarcos.superapp.fleetconfigs.FleetConfigsScreen(com.diegonmarcos.superapp.fleetconfigs.FleetConfigsPage.General, dark = state.dark, onDismiss = { fleetConfigsOpen = false }, onAppConfigs = { state.toggle(Menu.PROFILE) }, startId = "profile")
    NavBarTopFirstRowScaffold(
        title = text,
        hierarchy = if (state.saved) emptyList() else section?.let { titleHierarchy(it, pageId) }.orEmpty(),
        onNavigate = { e -> navigateTo(state, e) },
        onAccount = rememberAccountLauncher(),
        // Configs (the vertical dots) opens what the old Profile popover held: the AI token, theme, city.
        onConfigs = { fleetConfigsOpen = true },
        // AI opens the assistant page (the Search page under Chat).
        onAi = { state.openSection("chat"); state.chatPage = state.chatVertical },
        dark = state.dark,
    ) {
        TopBarDrawer { close ->
            Column(Modifier.fillMaxWidth().background(g.menu).padding(Metrics.gutter).testTag(Tags.CATEGORIES), verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                Categories(state, close)
            }
        }
        // The page under the chrome: it pads itself a little (Metrics.contentTop / contentBottom).
        if (state.saved) SavedPage()
        else when (state.section) {
            "chat" -> ChatSection(state, v)
            "research" -> ResearchSection(state)
            "cgc" -> CgcSection(state)
            in state.sampleSections -> SampleSection(state, state.section)
            in state.verticalSections -> VerticalsSection(state, state.section, v)
            else -> {
                val sub = state.subpageOf(v)
                key(v.id, sub) { Page(v, state.cfg.subpage(sub)?.kind ?: "") }
            }
        }
    }
}

/** A pick in the title dropdown: the bottom page, or one of its pages (a vertical, a Search, Research or CGC page). */
private fun navigateTo(state: SearchState, e: HierarchyEntry) {
    if (e.depth == 0) { state.openSection(e.sectionId); return }
    if (e.depth > 2) return
    if (e.depth == 2) {
        // a sub-page of a vertical: open the vertical, then the sub-page its unique id names
        val v = state.cfg.verticals.firstOrNull { e.id.startsWith(it.id + "-") && e.sectionId in state.verticalSections } ?: return
        state.open(v.id); state.showSubpage(v, e.id)
        return
    }
    when {
        e.sectionId == "chat" -> { state.openSection("chat"); state.chatPage = e.id }
        e.sectionId == "research" -> { state.openSection("research"); state.researchPage = e.id }
        e.sectionId == "cgc" -> { state.openSection("cgc"); state.cgcPage = e.id }
        e.sectionId in state.sampleSections -> { state.openSection(e.sectionId); state.samplePage[e.sectionId] = e.id }
        e.sectionId in state.verticalSections -> state.open(e.id)
    }
}

/**
 * #1197 Image, Videos and Media: the section's verticals (ui.sections[].pages) in the section's own second row over the selected vertical's page.
 */
@Composable
private fun VerticalsSection(state: SearchState, section: String, v: SearchConfig.Vertical) {
    val decl = NAV.section(section) ?: return
    NavSectionLayout(
        section = decl,
        selectedId = state.vertical,
        onSelect = { state.open(it.id) },
        modifier = Modifier.fillMaxSize().testTag(Tags.WEB_STRIP),
        dark = state.dark,
        surface = NAV.stripSurface(section, state.dark),
    ) {
        Box(Modifier.fillMaxSize()) {
            val sub = state.subpageOf(v)
            key(v.id, sub) { Page(v, state.cfg.subpage(sub)?.kind ?: "") }
        }
    }
}

/**
 * #913b Search (id chat): AI Chat (the assistant vertical, as before: a query from outside lands here), Engines (#1197) and, on the
 * same strip, the fleet searches that were the Cloud page: Apps, Messages and Code (CloudPages.kt).
 */
@Composable
private fun ChatSection(state: SearchState, v: SearchConfig.Vertical) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.testTag(Tags.CHAT_STRIP)) {
            NavBarTopSecondRow(
                pages = NAV.section("chat")?.pages.orEmpty(),
                selectedId = state.chatPage,
                onSelect = { state.chatPage = it.id },
                dark = state.dark,
                underTopChrome = false,
                surface = NAV.stripSurface("chat", state.dark),
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (state.chatPage == state.chatVertical) {
                val sub = state.subpageOf(v)
                key(v.id, sub) { Page(v, state.cfg.subpage(sub)?.kind ?: "") }
            } else if (state.chatPage == "engines") EnginesPage(state)
            else CloudPage(state, state.chatPage)
        }
    }
}

/** The one dispatch from a subpage's declared `kind` to its page. */
@Composable
private fun Page(v: SearchConfig.Vertical, kind: String) {
    Box(Modifier.fillMaxSize().testTag(Tags.page(kind))) {
        when (kind) {
            "listing" -> ListingPage(v)
            "assistant" -> AssistantPage(v)
            else -> Text(stringResource(R.string.no_renderer, kind), Modifier.padding(Metrics.gutter))
        }
    }
}

/** The hamburger menu: Categories (every shown vertical) and Saved Items. */
@Composable
private fun Categories(state: SearchState, close: () -> Unit) {
    val g = LocalGlass.current
    MenuTitle(stringResource(R.string.drawer_title))
    state.shownVerticals.forEach { v ->
        MenuItem(v.title, Tags.drawer(v.id), icon = { DeclaredIcon(v.icon, Metrics.icon, g.text) }) { state.open(v.id); close() }
    }
    Hairline(Modifier.padding(vertical = Metrics.gutter))
    MenuItem(stringResource(R.string.saved_items), Tags.drawer("saved"), icon = { Ph(R.drawable.ph_star, Metrics.icon, g.text) }) {
        state.saved = true
        state.menu = null
        close()
    }
}

/** Extensive Filters: sort, price range, the vertical's declared options. Applied on Apply Filters. */
@Composable
private fun FiltersMenu(state: SearchState) {
    val g = LocalGlass.current
    val v = state.v()
    val f0 = state.filtersOf(v.id)
    var sort by remember(v.id) { mutableStateOf(f0.sort) }
    var min by remember(v.id) { mutableStateOf(f0.min?.let { plain(it) } ?: "") }
    var max by remember(v.id) { mutableStateOf(f0.max?.let { plain(it) } ?: "") }
    var chips by remember(v.id) { mutableStateOf(f0.chips) }
    MenuTitle(stringResource(R.string.extensive_filters), { state.menu = null }, stringResource(R.string.close))
    FieldLabel(stringResource(R.string.sort_by))
    Select(stringResource(sortLabel(sort)), Filters.Sort.entries.map { it to stringResource(sortLabel(it)) }, Tags.SORT) { sort = it }
    FieldLabel(stringResource(R.string.price_range))
    Row(horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        GlassField(min, { min = it }, stringResource(R.string.min), Tags.option("min"), Modifier.weight(1f), icon = null, number = true)
        GlassField(max, { max = it }, stringResource(R.string.max), Tags.option("max"), Modifier.weight(1f), icon = null, number = true)
    }
    Hairline(Modifier.padding(vertical = Metrics.small))
    if (v.chips.isEmpty()) Text(stringResource(R.string.no_advanced_filters), color = g.text2, style = Type.style(Type.small))
    else {
        FieldLabel(stringResource(R.string.more_options))
        v.chips.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(Metrics.chipGap)) {
                pair.forEach { c ->
                    val on = c.id in chips
                    Row(
                        Modifier.weight(1f).clip(RoundedCornerShape(Metrics.tileRadius)).background(g.field)
                            .clickable { chips = if (on) chips - c.id else chips + c.id }.testTag(Tags.option(c.id))
                            .padding(horizontal = Metrics.chipGap),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(on, { chips = if (on) chips - c.id else chips + c.id }, colors = CheckboxDefaults.colors(checkedColor = g.accent, uncheckedColor = g.text2))
                        Text(c.label, color = g.text, style = Type.style(Type.body), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
    Text(
        stringResource(R.string.apply_filters), color = g.onBadge, style = Type.style(Type.menuItem, FontWeight.Bold),
        modifier = Modifier.fillMaxWidth().padding(top = Metrics.gap).clip(RoundedCornerShape(Metrics.tileRadius)).background(g.accent)
            .clickable {
                state.filters[v.id] = f0.copy(sort = sort, min = num(min), max = num(max), chips = chips)
                state.menu = null
            }
            .testTag(Tags.APPLY_FILTERS).padding(vertical = Metrics.menuItemPad),
        textAlign = TextAlign.Center,
    )
}

/** A <select>: the current choice with a caret; the options drop down. */
@Composable
fun <T> Select(current: String, options: List<Pair<T, String>>, tag: String, modifier: Modifier = Modifier, onPick: (T) -> Unit) {
    val g = LocalGlass.current
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Metrics.tileRadius)
    Box(modifier) {
        Row(
            Modifier.clip(shape).background(g.field).border(Metrics.hairline, g.tileBorder, shape)
                .clickable { open = true }.testTag(tag).padding(horizontal = Metrics.gap, vertical = Metrics.chipGap),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Metrics.small),
        ) {
            Text(current, color = g.text, style = Type.style(Type.small), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Ph(R.drawable.ph_caret_down, Metrics.iconXs, g.text2)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (value, label) ->
                DropdownMenuItem(text = { Text(label, style = Type.style(Type.small)) }, onClick = { onPick(value); open = false })
            }
        }
    }
}

/** Profile & settings: where the AI token comes from, the theme, the city, the build. */
@Composable
private fun ProfileMenu(state: SearchState) {
    val g = LocalGlass.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf<String?>(null) }
    Column(Modifier.testTag(Tags.PROFILE_MENU), verticalArrangement = Arrangement.spacedBy(Metrics.small)) {
        Row(Modifier.padding(Metrics.gap), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Metrics.cardPad)) {
            Box(Modifier.size(Metrics.profileAvatar).clip(CircleShape).background(g.field), contentAlignment = Alignment.Center) {
                Ph(R.drawable.ph_user, Metrics.icon, g.accent)
            }
            Column {
                Text(stringResource(R.string.fleet_account), color = g.text, style = Type.style(Type.menuItem, FontWeight.SemiBold))
                Text(stringResource(R.string.version, BuildConfig.GIT_SHORT_SHA), color = g.text2, style = Type.style(Type.label))
            }
        }
        Hairline()
        val box = RoundedCornerShape(Metrics.cardRadius)
        Column(
            Modifier.fillMaxWidth().clip(box).background(g.field).border(Metrics.hairline, g.glassBorder, box).padding(Metrics.cardPad),
            verticalArrangement = Arrangement.spacedBy(Metrics.small),
        ) {
            Text(stringResource(R.string.token_title), color = g.text2, style = Type.style(Type.label, FontWeight.Bold))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                Chip(stringResource(R.string.token_check), Tags.TOKEN_CHECK, icon = R.drawable.ph_key) {
                    token = ctx.getString(R.string.token_checking)
                    scope.launch {
                        val t = withContext(Dispatchers.IO) { Account.token(ctx, state.cfg.ai.accountProvider) }
                        token = if (t.value != null) ctx.getString(R.string.token_present) else ctx.getString(R.string.token_missing, t.why)
                    }
                }
                Text(token ?: "", color = g.text, style = Type.style(Type.label), modifier = Modifier.weight(1f).testTag(Tags.TOKEN))
            }
            Text(stringResource(R.string.token_blurb), color = g.text2, style = Type.style(Type.tiny))
        }
        MenuItem(stringResource(R.string.toggle_theme), Tags.THEME, icon = { Ph(if (state.dark) R.drawable.ph_moon else R.drawable.ph_sun, Metrics.icon, g.text) }) {
            state.toggleDark()
        }
        Row(Modifier.padding(horizontal = Metrics.menuItemPad), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Metrics.cardPad)) {
            Ph(R.drawable.ph_map_pin, Metrics.icon, g.text)
            Text(stringResource(R.string.city), color = g.text, style = Type.style(Type.menuItem))
        }
        ChipRow {
            state.cfg.cities.forEach { c -> Chip(c.label, Tags.city(c.id), selected = c.id == state.city) { state.pickCity(c.id) } }
        }
    }
}

fun sortLabel(s: Filters.Sort): Int = when (s) {
    Filters.Sort.RELEVANCE -> R.string.sort_relevance
    Filters.Sort.NEWEST -> R.string.sort_newest
    Filters.Sort.PRICE_ASC -> R.string.sort_price_asc
    Filters.Sort.PRICE_DESC -> R.string.sort_price_desc
}

/** A typed number, comma or point; blank or unreadable = no bound. */
fun num(s: String): Double? = s.trim().replace(',', '.').toDoubleOrNull()

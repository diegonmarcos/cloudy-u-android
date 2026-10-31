package com.diegonmarcos.superapp.bottomnav

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * THE nav UI declaration of the fleet: which chrome an app wears (Classic or Clean) and whether scrolling hides the bars. Every
 * name, row and size below is a declaration: the nav-shape guard (rule N13) holds `1_cicd/src/data/nav-shape.json::nav_ui` equal to
 * this file, the web shell's generated tokens carry the same sizes, and every app's Configs > UI offers the choice
 * ([NavUiStore], persisted per app in the `nav_ui` preferences).
 *
 *   Classic  the first row (breadcrumb title + AI / Profile / Configs), the second row (the sub-page strip) and the labelled bottom nav.
 *   Clean    the first row is only a hamburger, the second row is GONE, and a LEFT drawer holds what both rows held: the page
 *            name as a dropdown (a URL bar), the AI / Profile / Settings icons, and a dropdown of the child pages. The bottom nav is
 *            compact: icons only, a smaller height ([NavTokens.cleanBarHeight]).
 *
 * Both modes: the first row is transparent ([NavTokens.topRowFill]), and with scroll-hide on a downward scroll hides ALL bars until
 * an upward scroll reveals them again ([ChromeScrollPolicy.Hide]).
 */
public enum class NavUiMode(public val declared: String) {
    Classic("classic"),
    Clean("clean");

    public companion object {
        /** A declared mode; blank is [Classic], anything unknown is null (a declaration problem). */
        public fun of(text: String?): NavUiMode? =
            if (text.isNullOrBlank()) Classic else entries.firstOrNull { it.declared == text.trim() }
    }
}

/** A row of the Clean drawer, top to bottom ([NavUiDecl.CLEAN_DRAWER_ROWS]). */
public enum class DrawerRow(public val declared: String) {
    /** The current page's name as a dropdown that switches between the pages (the bottom nav's sections). */
    PageDropdown("page_dropdown"),
    /** The AI, Profile and Settings icons ([NavQuickAction]). */
    Actions("actions"),
    /** A dropdown selecting the child pages (sub-pages) of the current page. */
    SubPageDropdown("sub_page_dropdown"),
    /**
     * The app's ACTIONS: every launch page ([NavPage.action] non-blank: "new page", "new board"...) of every bottom section, as a
     * labelled list. Drawn only when the app declares `nav_ui.actions = "drawer"` ([NavUiSettings.actionsInDrawer]); the launch
     * pages then leave the bottom nav's strips ([NavUiDecl.stripPages]), like Obsidian mobile's ribbon menu (#1369).
     */
    AppActions("app_actions"),
}

/** Where an app's launch pages live: on the strips of the bottom nav (the default), or in the drawer's [DrawerRow.AppActions] row. */
public enum class NavActionsPlace(public val declared: String) {
    Strip("strip"),
    Drawer("drawer");

    public companion object {
        public fun of(text: String?): NavActionsPlace? =
            if (text.isNullOrBlank()) Strip else entries.firstOrNull { it.declared == text.trim() }
    }
}

/**
 * What the bottom nav holds in Clean mode (`nav_ui.bottom`, #1369): the app's bottom sections (the default), or Obsidian mobile's
 * HISTORY bar, a fixed set of [HistoryBarItem]s (Back, Forward, New, Tabs, Menu) drawn by the SAME NavBarBottom. Classic always
 * shows the sections. An app opts in by declaring it; the bar's look, sizes and scroll-hide stay the lib's.
 */
public enum class NavBottomKind(public val declared: String) {
    Sections("sections"),
    History("history");

    public companion object {
        public fun of(text: String?): NavBottomKind? =
            if (text.isNullOrBlank()) Sections else entries.firstOrNull { it.declared == text.trim() }
    }
}

/** The items of the [NavBottomKind.History] bar, in Obsidian mobile's order; [id] is the item's test tag suffix, [icon] a ui-icons name. */
public enum class HistoryBarItem(public val id: String, public val label: String, public val icon: String) {
    Back("history_back", "Back", "chevron_left"),
    Forward("history_forward", "Forward", "chevron_right"),
    New("history_new", "New", "add_circle"),
    Tabs("history_tabs", "Tabs", "tabs"),
    Menu("history_menu", "Menu", "menu");

    public companion object {
        public fun of(id: String): HistoryBarItem? = entries.firstOrNull { it.id == id }
    }
}

/** The icons of the drawer's [DrawerRow.Actions] row. */
public enum class NavQuickAction(public val declared: String) {
    Ai("ai"),
    Profile("profile"),
    Settings("settings"),
}

/** The user's (or the declaration's) choice: the nav [mode] and whether [scrollHide] is on. */
public data class NavUiSettings(
    val mode: NavUiMode = NavUiMode.Classic,
    val scrollHide: Boolean = true,
    /** `nav_ui.actions`: the launch pages move from the bottom nav's strips to the drawer's [DrawerRow.AppActions] row. */
    val actions: NavActionsPlace = NavActionsPlace.Strip,
    /** `nav_ui.bottom`: the bottom nav holds the sections, or (Clean only) the history bar ([NavBottomKind.History]). */
    val bottom: NavBottomKind = NavBottomKind.Sections,
) {
    public val actionsInDrawer: Boolean get() = actions == NavActionsPlace.Drawer

    /** The history bar replaces the sections on the bottom nav: declared [NavBottomKind.History] AND in Clean mode. */
    public val historyBar: Boolean get() = bottom == NavBottomKind.History && mode == NavUiMode.Clean

    /** How the chrome reacts to a scroll: hide every bar (sticky), or stay put. */
    public fun scrollPolicy(): ChromeScrollPolicy = if (scrollHide) ChromeScrollPolicy.Hide else ChromeScrollPolicy.Pinned
}

/** What a mode draws, as data: the plan the chrome follows and the tests read. */
public data class NavChromePlan(
    val mode: NavUiMode,
    /** The first row has no fill (the app's background shows through): true in every mode. */
    val topRowTransparent: Boolean,
    /** The top-left hamburger that opens the left drawer. */
    val hamburger: Boolean,
    /** The second row (the sub-page strip) is drawn. */
    val secondRow: Boolean,
    /** The rows of the left drawer, top to bottom; empty when there is no drawer. */
    val drawerRows: List<DrawerRow>,
    /** The icons of the drawer's [DrawerRow.Actions] row. */
    val drawerActions: List<NavQuickAction>,
    /** The bottom nav draws a label under each icon. */
    val bottomLabels: Boolean,
)

public object NavUiDecl {
    public const val SETTING_MODE: String = "nav_mode"
    public const val SETTING_SCROLL_HIDE: String = "scroll_hide"
    /** The id every app lists in `build.json::ui.configs_ui` to offer these settings in its Configs > UI. */
    public const val CONFIGS_UI_ID: String = "nav_ui"
    /** The entry every shared first row and the Clean drawer carry: it opens the Configs > UI card. */
    public const val CONFIGS_UI_LABEL: String = "Configs \u25B8 UI"

    /** The Clean drawer, top to bottom: rows 1 and 2 of the Classic chrome moved here, then the child pages. */
    public val CLEAN_DRAWER_ROWS: List<DrawerRow> = listOf(DrawerRow.PageDropdown, DrawerRow.Actions, DrawerRow.SubPageDropdown)
    public val CLEAN_DRAWER_ACTIONS: List<NavQuickAction> = listOf(NavQuickAction.Ai, NavQuickAction.Profile, NavQuickAction.Settings)

    /** The Clean drawer with the app's actions: the [DrawerRow.AppActions] row right after the AI / Profile / Settings icons. */
    public val CLEAN_DRAWER_ROWS_WITH_ACTIONS: List<DrawerRow> =
        listOf(DrawerRow.PageDropdown, DrawerRow.Actions, DrawerRow.AppActions, DrawerRow.SubPageDropdown)

    /**
     * The pages a bottom section's strip shows under [settings]: all of them, or, with the actions in the drawer, all but the launch
     * pages (they are the Clean drawer's [DrawerRow.AppActions] row then). Classic has no such drawer, so it keeps them on the
     * strip. The ONE filter every strip uses.
     */
    public fun stripPages(settings: NavUiSettings, pages: List<NavPage>): List<NavPage> =
        if (!settings.actionsInDrawer || settings.mode != NavUiMode.Clean) pages else pages.filter { it.action.isBlank() }.map { it.copy(pages = stripPages(settings, it.pages)) }

    /** The mode and the scroll-hide an app wears when neither its build.json nor its user chose. */
    public val DEFAULT: NavUiSettings = NavUiSettings(NavUiMode.Classic, scrollHide = true)

    /** What [mode] draws. The first row is transparent in EVERY mode (the fill is [NavTokens.topRowFill]). */
    public fun plan(mode: NavUiMode, tokens: NavTokens = NavTokens()): NavChromePlan {
        val clean = mode == NavUiMode.Clean
        return NavChromePlan(
            mode = mode,
            topRowTransparent = tokens.topRowFill.alpha == 0f,
            hamburger = clean,
            secondRow = !clean,
            drawerRows = if (clean) CLEAN_DRAWER_ROWS else emptyList(),
            drawerActions = if (clean) CLEAN_DRAWER_ACTIONS else emptyList(),
            bottomLabels = !clean,
        )
    }

    /**
     * `build.json::ui.nav_ui` = `{"mode": "clean", "scroll_hide": true}` as an app declares its DEFAULT; a blank or malformed
     * blob is [DEFAULT], never a crash. The user's own choice ([NavUiStore]) wins over it.
     */
    public fun parse(json: String): NavUiSettings = runCatching {
        val o = MiniJson.parse(json) as? Map<*, *> ?: return DEFAULT
        NavUiSettings(
            mode = NavUiMode.of(o["mode"] as? String) ?: DEFAULT.mode,
            scrollHide = (o["scroll_hide"] as? Boolean) ?: DEFAULT.scrollHide,
            actions = NavActionsPlace.of(o["actions"] as? String) ?: DEFAULT.actions,
            bottom = NavBottomKind.of(o["bottom"] as? String) ?: DEFAULT.bottom,
        )
    }.getOrDefault(DEFAULT)

    /** From BuildConfig.UI_NAV_UI_B64 (base64 of the object's JSON). */
    @OptIn(ExperimentalEncodingApi::class)
    public fun fromBuildConfig(uiNavUiB64: String): NavUiSettings = runCatching {
        if (uiNavUiB64.isBlank()) return DEFAULT
        val b64 = Base64.Default.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)
        parse(b64.decode(uiNavUiB64.filterNot { it.isWhitespace() }).decodeToString())
    }.getOrDefault(DEFAULT)
}

/**
 * The user's runtime choice, per app. Null = never chosen (the declaration's default stands). Pure: the Android store
 * ([NavUiPrefs], the `nav_ui` preferences) and the tests implement the same interface.
 */
public interface NavUiStore {
    public fun mode(): NavUiMode?
    public fun setMode(mode: NavUiMode?)
    public fun scrollHide(): Boolean?
    public fun setScrollHide(on: Boolean?)
}

/** An in-memory [NavUiStore] (tests, previews); its reads are Compose state, so a change redraws. */
public class MemoryNavUiStore(mode: NavUiMode? = null, scrollHide: Boolean? = null) : NavUiStore {
    // not named like the accessors: a private `mode` property and `fun setMode` would clash on the JVM
    private var chosenMode: NavUiMode? by mutableStateOf(mode)
    private var chosenScrollHide: Boolean? by mutableStateOf(scrollHide)
    override fun mode(): NavUiMode? = chosenMode
    override fun setMode(mode: NavUiMode?) { chosenMode = mode }
    override fun scrollHide(): Boolean? = chosenScrollHide
    override fun setScrollHide(on: Boolean?) { chosenScrollHide = on }
}

/** The settings in force: the user's choice in [store], else the app's declared [default]. */
public fun resolveNavUi(default: NavUiSettings, store: NavUiStore?): NavUiSettings =
    NavUiSettings(store?.mode() ?: default.mode, store?.scrollHide() ?: default.scrollHide, default.actions, default.bottom)

/** The app's declared default ([NavUiDecl.fromBuildConfig]); an app that declares none wears [NavUiDecl.DEFAULT]. */
public val LocalNavUiDefault = staticCompositionLocalOf { NavUiDecl.DEFAULT }

/** The enclosing store (null: the process's persisted [NavUiPrefs] on Android). */
public val LocalNavUiStore = staticCompositionLocalOf<NavUiStore?> { null }

/** The settings in force below a shared scaffold; null outside one (a bar then draws Classic and the legacy scroll). */
public val LocalNavUiSettings = staticCompositionLocalOf<NavUiSettings?> { null }

// ── the chrome's scroll policy ──

/**
 * How the chrome answers a scroll. [Hide]: a downward scroll or drag hides ALL bars (first row, second row, bottom nav) and they
 * STAY hidden, through the release and the fling, until an upward scroll reveals them. [Pinned]: the bars never move. [Shrink]:
 * the older three-state machine ([chromeScrollOnDelta]): hidden while dragging, shrunk (compact bars) after the finger lifts.
 */
public enum class ChromeScrollPolicy { Hide, Pinned, Shrink }

/** [chromeScrollOnDelta] under [policy] (> 0: reading down). */
public fun chromeScrollStep(policy: ChromeScrollPolicy, state: ChromeScrollState, delta: Float, dragging: Boolean): ChromeScrollState = when (policy) {
    ChromeScrollPolicy.Shrink -> chromeScrollOnDelta(state, delta, dragging)
    ChromeScrollPolicy.Pinned -> ChromeScrollState.Default
    ChromeScrollPolicy.Hide -> when {
        delta < 0f -> ChromeScrollState.Default
        delta > 0f -> ChromeScrollState.Hidden
        else -> state
    }
}

/** [chromeScrollOnRelease] under [policy]: only [ChromeScrollPolicy.Shrink] turns HIDDEN into SHRUNK; [ChromeScrollPolicy.Hide] stays hidden. */
public fun chromeScrollReleaseStep(policy: ChromeScrollPolicy, state: ChromeScrollState): ChromeScrollState = when (policy) {
    ChromeScrollPolicy.Shrink -> chromeScrollOnRelease(state)
    ChromeScrollPolicy.Pinned -> ChromeScrollState.Default
    ChromeScrollPolicy.Hide -> state
}

/** No gesture holds the bars hidden ([ChromeGestureStep.latch]). */
public const val NO_GESTURE: Int = -1

/** [chromeGestureStep]'s result: the new [state], and the gesture that hid the bars ([latch], [NO_GESTURE] = none). */
public data class ChromeGestureStep(val state: ChromeScrollState, val latch: Int)

/**
 * [chromeScrollStep] with PER-GESTURE memory, under [ChromeScrollPolicy.Hide]: the touch that scrolls down and hides the bars
 * ([gesture]) is latched, and NO upward delta of that same gesture brings them back, neither while the finger is held nor in the
 * fling it throws (a fling is part of the gesture that threw it). Only a NEW gesture (finger lifted, then down again: a new
 * [gesture] id) restores them, on its first upward scroll; a new downward gesture keeps them hidden. Other policies are unchanged.
 */
public fun chromeGestureStep(policy: ChromeScrollPolicy, state: ChromeScrollState, delta: Float, dragging: Boolean, gesture: Int, latch: Int): ChromeGestureStep {
    if (policy == ChromeScrollPolicy.Hide && delta < 0f && latch != NO_GESTURE && latch == gesture) return ChromeGestureStep(state, latch)
    val next = chromeScrollStep(policy, state, delta, dragging)
    val held = when {
        policy != ChromeScrollPolicy.Hide || next == ChromeScrollState.Default -> NO_GESTURE
        delta > 0f && next == ChromeScrollState.Hidden -> gesture
        else -> latch
    }
    return ChromeGestureStep(next, held)
}

/** A host's override of the scroll policy (a test); null = the policy of the settings in force. */
public val LocalChromeScrollPolicy = staticCompositionLocalOf<ChromeScrollPolicy?> { null }

// ── the Clean drawer's content, as data ──

/** One entry of a Clean-drawer dropdown. */
public data class CleanDrawerEntry(val id: String, val label: String, val selected: Boolean, val depth: Int = 0)

/** What the Clean drawer shows for the current page: row 1's dropdown, row 2's icons, row 3's dropdown. */
public data class CleanDrawerModel(
    val rows: List<DrawerRow>,
    /** Row 1: the current page's name (the dropdown's face). */
    val pageName: String,
    /** Row 1: every page of the bottom nav, the current one selected. */
    val pages: List<CleanDrawerEntry>,
    /** Row 2. */
    val actions: List<NavQuickAction>,
    /** Row 3: the child pages of the current page, depth-first; empty = the row is not drawn. */
    val subPages: List<CleanDrawerEntry>,
    /** Row 3: the face of the dropdown (the selected child page, else the first). */
    val subPageName: String,
    /** [DrawerRow.AppActions]: every launch page of every section, id `<section>/<page>`; empty = the row is not drawn. */
    val appActions: List<CleanDrawerEntry> = emptyList(),
)

/** A [CleanDrawerModel.appActions] id: `<section>/<page>`. */
public fun appActionId(sectionId: String, pageId: String): String = "$sectionId/$pageId"

/** The (section, page) of an [appActionId]; null for a malformed id. */
public fun appActionTarget(id: String): Pair<String, String>? =
    id.indexOf('/').takeIf { it > 0 && it < id.length - 1 }?.let { id.substring(0, it) to id.substring(it + 1) }

/**
 * The Clean drawer for [section] (the open bottom page) among [bottom] (the bottom nav's sections), with [currentPageId] the open
 * child page. Pure: the drawer's content is testable without drawing. Launch tabs ([NavPage.action]) are not destinations and are left out.
 */
public fun cleanDrawerModel(
    bottom: List<NavSection>,
    section: NavSection?,
    currentPageId: String?,
    /** `nav_ui.actions = "drawer"`: the launch pages of every section become the [DrawerRow.AppActions] row. */
    actionsInDrawer: Boolean = false,
): CleanDrawerModel {
    val pages = bottom.map { CleanDrawerEntry(it.id, it.label, it.id == section?.id) }
    val onPath = section?.path(currentPageId)?.map { it.id }.orEmpty().toSet()
    val subs = ArrayList<CleanDrawerEntry>()
    fun walk(list: List<NavPage>, depth: Int) {
        for (p in list) {
            if (p.action.isBlank()) subs += CleanDrawerEntry(p.id, p.label, p.id in onPath, depth)
            walk(p.pages, depth + 1)
        }
    }
    if (section != null) walk(section.pages, 0)
    val face = subs.lastOrNull { it.selected } ?: subs.firstOrNull()
    val actions = ArrayList<CleanDrawerEntry>()
    if (actionsInDrawer) {
        fun launches(sectionId: String, list: List<NavPage>) {
            for (p in list) {
                if (p.action.isNotBlank()) actions += CleanDrawerEntry(appActionId(sectionId, p.id), p.label, false)
                launches(sectionId, p.pages)
            }
        }
        for (b in bottom) launches(b.id, b.pages)
    }
    val declared = if (actionsInDrawer) NavUiDecl.CLEAN_DRAWER_ROWS_WITH_ACTIONS else NavUiDecl.CLEAN_DRAWER_ROWS
    return CleanDrawerModel(
        rows = declared.filter { (it != DrawerRow.SubPageDropdown || subs.isNotEmpty()) && (it != DrawerRow.AppActions || actions.isNotEmpty()) },
        pageName = section?.label ?: "",
        pages = pages,
        actions = NavUiDecl.CLEAN_DRAWER_ACTIONS,
        subPages = subs,
        subPageName = face?.label ?: "",
        appActions = actions,
    )
}

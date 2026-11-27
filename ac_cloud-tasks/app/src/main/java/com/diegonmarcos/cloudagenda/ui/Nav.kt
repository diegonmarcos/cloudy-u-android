package com.diegonmarcos.cloudagenda.ui

import com.diegonmarcos.cloudagenda.BuildConfig
import com.diegonmarcos.superapp.bottomnav.NavDecl
import com.diegonmarcos.superapp.bottomnav.TopBarDecl

/**
 * The ids Kotlin dispatches on. build.json::ui is the declaration; test/test-tasks-nav.sh diffs this object against
 * it in both directions, so an id declared and never drawn (or drawn and never declared) fails the build.
 */
object Ids {
    const val INBOXES = "inboxes"
    const val TASKS = "tasks"
    /** #1974 the Files section on the island (Backlog's old slot): the RoadMap and the Assets of the backlog repo (Library's content lives under Assets). */
    const val FILES = "files"
    /** #1964 the Clock section on the island (libs:clock-tools FullClock). */
    const val CLOCK = "clock"
    /** #1757 the island's centre: the project selector and the status cards; the former Projects tab is its second page. */
    const val HOME = "home"
    const val SETTINGS = "settings"

    const val PAGE_NOTES = "notes"
    const val PAGE_SYNCED = "synced"
    /** #1974 Tasks' first page (the former Backlog section's page "backlogs"). */
    const val PAGE_BACKLOG = "backlog"
    const val PAGE_ROADMAP = "roadmap"
    const val PAGE_ASSETS = "assets"
    const val PAGE_OVERVIEW = "overview"
    const val PAGE_CALENDAR = "calendar"
    const val PAGE_LIST = "list"
    const val PAGE_GANTT = "gantt"
    const val PAGE_KANBAN = "kanban"
    const val PAGE_MINDMAP = "mindmap"
    /** #1668 the full clock of libs:clock-tools (shared with Cloud Calc and the SuperApp). */
    const val PAGE_CLOCK = "clock"
    const val PAGE_GENERAL = "general"
    const val PAGE_PERMS = "perms"
    const val PAGE_ABOUT = "about"
    const val PAGE_APPEARANCE = "appearance"
    const val PAGE_AGENT = "agent"
    const val PAGE_INBOX = "inbox"
    const val PAGE_FEEDS = "feeds"

    /**
     * #1964/#1974 old routes that still resolve: "section" or "section/page" -> (section, page). build.json::ui.route_aliases declares them.
     * Backlog moved under Tasks, Library's content lives under Files > Assets; the owner-named removals (Home > Projects, Home > Library page) land on Home.
     */
    val ALIASES: Map<String, Pair<String, String>> = mapOf(
        "library" to (FILES to PAGE_ASSETS),
        "library/library" to (FILES to PAGE_ASSETS),
        "home/library" to (FILES to PAGE_ASSETS),
        "home/projects" to (HOME to PAGE_OVERVIEW),
        "projects" to (HOME to PAGE_OVERVIEW),
        "backlogs" to (TASKS to PAGE_BACKLOG),
        "backlogs/backlogs" to (TASKS to PAGE_BACKLOG),
        "tasks/clock" to (CLOCK to PAGE_CLOCK),
    )

    /** The (section, page) a route names; page null = the section's own first page. Aliases first, so an old id never dead-ends. */
    fun resolve(section: String, page: String? = null): Pair<String, String?> =
        (page?.let { ALIASES["$section/$it"] } ?: ALIASES[section])?.let { it.first to it.second } ?: (section to page)
}

/** The navigation declaration baked from build.json::ui (ui.bottom_nav, ui.sections, ui.default_section). */
object Declarations {
    /** build.json::ui.top_bar (the fleet's first row); null = the app declares none. */
    val topBar: TopBarDecl? by lazy { TopBarDecl.fromBuildConfig(BuildConfig.UI_TOP_BAR_B64) }

    val nav: NavDecl by lazy { NavDecl.fromBuildConfig(BuildConfig.UI_SECTIONS_B64, BuildConfig.UI_BOTTOM_NAV, BuildConfig.UI_DEFAULT_SECTION) }
}

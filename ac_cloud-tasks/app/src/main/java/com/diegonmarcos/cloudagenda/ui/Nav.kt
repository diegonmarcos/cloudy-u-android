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
    const val BACKLOGS = "backlogs"
    const val TASKS = "tasks"
    const val LIBRARY = "library"
    const val PROJECTS = "projects"
    const val SETTINGS = "settings"

    const val PAGE_NOTES = "notes"
    const val PAGE_SYNCED = "synced"
    const val PAGE_BACKLOGS = "backlogs"
    const val PAGE_LIBRARY = "library"
    const val PAGE_PROJECTS = "projects"
    const val PAGE_CALENDAR = "calendar"
    const val PAGE_LIST = "list"
    const val PAGE_GANTT = "gantt"
    const val PAGE_KANBAN = "kanban"
    const val PAGE_MINDMAP = "mindmap"
    const val PAGE_GENERAL = "general"
    const val PAGE_PERMS = "perms"
    const val PAGE_ABOUT = "about"
    const val PAGE_APPEARANCE = "appearance"
    const val PAGE_AGENT = "agent"
    const val PAGE_INBOX = "inbox"
    const val PAGE_FEEDS = "feeds"
}

/** The navigation declaration baked from build.json::ui (ui.bottom_nav, ui.sections, ui.default_section). */
object Declarations {
    /** build.json::ui.top_bar (the fleet's first row); null = the app declares none. */
    val topBar: TopBarDecl? by lazy { TopBarDecl.fromBuildConfig(BuildConfig.UI_TOP_BAR_B64) }

    val nav: NavDecl by lazy { NavDecl.fromBuildConfig(BuildConfig.UI_SECTIONS_B64, BuildConfig.UI_BOTTOM_NAV, BuildConfig.UI_DEFAULT_SECTION) }
}

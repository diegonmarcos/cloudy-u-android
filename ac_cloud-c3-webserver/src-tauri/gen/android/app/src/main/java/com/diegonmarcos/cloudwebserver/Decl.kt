package com.diegonmarcos.cloudwebserver

import com.diegonmarcos.superapp.bottomnav.NavDecl

/**
 * The navigation declaration build.json::ui (bottom_nav, sections, default_section), decoded once through libs:bottomnav's NavDecl.
 * The bottom ids and the default are BuildConfig strings; the sections come from the Rust core (Core.nativeUiSections, its copy of
 * build.json::ui.sections) because runtime-data D3 forbids a new BuildConfig base64 field. If the core cannot load, the bar still
 * draws the declared bottom ids (labels = ids) and each page says why the server does not answer: Pages, API, Home, SSHd, ADB Shell on the bottom nav (the web shell's five tabs), Configs behind
 * the top bar's gear. Kotlin names no label; the ids below are the ones it dispatches on (test-c3-webserver-native.sh diffs them
 * against build.json::ui in both directions).
 */
object Decl {
    val nav: NavDecl by lazy {
        val bottom = BuildConfig.UI_BOTTOM_NAV
        runCatching { NavDecl.parse(Core.uiSections() ?: error("no core"), bottom, BuildConfig.UI_DEFAULT_SECTION) }.getOrNull()
            ?.takeIf { it.sections.isNotEmpty() }
            ?: NavDecl.parse(org.json.JSONArray(bottom.split(',').filter { it.isNotBlank() }.map { org.json.JSONObject().put("id", it).put("label", it) }), bottom, BuildConfig.UI_DEFAULT_SECTION)
    }

    const val SECTION_PAGES = "pages"
    const val SECTION_API = "api"
    const val SECTION_HOME = "home"
    const val SECTION_SSHD = "sshd"
    const val SECTION_ADB_SHELL = "adb_shell"
    const val SECTION_CONFIGS = "configs"

    const val PAGE_ALL = "all"
    const val PAGE_EXPLORER = "explorer"
    const val PAGE_BROWSER = "browser"
    const val PAGE_TOPOLOGY = "topology"
    const val PAGE_SERVER = "server"
    const val PAGE_DEVICE = "device"
    const val PAGE_ENGINES = "engines"
    const val PAGE_LINKS = "links"

    // #1713 the ADB Shell section's sub pages (the fleet second row): Status, Shell, Perms, Server, All. Ids are unique in the app (N12).
    const val PAGE_ADB_STATUS = "adb_status"
    const val PAGE_ADB_TERM = "adb_term"
    const val PAGE_ADB_PERMS = "adb_perms"
    const val PAGE_ADB_SERVER = "adb_server"
    const val PAGE_ADB_ALL = "adb_all"
}

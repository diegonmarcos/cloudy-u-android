package com.diegonmarcos.cloudsuperintel

import com.diegonmarcos.superapp.bottomnav.NavDecl

/**
 * The navigation declaration app/build.gradle bakes from build.json::ui (bottom_nav, sections, default_section),
 * decoded once through libs:bottomnav's NavDecl. Kotlin names no section label: the bottom nav, the second
 * row and the title dropdown all come from it. The SI section's page and the CGCs section's pages (six groups, two
 * rows) are the personas of assets/si/personas.json: their ids are data, declared in build.json::ui too
 * (test-superintel-app.sh and PersonasTest hold the two in step), so Kotlin names none of them.
 */
object Decl {
    val nav: NavDecl by lazy {
        NavDecl.fromBuildConfig(BuildConfig.UI_SECTIONS_B64, BuildConfig.UI_BOTTOM_NAV, BuildConfig.UI_DEFAULT_SECTION)
    }

    /** The ids Kotlin dispatches on; test-superintel-app.sh diffs them against build.json::ui in both directions. */
    const val SECTION_NOTEBOOK = "notebook"
    const val SECTION_SI = "si"
    const val SECTION_CGCS = "cgcs"
    const val SECTION_SETTINGS = "settings"
    const val SECTION_TOOLS = "tools"
    const val PAGE_NB_NOTEBOOKS = "nb_notebooks"
    const val PAGE_NB_SOURCES = "nb_sources"
    const val PAGE_NB_CHAT = "nb_chat"
    const val PAGE_NB_STUDIO = "nb_studio"
    const val PAGE_TOOLS_MODELS = "tools_models"
    const val PAGE_TOOLS_PICKERS = "tools_pickers"
    const val PAGE_TOOLS_TASKS = "tools_tasks"
    const val PAGE_TOOLS_MCP = "tools_mcp"
    const val PAGE_MODELS = "models"
    const val PAGE_BACKEND = "backend"
    const val PAGE_ABOUT = "about"
}

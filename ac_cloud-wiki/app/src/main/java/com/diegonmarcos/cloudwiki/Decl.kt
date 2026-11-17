package com.diegonmarcos.cloudwiki

import com.diegonmarcos.superapp.bottomnav.NavDecl

/**
 * The navigation declaration app/build.gradle bakes from build.json::ui (sections, default_section; bottom_nav is empty: no bottom nav), decoded once
 * through libs:bottomnav's NavDecl. Kotlin names no topic and no label: the side menu, its groups and the title all come from it. The two ids below
 * are the only ids Kotlin dispatches on; test-wiki-app.sh diffs them against build.json::ui.
 */
object Decl {
    val nav: NavDecl by lazy {
        NavDecl.fromBuildConfig(BuildConfig.UI_SECTIONS_B64, BuildConfig.UI_BOTTOM_NAV, BuildConfig.UI_DEFAULT_SECTION)
    }

    /** The one section holding every page (the collapsed side menu). */
    const val SECTION_WIKI = "wiki"

    /** The one page that is not a topic: the graph of every page. */
    const val PAGE_GRAPH = "graph"
}

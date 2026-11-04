package com.diegonmarcos.cloudshopping

import com.diegonmarcos.superapp.bottomnav.NavDecl

/**
 * The navigation declaration app/build.gradle bakes from build.json::ui (bottom_nav, sections, default_section), decoded once through
 * libs:bottomnav's NavDecl. Kotlin names no page and no label: the side menu, its groups and the title all come from it. The two section ids below
 * are the only ids Kotlin dispatches on; test-shopping-app.sh diffs them against build.json::ui in both directions.
 */
object Decl {
    val nav: NavDecl by lazy {
        NavDecl.fromBuildConfig(BuildConfig.UI_SECTIONS_B64, BuildConfig.UI_BOTTOM_NAV, BuildConfig.UI_DEFAULT_SECTION)
    }

    /** The section holding every page (the collapsed side menu) and the one-item bottom nav's section (Search). */
    const val SECTION_SHOP = "shop"
    const val SECTION_SEARCH = "search"

    /** The page group of each page id, from the shop section's declared groups (a page in no group maps to its section). */
    fun groupOf(): Map<String, String> =
        nav.section(SECTION_SHOP)?.groups.orEmpty().flatMap { g -> g.pages.map { it.id to g.id } }.toMap()
}

package com.diegonmarcos.cloudwiki

import android.util.Base64
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

    /**
     * #1789 the Chronos pages (diegonmarcos/lib-chronos at the pin, tools/chronos-pin.json): the nested pages build.json::ui declares under
     * wiki > chronos with their `asset`, read from the same baked ui.sections (NavDecl keeps no `asset`).
     */
    val chronos: ChronosTree by lazy {
        Chronos.parse(runCatching { String(Base64.decode(BuildConfig.UI_SECTIONS_B64, Base64.DEFAULT), Charsets.UTF_8) }.getOrDefault("[]"))
    }

    /** The one section holding every page (the collapsed side menu). */
    const val SECTION_WIKI = "wiki"

    /** The one page that is not a topic: the graph of every page. */
    const val PAGE_GRAPH = "graph"
}

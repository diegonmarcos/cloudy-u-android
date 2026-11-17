package com.diegonmarcos.cloudcanvas

import com.diegonmarcos.superapp.bottomnav.NavDecl

/**
 * The navigation declaration app/build.gradle bakes from build.json::ui (bottom_nav Draw | Play, sections, default_section), decoded once through
 * libs:bottomnav's NavDecl. The two section ids below are the only ids Kotlin dispatches on; test-canvas-app.sh diffs them against build.json::ui.
 */
object Decl {
    val nav: NavDecl by lazy {
        NavDecl.fromBuildConfig(BuildConfig.UI_SECTIONS_B64, BuildConfig.UI_BOTTOM_NAV, BuildConfig.UI_DEFAULT_SECTION)
    }

    const val SECTION_DRAW = "draw"
    const val SECTION_PLAY = "play"
}

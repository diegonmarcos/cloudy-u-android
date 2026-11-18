package com.diegonmarcos.cloudcanvas

import com.diegonmarcos.superapp.bottomnav.NavDecl

/**
 * The navigation declaration app/build.gradle bakes from build.json::ui (bottom_nav Draw | Play | Draw Enhancer | Music Enhancer, sections, default_section), decoded once through
 * libs:bottomnav's NavDecl. The four section ids and the two Draw page ids below are the only ids Kotlin dispatches on; test-canvas-app.sh diffs
 * them against build.json::ui. #1787 the Draw section holds two sibling pages, Draw (the paint canvas) and Board (the tldraw whiteboard),
 * drawn as the shared second row.
 */
object Decl {
    val nav: NavDecl by lazy {
        NavDecl.fromBuildConfig(BuildConfig.UI_SECTIONS_B64, BuildConfig.UI_BOTTOM_NAV, BuildConfig.UI_DEFAULT_SECTION)
    }

    const val SECTION_DRAW = "draw"
    const val SECTION_PLAY = "play"
    const val SECTION_ENHANCE = "enhance"
    const val SECTION_MUSIC = "music"

    const val PAGE_DRAW = "draw"
    const val PAGE_BOARD = "board"
}

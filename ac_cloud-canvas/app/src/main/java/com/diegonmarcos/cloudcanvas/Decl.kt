package com.diegonmarcos.cloudcanvas

import com.diegonmarcos.superapp.bottomnav.NavDecl

/**
 * The navigation declaration app/build.gradle bakes from build.json::ui (bottom_nav Draw | Painter | Board | Play | Enhancers, sections, default_section), decoded once through
 * libs:bottomnav's NavDecl. The four section ids and the two Draw page ids below are the only ids Kotlin dispatches on; test-canvas-app.sh diffs
 * them against build.json::ui. #1787 the Draw section holds two sibling pages, Draw (the paint canvas) and Board (the Quickdraw whiteboard),
 * drawn as the shared second row. #1808 Painter (the cell painter, a WebView page) is a section of its own, right after Draw.
 * #1808 nav: Board is ALSO a section of its own right after Painter (the Draw > Board page stays), and Enhancers (section id enhance) holds
 * the pages Draw Enhancer and Music Enhancer on one shared second row. [LEGACY_SECTIONS] keeps the old `section music` deep link working.
 */
object Decl {
    val nav: NavDecl by lazy {
        NavDecl.fromBuildConfig(BuildConfig.UI_SECTIONS_B64, BuildConfig.UI_BOTTOM_NAV, BuildConfig.UI_DEFAULT_SECTION)
    }

    const val SECTION_DRAW = "draw"
    const val SECTION_PAINTER = "painter"
    const val SECTION_PLAY = "play"
    /** Enhancers: the one bottom-nav entry whose second row holds Draw Enhancer and Music Enhancer. */
    const val SECTION_ENHANCE = "enhance"
    const val SECTION_BOARD = "board"

    const val PAGE_DRAW = "draw"
    const val PAGE_BOARD = "board"
    const val PAGE_PAINTER = "painter"
    const val PAGE_ENHANCE = "enhance"
    /** Music Enhancer, the coming-soon placeholder (its glyph greyed in the Enhancers second row). */
    const val PAGE_MUSIC = "music"

    /** Section ids that were bottom-nav entries before the #1808 nav and are pages now: a deep link naming one opens (section, page). */
    val LEGACY_SECTIONS: Map<String, Pair<String, String>> = mapOf("music" to (SECTION_ENHANCE to PAGE_MUSIC))

    /** Where a deep link's (section, page) lands: a legacy section id maps to its new section and page; an explicit page wins otherwise. */
    fun resolve(section: String?, page: String?): Pair<String?, String?> =
        section?.let { LEGACY_SECTIONS[it] }?.let { (s, p) -> s to p } ?: (section to page)
}

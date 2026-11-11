package com.diegonmarcos.cloudwriter


import com.diegonmarcos.cloudwriter.writerui.BuildConfig

import com.diegonmarcos.cloudwriter.core.WriterTabs
import com.diegonmarcos.superapp.bottomnav.NavDecl

/**
 * #868 Cloud Writer's navigation, read from THE one declaration: build.json::ui (bottom_nav,
 * sections, default_section), baked by app/build.gradle and parsed by libs:bottomnav's [NavDecl].
 * MainActivity draws the bar (BottomNavIsland) and the Tools strip (PageTabs) from it; nothing
 * here lists a section twice. What stays code is the localized label of each id (the declared `label` is the English fallback). Icons are libs:ui-icons' catalog (#1160).
 */
object WriterNav {

    val decl: NavDecl by lazy {
        NavDecl.fromBuildConfig(BuildConfig.UI_SECTIONS_B64, BuildConfig.UI_BOTTOM_NAV, BuildConfig.UI_DEFAULT_SECTION)
    }

    /** The section ids build.json declares; a `when` over them is how MainActivity opens one. */
    /** #1418 Home (the document list) is the bar's middle item and where the app opens. */
    const val DOCUMENTS = WriterTabs.HOME
    const val ENHANCER = WriterTabs.ENHANCER
    const val DRAFTER = WriterTabs.DRAFTER
    const val TRANSLATOR = WriterTabs.TRANSLATOR
    const val GRAMMAR = WriterTabs.GRAMMAR
    const val WRITER = "writer"
    const val TOOLS = "tools"
    const val SETTINGS = "settings"

    /** The declared ui.sections[tools].pages id of a tool's activity, by class name: MainActivity names each activity once. */
    fun pageId(screen: Class<*>): String? = when (screen.simpleName) {
        "TextEnhanceActivity" -> "enhance"
        "TranslationActivity" -> "translation"
        "GrammarCheckActivity" -> "grammar"
        "AiRoutingActivity" -> "ai_routing"
        "RoutesActivity" -> "routes"
        else -> null
    }
}

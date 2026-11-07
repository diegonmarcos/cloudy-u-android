package com.diegonmarcos.cloudkgstore

import com.diegonmarcos.superapp.bottomnav.NavDecl

/**
 * The navigation declaration app/build.gradle bakes from build.json::ui (bottom_nav, sections, default_section),
 * decoded once through libs:bottomnav's NavDecl. Kotlin names no section label: the bottom nav, the second row (two rows
 * in Cloud) and the title dropdown all come from it. The pages of Cloud, Phone and Web are the keys of the kg dataset pages
 * (test-kg-store-app.sh and KgConfigTest hold the two in step), so Kotlin names none of them.
 */
object Decl {
    val nav: NavDecl by lazy {
        NavDecl.fromBuildConfig(BuildConfig.UI_SECTIONS_B64, BuildConfig.UI_BOTTOM_NAV, BuildConfig.UI_DEFAULT_SECTION)
    }

    /** The ids Kotlin dispatches on; test-kg-store-app.sh diffs them against build.json::ui in both directions. */
    const val SECTION_CLOUD = "cloud"
    const val SECTION_PHONE = "phone"
    const val SECTION_WEB = "web"
    const val SECTION_ASK = "ask"
    const val SECTION_SETTINGS = "settings"
    const val PAGE_MODELS = "models"
    const val PAGE_BACKEND = "backend"
    const val PAGE_ABOUT = "about"
}

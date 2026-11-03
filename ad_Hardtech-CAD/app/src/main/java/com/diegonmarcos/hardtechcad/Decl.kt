package com.diegonmarcos.hardtechcad

import android.util.Base64
import com.diegonmarcos.superapp.bottomnav.NavDecl

/**
 * The declarations app/build.gradle bakes from build.json::ui, decoded once. [nav] is libs:bottomnav's NavDecl (bottom_nav,
 * sections, default_section): the bottom bar, the title and the first row all come from it, Kotlin names no section label.
 * [stacks] is ui.stacks (the entries of each section's page) and [targets] the declared sources those entries name, resolved at
 * build time from the SuperApp's own declarations (linktree.json, ui.app_urls), so no address is typed here or in build.json.
 */
object Decl {
    val nav: NavDecl by lazy {
        NavDecl.fromBuildConfig(BuildConfig.UI_SECTIONS_B64, BuildConfig.UI_BOTTOM_NAV, BuildConfig.UI_DEFAULT_SECTION)
    }

    val stacks: String by lazy { decode(BuildConfig.UI_STACKS_B64) }
    val targets: String by lazy { decode(BuildConfig.UI_TARGETS_B64) }

    /** #1404 ui.libraries: the MCAD model-library pages (libs:cad-core CadLibraries parses it). */
    val libraries: String by lazy { decode(BuildConfig.UI_LIBRARIES_B64) }

    /** The one section Kotlin dispatches on: the gear on the first row opens it (declared in ui.sections, not on the bottom bar). */
    const val SECTION_ABOUT = "about"

    private fun decode(b64: String): String = String(Base64.decode(b64, Base64.NO_WRAP), Charsets.UTF_8)
}

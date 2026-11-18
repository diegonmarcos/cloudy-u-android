package com.diegonmarcos.cgomniverse

import android.util.Base64
import com.diegonmarcos.superapp.bottomnav.NavDecl

/**
 * The declarations app/build.gradle bakes, decoded once. [nav] is libs:bottomnav's NavDecl (build.json::ui bottom_nav, sections,
 * default_section): the bottom bar, the title and the first row all come from it, Kotlin names no section label. [stacks] is
 * ui.stacks (the kind each section's page draws) and [targets] the declared sources entries name. [worlds] is data/store.json (the
 * Store's catalogue, generated from data/fwa.md) and [avatar] data/avatar.json (the configurator's groups and default avatar).
 */
object Decl {
    val nav: NavDecl by lazy {
        NavDecl.fromBuildConfig(BuildConfig.UI_SECTIONS_B64, BuildConfig.UI_BOTTOM_NAV, BuildConfig.UI_DEFAULT_SECTION)
    }

    val stacks: String by lazy { decode(BuildConfig.UI_STACKS_B64) }
    val targets: String by lazy { decode(BuildConfig.UI_TARGETS_B64) }
    val worlds: List<World> by lazy { Store.parse(decode(BuildConfig.STORE_B64)) }
    val avatar: AvatarSpec by lazy { Avatar.spec(decode(BuildConfig.AVATAR_B64)) }

    /** #1711 Galaxy's declared default tabs (build.json::galaxy_tabs): Store ids, in tab order. */
    val galaxyTabs: List<String> by lazy { Tabs.ids(BuildConfig.GALAXY_TABS) }

    /** The one section Kotlin dispatches on: the gear on the first row opens it (declared in ui.sections, not on the bottom bar). */
    const val SECTION_ABOUT = "about"
    /** Where "Open in Galaxy" lands. */
    const val SECTION_GALAXY = "galaxy"
    /** Where Galaxy's "+" lands: the Store is the world picker. */
    const val SECTION_STORE = "store"

    private fun decode(b64: String): String = String(Base64.decode(b64, Base64.NO_WRAP), Charsets.UTF_8)
}

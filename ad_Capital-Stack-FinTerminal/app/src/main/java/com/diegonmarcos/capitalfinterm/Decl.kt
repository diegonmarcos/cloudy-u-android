package com.diegonmarcos.capitalfinterm

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
    /** #1399 ui.redirects: removed dashboards and the (tab, sub-page) their deep links open. */
    val redirects: String by lazy { decode(BuildConfig.UI_REDIRECTS_B64) }

    /** #1399 the declared sub-pages of [sectionId] (ui.sections[].pages: Home first) as (id, label, icon). */
    fun pages(sectionId: String): List<Triple<String, String, String>> = nav.section(sectionId)?.pages.orEmpty().map { Triple(it.id, it.label, it.icon) }

    /** The private mesh's base addresses (fleet endpoint id to address), baked by app/build.gradle from the fleet declaration: nothing is typed here. */
    val fleetMesh: Map<String, String> by lazy { Mesh.parse(decode(BuildConfig.FLEET_MESH_B64)) }

    /** The one section Kotlin dispatches on: the gear on the first row opens it (declared in ui.sections, not on the bottom bar). */
    const val SECTION_ABOUT = "about"

    /** The hidden Config section the gear on the first row opens: Sources (the catalog and the user's keys) and About. */
    const val SECTION_CONFIG = "config"

    private fun decode(b64: String): String = String(Base64.decode(b64, Base64.NO_WRAP), Charsets.UTF_8)
}

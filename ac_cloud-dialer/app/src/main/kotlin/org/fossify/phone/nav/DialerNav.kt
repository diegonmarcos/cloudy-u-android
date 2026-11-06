package org.fossify.phone.nav

import com.diegonmarcos.superapp.bottomnav.NavDecl
import com.diegonmarcos.superapp.bottomnav.NavSection
import org.fossify.phone.BuildConfig

/**
 * Cloud Dialer's navigation (#868): build.json::ui, baked into BuildConfig, decoded once. The
 * sections are the island's items; which of them are on screen is the pager's business
 * (Settings > Manage shown tabs), so [shown] takes the ids the pager shows.
 */
object DialerNav {
    val decl: NavDecl by lazy {
        NavDecl.fromBuildConfig(BuildConfig.UI_SECTIONS_B64, BuildConfig.UI_BOTTOM_NAV, BuildConfig.UI_DEFAULT_SECTION)
    }

    /** #1528 Keypad (T9 search), VoIP (opens a VoIP app) and SIMs (one sub-page per SIM / eSIM) are not pager pages: always shown. */
    const val KEYPAD = "keypad"
    const val VOIP = "voip"
    const val SIMS = "sims"
    val NON_PAGER: Set<String> = setOf(KEYPAD, VOIP, SIMS)

    /** The bottom nav's sections, in declared order, limited to the sections whose page is on screen (plus [NON_PAGER]). */
    fun shown(visibleSections: Collection<String>): List<NavSection> =
        decl.bottomSections().filter { it.id in visibleSections || it.id in NON_PAGER }
}

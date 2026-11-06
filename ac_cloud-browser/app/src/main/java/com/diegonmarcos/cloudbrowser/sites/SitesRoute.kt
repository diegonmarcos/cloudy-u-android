package com.diegonmarcos.cloudbrowser.sites

/**
 * #1531 where the browser's own links lead, pure (JVM-tested in SitesRouteTest). The Tabs page's History link (and the menu's History)
 * opens the official Domains > History page: one history UI, this route is its only entry.
 */
object SitesRoute {
    const val HISTORY_SECTION = "domains"
    const val HISTORY_PAGE = "history"

    /** (section, page) of the History page when the nav declaration has it ([has] asks the declared sections), else null. */
    fun history(has: (section: String, page: String) -> Boolean): Pair<String, String>? =
        (HISTORY_SECTION to HISTORY_PAGE).takeIf { has(it.first, it.second) }
}

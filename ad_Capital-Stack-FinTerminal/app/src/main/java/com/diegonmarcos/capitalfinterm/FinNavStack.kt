package com.diegonmarcos.capitalfinterm

/**
 * A tab's back stack (UI spec 4.3 / 4.4), as saveable strings: `list` (the section's Homepage, #1399), `dash:<id>`, `page:<id>` (a
 * non-dashboard sub-page, e.g. MY-PM's holdings), `quote:<symbol>`. A tab opens on its Homepage ([initial]); a quote is pushed on
 * top; back pops a quote, then returns any sub-page to the Homepage, then leaves a non-home tab for the home tab ([HOME]), and on
 * the home tab's Homepage lets the system exit (null). The island's own selection never adds to a stack (tabs are peers).
 */
object FinNavStack {
    const val LIST = "list"
    val LIST_ONLY: List<String> = listOf(LIST)
    /** The answer of [back] that means: switch to the home tab. */
    val HOME: List<String> = listOf("__home__")

    /** #1399 every section opens on its Homepage. */
    fun initial(): List<String> = LIST_ONLY

    /** A dashboard replaces the tab's detail (no dashboard history: back goes to the Homepage). */
    fun open(@Suppress("UNUSED_PARAMETER") stack: List<String>, id: String): List<String> = listOf(LIST, "dash:$id")

    /** A non-dashboard sub-page (`page:<id>`) replaces the tab's detail like a dashboard. */
    fun openPage(id: String): List<String> = listOf(LIST, "page:$id")

    fun quote(stack: List<String>, symbol: String): List<String> = if (stack.lastOrNull() == "quote:$symbol") stack else stack + "quote:$symbol"

    fun dashboardOf(stack: List<String>): String? = stack.lastOrNull { it.startsWith("dash:") }?.removePrefix("dash:")

    fun pageOf(stack: List<String>): String? = stack.lastOrNull { it.startsWith("page:") }?.removePrefix("page:")

    /** The sub-page the one-row nav highlights: the section's Home on the Homepage, else the shown dashboard's slug or page id. */
    fun selectedPage(stack: List<String>, sectionId: String): String = pageOf(stack) ?: dashboardOf(stack)?.let { Pages.slugOf(it) } ?: Pages.homeId(sectionId)

    fun symbolOf(screen: String?): String? = screen?.takeIf { it.startsWith("quote:") }?.removePrefix("quote:")

    fun back(stack: List<String>, isHomeTab: Boolean): List<String>? {
        val top = stack.lastOrNull() ?: return if (isHomeTab) null else HOME
        if (top.startsWith("quote:")) return stack.dropLast(1)
        if (top != LIST) return LIST_ONLY
        return if (isHomeTab) null else HOME
    }
}

package com.diegonmarcos.cloudagenda

import com.diegonmarcos.cloudagenda.ui.Declarations
import com.diegonmarcos.cloudagenda.ui.Ids
import com.diegonmarcos.superapp.bottomnav.TabSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The declared navigation (build.json::ui, baked into BuildConfig) against the ids Kotlin dispatches on. */
class NavDeclTest {
    private val nav = Declarations.nav

    @Test fun `the declaration is valid and opens on Home`() {
        assertEquals(emptyList<String>(), nav.problems())
        assertEquals(Ids.HOME, nav.default()?.id)
    }

    // #1964 the island has Clock in Library's place
    @Test fun `the island has Clock and no Library`() {
        assertEquals(listOf("inboxes", "backlogs", "home", "tasks", "clock"), nav.bottomNav)
        assertTrue("library" !in nav.bottomNav)
        assertEquals("clock", nav.bottomSections().last().icon)
    }

    // #1964 rule 16: Library's content and page stay reachable, and the old routes still resolve
    @Test fun `Library is still reachable as a Home page and its old routes resolve`() {
        assertEquals(listOf(Ids.PAGE_OVERVIEW, Ids.PAGE_PROJECTS, Ids.PAGE_LIBRARY), nav.section(Ids.HOME)!!.pages.map { it.id })
        assertEquals(Ids.HOME to Ids.PAGE_LIBRARY, Ids.resolve("library").let { it.first to it.second })
        assertEquals(Ids.HOME to Ids.PAGE_LIBRARY, Ids.resolve("library", "library").let { it.first to it.second })
        assertEquals(Ids.CLOCK to Ids.PAGE_CLOCK, Ids.resolve("tasks", "clock").let { it.first to it.second })
        for ((_, target) in Ids.ALIASES) assertTrue(nav.section(target.first)!!.pages.any { it.id == target.second })
    }

    // #1964 the Clock's subpages are Cloud Calc's Tools > Clock modes, declared once in the lib
    @Test fun `the Clock subpages equal Cloud Calc's Tools Clock`() {
        val calc = java.io.File("../../ac_cloud-calc/build.json")
        assertTrue("Cloud Calc's build.json must be readable from the checkout: " + calc.absolutePath, calc.isFile)
        val modes = org.json.JSONObject(calc.readText()).getJSONObject("ui").getJSONArray("modes")
        val calcClock = (0 until modes.length()).map { modes.getJSONObject(it) }.filter { it.optString("tab") == "clock" }.map { it.getString("id") }
        assertEquals(calcClock, com.diegonmarcos.clocktools.ClockSubpages.IDS)
    }

    @Test fun `every id Kotlin dispatches on is declared and every declared section is dispatched`() {
        assertEquals(setOf(Ids.INBOXES, Ids.BACKLOGS, Ids.HOME, Ids.TASKS, Ids.CLOCK, Ids.SETTINGS), nav.sections.map { it.id }.toSet())
        assertEquals(listOf(Ids.PAGE_CALENDAR, Ids.PAGE_LIST, Ids.PAGE_GANTT, Ids.PAGE_KANBAN, Ids.PAGE_MINDMAP), nav.section(Ids.TASKS)!!.pages.map { it.id })
        assertEquals(
            listOf(Ids.PAGE_APPEARANCE, Ids.PAGE_AGENT, Ids.PAGE_INBOX, Ids.PAGE_FEEDS, Ids.PAGE_GENERAL, Ids.PAGE_PERMS, Ids.PAGE_ABOUT),   // #1852 own pages first
            nav.section(Ids.SETTINGS)!!.pages.map { it.id },
        )
        assertEquals(listOf(Ids.PAGE_NOTES, Ids.PAGE_SYNCED), nav.section(Ids.INBOXES)!!.pages.map { it.id })
        assertEquals(listOf(Ids.PAGE_BACKLOGS), nav.section(Ids.BACKLOGS)!!.pages.map { it.id })
        assertEquals(listOf(Ids.PAGE_CLOCK), nav.section(Ids.CLOCK)!!.pages.map { it.id })
        assertEquals(listOf(Ids.PAGE_OVERVIEW, Ids.PAGE_PROJECTS, Ids.PAGE_LIBRARY), nav.section(Ids.HOME)!!.pages.map { it.id })
    }

    @Test fun `Inboxes has the sub-pages Notes then Synced, and opens on Notes`() {
        val pages = nav.section(Ids.INBOXES)!!.pages
        assertEquals(listOf("Notes", "Synced"), pages.map { it.label })
        assertEquals(Ids.PAGE_NOTES, nav.section(Ids.INBOXES)!!.page(null)?.id)
    }

    @Test fun `Settings is declared but not on the island`() {
        assertTrue(Ids.SETTINGS !in nav.bottomNav)
        assertTrue(nav.bottomSections().none { it.id == Ids.SETTINGS })
    }

    @Test fun `strips follow the app's light and dark mode`() {
        assertEquals(TabSurface.Dark, nav.stripSurface(Ids.TASKS, darkTheme = true))
        assertEquals(TabSurface.Light, nav.stripSurface(Ids.TASKS, darkTheme = false))
        assertEquals(TabSurface.Light, nav.stripSurface(Ids.SETTINGS, darkTheme = false, pageId = Ids.PAGE_AGENT))
    }
}

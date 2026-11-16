package com.diegonmarcos.superapp.navbartop1

import com.diegonmarcos.superapp.bottomnav.NavPage
import com.diegonmarcos.superapp.bottomnav.NavSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1758 the title dropdown always offers every section, whatever page a strip put the selection on (nav-walk P6). */
class TitleDropdownSectionsTest {
    private val strip = NavSection("tasks", "Tasks", "t", listOf(NavPage("cal", "Calendar", "c"), NavPage("list", "List", "l")))
    private val bare = NavSection("library", "Library", "b")
    private val settings = NavSection("settings", "Settings", "s", listOf(NavPage("general", "General", "g"), NavPage("about", "About", "a")))
    private val all = listOf(strip, bare, settings)
    private fun ids(l: List<HierarchyEntry>) = l.map { it.id }

    @Test fun `a section with a strip lists every section after its pages once the strip moved the selection`() {
        val h = hierarchyWithShown(titleHierarchy(strip, null), listOf("list"))
        val n = titleNav("App", "Tasks", h, all)
        assertEquals("the pages stay as they were", listOf("cal", "list"), ids(n.related))
        assertEquals(listOf("tasks", "library", "settings"), ids(n.sections))
        assertTrue(n.sections.first { it.id == "tasks" }.selected)
        assertTrue(n.sections.all { it.depth == 0 })
    }

    @Test fun `a section without a strip lists every section at depth 0 as before`() {
        val n = titleNav("App", "Library", titleHierarchy(bare, null), all)
        assertEquals(listOf("tasks", "library", "settings"), ids(n.related))
        assertTrue("no second copy", n.sections.isEmpty())
    }

    @Test fun `the settings section is a peer too, listed or not in the sections handed in`() {
        val h = hierarchyWithShown(titleHierarchy(settings, null), listOf("about"))
        assertEquals(listOf("tasks", "library", "settings"), ids(titleNav("App", "Settings", h, all).sections))
        val off = titleNav("App", "Settings", h, listOf(strip, bare))
        assertEquals(listOf("tasks", "library", "settings"), ids(off.sections))
        assertTrue(off.sections.last().selected)
        assertEquals(listOf("general", "about"), ids(off.related))
        assertEquals(listOf("App", "Settings", "About"), off.labels)
    }

    @Test fun `no sections handed in adds none`() {
        val h = hierarchyWithShown(titleHierarchy(strip, null), listOf("list"))
        assertTrue(titleNav("App", "Tasks", h).sections.isEmpty())
    }

    @Test fun `a section whose only page carries its id is listed once`() {
        val sheet = NavSection("sheet", "Sheet", "x", listOf(NavPage("sheet", "Sheet", "x")))
        val n = titleNav("App", "Sheet", titleHierarchy(sheet, "sheet"), listOf(strip, sheet, settings))
        val all = ids(n.related) + ids(n.sections)
        assertEquals(all.distinct(), all)
        assertEquals(setOf("tasks", "sheet", "settings"), all.toSet())
    }
}

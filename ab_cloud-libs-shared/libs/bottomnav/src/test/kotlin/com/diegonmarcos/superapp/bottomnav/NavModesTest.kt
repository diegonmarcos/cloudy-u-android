package com.diegonmarcos.superapp.bottomnav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The three navigation modes, the runtime override, and the side menu's pure rules (rail caption, presence, rows). */
class NavModesTest {
    private fun p(id: String, label: String = "Page $id") = NavPage(id, label, "folder")
    private val pages = listOf(p("a"), NavPage("b", "Bravo", "folder", pages = listOf(p("b1", "Child"))), p("c"))
    private val section = NavSection("s", "S", "i", pages)

    @Test fun `the declared names and the old aliases both read`() {
        assertEquals(SecondRowMode.OneRow, SecondRowMode.of("horizontal_one_row"))
        assertEquals(SecondRowMode.OneRow, SecondRowMode.of("one_row"))
        assertEquals(SecondRowMode.OneRow, SecondRowMode.of(null))
        assertEquals(SecondRowMode.OneRow, SecondRowMode.of(""))
        assertEquals(SecondRowMode.ManyRows, SecondRowMode.of("horizontal_two_rows"))
        assertEquals(SecondRowMode.ManyRows, SecondRowMode.of("many_rows"))
        assertEquals(SecondRowMode.SideMenu, SecondRowMode.of("vertical_side_menu"))
        assertNull(SecondRowMode.of("sideways"))
        assertEquals(listOf("horizontal_one_row", "horizontal_two_rows", "vertical_side_menu"), SecondRowMode.entries.map { it.declared })
    }

    @Test fun `a section declares its mode in nav_mode and the default is one row`() {
        val json = """[{"id":"x","label":"X","icon":"i","nav_mode":"vertical_side_menu","pages":[{"id":"p","label":"P","icon":"i"}]},
            {"id":"y","label":"Y","icon":"i","nav_mode":"many_rows","pages":[{"id":"q","label":"Q","icon":"i"}]},
            {"id":"z","label":"Z","icon":"i","pages":[{"id":"r","label":"R","icon":"i"}]}]"""
        val d = NavDecl.parse(json)
        assertEquals(SecondRowMode.SideMenu, d.section("x")!!.rows)
        assertEquals(SecondRowMode.ManyRows, d.section("y")!!.rows)
        assertEquals(SecondRowMode.OneRow, d.section("z")!!.rows)
    }

    @Test fun `emoji_only and separator_before are read per page, both false when absent (#1937)`() {
        val json = """[{"id":"x","label":"X","icon":"i","pages":[{"id":"a","label":"A","icon":"i"},{"id":"b","label":"B","icon":"i","emoji_only":true,"separator_before":true}]}]"""
        val pages = NavDecl.parse(json).section("x")!!.pages
        assertEquals(listOf(false, true), pages.map { it.emojiOnly })
        assertEquals(listOf(false, true), pages.map { it.separatorBefore })
    }

    @Test fun `the user's choice overrides the declaration per section and clearing it restores the declaration`() {
        val store = MemoryNavModeStore()
        assertSame(section, section.effective(store))
        assertSame(section, section.effective(null))
        store.setMode("s", SecondRowMode.SideMenu)
        assertEquals(SecondRowMode.SideMenu, section.effective(store).rows)
        assertEquals("another section is untouched", SecondRowMode.OneRow, section.copy(id = "t").effective(store).rows)
        store.setMode("s", SecondRowMode.ManyRows)
        assertEquals(SecondRowMode.ManyRows, section.effective(store).rows)
        store.setMode("s", null)
        assertEquals(SecondRowMode.OneRow, section.effective(store).rows)
    }

    @Test fun `the rail shows the first three letters of the name`() {
        assertEquals("Inb", railLabel("Inbox"))
        assertEquals("Ab", railLabel("Ab"))
        assertEquals("Sen", railLabel("  Sent mail "))
        assertEquals("", railLabel(""))
        assertEquals("Dr", railLabel("Dr"))
        assertEquals("Ca", railLabel("Ca", 2))
    }

    @Test fun `the side menu follows the scroll machine, and the user's choice only matters at rest`() {
        assertEquals(SideMenuPresence.Expanded, sideMenuPresence(true, ChromeScrollState.Default))
        assertEquals(SideMenuPresence.Rail, sideMenuPresence(false, ChromeScrollState.Default))
        for (pref in listOf(true, false)) {
            assertEquals(SideMenuPresence.Away, sideMenuPresence(pref, ChromeScrollState.Hidden))
            assertEquals(SideMenuPresence.Rail, sideMenuPresence(pref, ChromeScrollState.Shrunk))
        }
    }

    @Test fun `the rail state is remembered by the store`() {
        val store = MemoryNavModeStore()
        assertNull(store.sideExpanded())
        store.setSideExpanded(true)
        assertEquals(true, store.sideExpanded())
        store.setSideExpanded(false)
        assertEquals(false, store.sideExpanded())
    }

    @Test fun `the menu lists pages depth-first with children indented`() {
        val rows = sideMenuItems(section)
        assertEquals(listOf("a", "b", "b1", "c"), rows.map { it.id })
        assertEquals(listOf(0, 0, 1, 0), rows.map { it.depth })
        assertTrue(rows.none { it.heading })
    }

    @Test fun `declared groups become headings over their pages`() {
        val g1 = NavGroup("g1", "Folders", listOf(p("a"), p("b")), "ic")
        val g2 = NavGroup("g2", "Labels", listOf(p("c")), "ic")
        val s = NavSection("m", "Mail", "i", listOf(p("a"), p("b"), p("c")), rows = SecondRowMode.SideMenu, groups = listOf(g1, g2))
        val rows = sideMenuItems(s)
        assertEquals(listOf("group:g1", "a", "b", "group:g2", "c"), rows.map { it.id })
        assertEquals(listOf(true, false, false, true, false), rows.map { it.heading })
    }
}

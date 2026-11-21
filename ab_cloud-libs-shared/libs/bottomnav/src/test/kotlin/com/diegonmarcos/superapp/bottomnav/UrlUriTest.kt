package com.diegonmarcos.superapp.bottomnav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1842 the Url-Uri dropdown's model, frozen (Rule 16): the pages and tools every app's top bar lists, in order, plus the declared slots. */
class UrlUriTest {
    private val sections = listOf(NavSection("a", "Alpha", "home"), NavSection("b", "Beta", "cloud"))

    @Test fun `pages are the bottom pages, AI, the app slot, Profile and Configs in that order`() {
        val decl = UrlUriDecl(pages = listOf(UrlUriItem("x", "Extra", "star", "b/p")))
        val m = urlUriModel(sections, "b", decl, canUp = true)
        assertEquals(listOf("section:a", "section:b", "ai", "slot_page:x", "profile", "configs"), m.pages.map { it.id })
        assertEquals(listOf(false, true), m.pages.take(2).map { it.selected })
        assertTrue(m.pages.all { it.icon.isNotBlank() })
    }

    @Test fun `tools are back up home, background force close restart, then the app slot`() {
        val decl = UrlUriDecl(tools = listOf(UrlUriItem("t", "New tab", "add")))
        val m = urlUriModel(sections, "a", decl, canUp = false)
        assertEquals(UrlUriIds.FIXED_TOOLS + "slot_tool:t", m.tools.map { it.id })
        assertFalse("Up is disabled, not dropped, with no parent", m.tools.first { it.id == UrlUriIds.UP }.enabled)
        assertTrue(m.tools.all { it.icon.isNotBlank() })
    }

    @Test fun `an app that declares nothing still has both groups`() {
        val m = urlUriModel(emptyList(), null, UrlUriDecl(), canUp = false)
        assertEquals(UrlUriIds.FIXED_PAGES, m.pages.map { it.id })
        assertEquals(UrlUriIds.FIXED_TOOLS, m.tools.map { it.id })
    }

    @Test fun `the declaration parses from json, and a malformed or blank one is empty`() {
        val json = """{"pages":[{"id":"s","label":"Search","icon":"search","page":"search"}],"tools":[{"id":"nt","label":"New tab","icon":"add"}]}"""
        val d = UrlUriDecl.parse(json)
        assertEquals(listOf("s"), d.pages.map { it.id }); assertEquals("search", d.pages[0].page); assertEquals(listOf("nt"), d.tools.map { it.id })
        assertEquals(emptyList<String>(), d.problems())
        assertEquals(UrlUriDecl(), UrlUriDecl.parse(""))
        assertEquals(UrlUriDecl(), UrlUriDecl.parse("[1,2"))
    }

    @Test fun `problems name a slot entry with no page or a repeated id`() {
        val bad = UrlUriDecl(pages = listOf(UrlUriItem("s", "S", "i", ""), UrlUriItem("s", "S2", "i", "x")))
        assertTrue(bad.problems().any { "names no page" in it }); assertTrue(bad.problems().any { "repeats an id" in it })
    }
}

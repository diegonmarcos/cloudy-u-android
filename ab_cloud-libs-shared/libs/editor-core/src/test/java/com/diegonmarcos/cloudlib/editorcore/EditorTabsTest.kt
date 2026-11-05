package com.diegonmarcos.cloudlib.editorcore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EditorTabsTest {
    private fun paths(t: EditorTabs) = t.tabs.map { it.path }

    @Test fun `opening adds a tab at the end and activates it`() {
        val t = EditorTabs().open("/a.txt").open("/b.md")
        assertEquals(listOf("/a.txt", "/b.md"), paths(t))
        assertEquals("/b.md", t.activePath)
        assertEquals("b.md", t.active?.title)
    }

    @Test fun `opening an open path switches instead of duplicating`() {
        val t = EditorTabs().open("/a.txt").open("/b.md").open("/a.txt")
        assertEquals(listOf("/a.txt", "/b.md"), paths(t))
        assertEquals("/a.txt", t.activePath)
    }

    @Test fun `switching changes the active tab and ignores an unknown path`() {
        val t = EditorTabs().open("/a").open("/b").activate("/a")
        assertEquals("/a", t.activePath)
        assertEquals("/a", t.activate("/zzz").activePath)
    }

    @Test fun `closing the active tab activates the right neighbour, else the left one`() {
        val t = EditorTabs().open("/a").open("/b").open("/c")
        assertEquals("/c", t.close("/b").activePath) // /c was active, closing /b keeps it
        assertEquals("/c", t.activate("/b").close("/b").activePath)
        assertEquals("/b", t.close("/c").activePath)
        assertEquals(listOf("/a", "/b"), paths(t.close("/c")))
    }

    @Test fun `closing the last tab leaves an empty strip with no active tab`() {
        val t = EditorTabs().open("/a").close("/a")
        assertEquals(emptyList<String>(), paths(t))
        assertNull(t.activePath)
        assertNull(t.active)
    }

    @Test fun `a blank path opens nothing`() {
        assertEquals(EditorTabs(), EditorTabs().open("  "))
    }

    @Test fun `the strip survives a restart, modes and active tab included`() {
        val t = EditorTabs().open("/a b.md", EditorMode.Edit).open("/sdcard/x\ty.txt").open("/c.pdf").activate("/a b.md")
        val back = EditorTabs.decode(t.encode())
        assertEquals(t, back)
        assertEquals(EditorMode.Edit, back.active?.mode)
    }

    @Test fun `garbage decodes to an empty strip and a stale active path falls back to the first tab`() {
        assertEquals(EditorTabs(), EditorTabs.decode("not a strip"))
        assertEquals(EditorTabs(), EditorTabs.decode(""))
        val t = EditorTabs.decode("v1\nactive\t%2Fgone\ntab\tView\t%2Fa\ntab\tEdit\t%2Fb\n")
        assertEquals("/a", t.activePath)
        assertEquals(listOf("/a", "/b"), paths(t))
    }
}

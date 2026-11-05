package com.diegonmarcos.cloudlib.editorcore

import org.junit.Assert.assertEquals
import org.junit.Test

class EditorModeTest {
    @Test fun `toggle flips View and Edit and back`() {
        assertEquals(EditorMode.Edit, EditorMode.View.toggled())
        assertEquals(EditorMode.View, EditorMode.Edit.toggled())
        assertEquals(EditorMode.View, EditorMode.View.toggled().toggled())
    }

    @Test fun `served goes back to view and is never reached by the toggle`() {
        assertEquals(EditorMode.View, EditorMode.Served.toggled())
        assertEquals(EditorMode.Served, EditorMode.of("Served"))
        val t = EditorTabs().open("/a.html").setMode("/a.html", EditorMode.Served)
        assertEquals(EditorMode.Served, EditorTabs.decode(t.encode()).active?.mode)
    }

    @Test fun `an unknown stored name reads as View`() {
        assertEquals(EditorMode.View, EditorMode.of(null))
        assertEquals(EditorMode.View, EditorMode.of("Nonsense"))
        assertEquals(EditorMode.Edit, EditorMode.of("Edit"))
    }

    @Test fun `the toggle of one tab leaves the others alone`() {
        val t = EditorTabs().open("/a.md").open("/b.md").toggleMode("/a.md")
        assertEquals(EditorMode.Edit, t.tabs.first { it.path == "/a.md" }.mode)
        assertEquals(EditorMode.View, t.tabs.first { it.path == "/b.md" }.mode)
        assertEquals(EditorMode.View, t.toggleMode("/a.md").tabs.first { it.path == "/a.md" }.mode)
    }
}

package com.diegonmarcos.clouddrive.editor

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1484 the glue between Explorer and the Editor: what it offers to open, and the hand-over. */
class EditorHookTest {
    @After fun reset() { EditorHook.pending.value = null; EditorHook.jump = null }

    @Test fun `the Editor is offered for the routed types and refused for Microsoft Office files`() {
        for (n in listOf("a.txt", "a.md", "a.html", "a.png", "a.mp3", "a.mp4", "a.pdf", "a.odt", "a.ods", "a.odp")) assertTrue(n, EditorHook.canOpen(n))
        for (n in listOf("a.doc", "a.docx", "a.xls", "a.xlsx", "a.ppt", "a.pptx", "a.bin", "noext")) assertFalse(n, EditorHook.canOpen(n))
    }

    @Test fun `opening queues the path and asks the shell for the Editor tab`() {
        var jumped = 0
        EditorHook.jump = { jumped++ }
        EditorHook.open("/sdcard/Documents/a.md")
        assertEquals("/sdcard/Documents/a.md", EditorHook.pending.value)
        assertEquals(1, jumped)
    }

    @Test fun `with no shell attached opening still queues and does not crash`() {
        assertNull(EditorHook.jump)
        EditorHook.open("/x.pdf")
        assertEquals("/x.pdf", EditorHook.pending.value)
    }
}

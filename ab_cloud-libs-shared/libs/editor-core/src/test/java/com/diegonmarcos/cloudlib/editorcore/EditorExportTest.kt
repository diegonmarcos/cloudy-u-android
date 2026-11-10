package com.diegonmarcos.cloudlib.editorcore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1607 the editor's Export dropdown contract: three actions, labelled, each with a stable test tag. */
class EditorExportTest {

    @Test fun threeExportActions() {
        assertEquals(listOf("CLIPBOARD", "DOWNLOAD", "PATH"), EditorExport.entries.map { it.name })
    }

    @Test fun defaultLabelsCoverExport() {
        val l = EditorLabels()
        assertTrue(l.export.isNotBlank())
        assertTrue(l.exportClipboard.isNotBlank())
        assertTrue(l.exportDownload.isNotBlank())
        assertTrue(l.exportPath.isNotBlank())
    }

    @Test fun exportTagsAreDistinctAndStable() {
        val tags = EditorExport.entries.map { EditorTags.export(it) }
        assertEquals(tags.size, tags.toSet().size)
        assertEquals("editor_export_clipboard", EditorTags.export(EditorExport.CLIPBOARD))
    }
}

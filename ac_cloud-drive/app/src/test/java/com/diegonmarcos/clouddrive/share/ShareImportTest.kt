package com.diegonmarcos.clouddrive.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** #1888 the share sheet's pure rules. */
class ShareImportTest {
    @Test fun streamsWinThenTextThenNothing() {
        assertEquals(listOf("content://a", "content://b"), ShareImport.plan(ShareImport.SEND_MULTIPLE, listOf("content://a", "content://b"), "cap"))
        assertEquals(listOf(ShareImport.TEXT_MARK), ShareImport.plan(ShareImport.SEND, emptyList(), "hello"))
        assertEquals(emptyList<String>(), ShareImport.plan(ShareImport.SEND, emptyList(), " "))
        assertEquals(emptyList<String>(), ShareImport.plan("android.intent.action.VIEW", listOf("x"), "y"))
        assertEquals(emptyList<String>(), ShareImport.plan(null, listOf("x"), null))
    }

    @Test fun namesAreSafeAndUnique() {
        assertEquals("a_b.md", ShareImport.safeName("dir/x/a:b.md".substringAfterLast('/')))
        assertEquals("file", ShareImport.safeName(" .. "))
        assertEquals("a.md", ShareImport.uniqueName("a.md", setOf("b.md")))
        assertEquals("a (1).md", ShareImport.uniqueName("a.md", setOf("A.md")))
        assertEquals("a (2).md", ShareImport.uniqueName("a.md", setOf("a.md", "a (1).md")))
        assertEquals("note", ShareImport.uniqueName("note", emptySet()))
        assertEquals("trip.txt", ShareImport.textName("trip", "body"))
        assertEquals("first line.txt", ShareImport.textName(null, "\nfirst line\nx"))
    }

    @Test fun treeIdsBecomePaths() {
        assertEquals("/storage/emulated/0/Download/x", ShareImport.docIdToPath("primary:Download/x"))
        assertEquals("/storage/emulated/0", ShareImport.docIdToPath("primary:"))
        assertEquals("/storage/1A2B-3C4D/Docs", ShareImport.docIdToPath("1A2B-3C4D:Docs"))
        assertNull(ShareImport.docIdToPath("raw"))
        assertNull(ShareImport.docIdToPath(":x"))
    }
}

class ShareImportSaveToTest {
    @Test fun renameOnlyForOneItem() {
        assertEquals(true, ShareImport.canRename(1))
        assertEquals(false, ShareImport.canRename(2))
        assertEquals(false, ShareImport.canRename(0))
        assertEquals("b.md", ShareImport.finalName("a.md", " b.md "))
        assertEquals("a.md", ShareImport.finalName("a.md", " "))
        assertEquals("a.md", ShareImport.finalName("a.md", null))
        assertEquals("x_y.md", ShareImport.finalName("a.md", "x:y.md"))
    }

    @Test fun lastFolderAndResultLines() {
        assertEquals("content://tree/1", ShareImport.initialTree("content://tree/1"))
        assertNull(ShareImport.initialTree(""))
        assertNull(ShareImport.initialTree(null))
        assertEquals("saved a.md", ShareImport.resultLine("a.md", true))
        assertEquals("failed a.md", ShareImport.resultLine("a.md", false))
    }
}

class ShareOpenWithTest {
    @Test fun pdfGoesToTheReaderEverythingElseToTheEditor() {
        assertEquals(ShareImport.OpenTarget.PDF_READER, ShareImport.openTarget("application/pdf", null))
        assertEquals(ShareImport.OpenTarget.PDF_READER, ShareImport.openTarget("application/octet-stream", "A.PDF"))
        assertEquals(ShareImport.OpenTarget.EDITOR, ShareImport.openTarget("text/markdown", "a.md"))
        assertEquals(ShareImport.OpenTarget.EDITOR, ShareImport.openTarget(null, null))
    }
}

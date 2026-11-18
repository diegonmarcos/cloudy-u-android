package com.diegonmarcos.cloudcanvas

import com.diegonmarcos.cloudcanvas.board.BoardStore
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** #1787 the Board page's storage: boards in app storage as tldraw store snapshots, with an index of names and the open board. */
class BoardTest {
    private fun dir(): File = Files.createTempDirectory("boards").toFile()
    private val snap = """{"store":{"page:page":{"id":"page:page","typeName":"page"}},"schema":{"schemaVersion":2}}"""

    @Test fun aFreshStoreOpensOneEmptyBoard() {
        val s = BoardStore(dir())
        val id = s.currentId()
        assertEquals(listOf(id), s.list().map { it.id })
        assertEquals(BoardStore.DEFAULT_NAME, s.list()[0].name)
        assertNull(s.read(id))
    }

    @Test fun createRenameDeleteKeepsAtLeastOneBoard() {
        val s = BoardStore(dir())
        val a = s.currentId()
        val b = s.create("Plan")
        assertEquals(b.id, s.currentId())
        assertEquals("Plan 2", s.create("Plan").name)       // names stay unique
        assertEquals("Ideas", s.rename(a, "  Ideas\n").name)
        assertEquals(b.name, s.rename(b.id, "   ").name)     // a blank name keeps the old one
        s.open(a); assertEquals(a, s.currentId())
        s.delete(a)
        assertFalse(s.list().any { it.id == a })
        assertTrue(s.list().any { it.id == s.currentId() })
        s.list().map { it.id }.forEach { s.delete(it) }
        assertEquals(1, s.list().size)                       // never zero boards
    }

    @Test fun writeIsAtomicAndRefusesForeignJson() {
        val d = dir()
        val s = BoardStore(d)
        val id = s.currentId()
        s.write(id, snap)
        assertEquals(snap, s.read(id))
        assertFalse(File(d, "$id.tldr.tmp").exists())
        for (bad in listOf("not json", "{\"foo\":1}", "[]")) {
            try { s.write(id, bad); fail("accepted $bad") } catch (e: Exception) { }
        }
        assertEquals(snap, s.read(id))                       // a refused write keeps the saved board
        try { s.write("../etc", snap); fail("accepted a path") } catch (e: IllegalArgumentException) { }
        try { s.write("board-999", snap); fail("accepted an unknown board") } catch (e: IllegalArgumentException) { }
        val before = s.list().first { it.id == id }.updated
        s.write(id, snap)
        assertTrue(s.list().first { it.id == id }.updated > before)
    }

    @Test fun theIndexSurvivesAReopen() {
        val d = dir()
        val s = BoardStore(d)
        val a = s.currentId(); s.write(a, snap)
        val b = s.create("Second")
        val again = BoardStore(d)
        assertEquals(listOf(a, b.id), again.list().map { it.id })
        assertEquals(b.id, again.currentId())
        assertEquals(snap, again.read(a))
        // a corrupt index is not fatal: the store starts over with one board
        File(d, "boards.json").writeText("{broken")
        val fresh = BoardStore(d)
        fresh.currentId()
        assertEquals(1, fresh.list().size)
    }
}

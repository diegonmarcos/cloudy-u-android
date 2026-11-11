package com.diegonmarcos.cloudwriter.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DocStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private var now = 1_700_000_000_000L
    private fun store(dir: File = tmp.root) = DocStore(dir) { now }

    @Test fun `create writes the text under a fresh id`() {
        val s = store()
        val id = s.create("# Hello\nworld")
        assertTrue(id.matches(Regex("[a-z0-9]+")))
        assertEquals("# Hello\nworld", s.read(id))
        assertTrue(File(tmp.root, "$id.md").isFile)
        assertEquals("", s.read(s.create())!!)
    }

    @Test fun `two documents created in the same millisecond get different ids`() {
        val s = store()
        assertNotEquals(s.create("a"), s.create("b"))
        assertEquals(2, s.list().size)
    }

    @Test fun `the list is newest first and carries title, snippet and words`() {
        val s = store()
        val a = s.create("# Alpha\nfirst body")
        now += 60_000
        val b = s.create("Beta")
        val list = s.list()
        assertEquals(listOf(b, a), list.map { it.id })
        val alpha = list[1]
        assertEquals("Alpha", alpha.title)
        assertEquals("first body", alpha.snippet)
        assertEquals(4, alpha.words)
        assertEquals(1_700_000_000_000L, alpha.modified)
        now += 60_000
        s.write(a, "# Alpha 2")
        assertEquals(listOf(a, b), s.list().map { it.id })
        assertEquals("Alpha 2", s.list()[0].title)
    }

    @Test fun `equal times order by id, newest id first`() {
        val s = store()
        val a = s.create("a")
        val b = s.create("b")
        assertEquals(listOf(b, a).sortedDescending(), s.list().map { it.id })
    }

    @Test fun `append spaces a segment after the text and refuses a missing document`() {
        val s = store()
        val id = s.create("One.")
        assertTrue(s.append(id, "Two."))
        assertEquals("One. Two.", s.read(id))
        assertTrue(s.append(id, "Speaker 2: Three."))
        assertEquals("One. Two.\nSpeaker 2: Three.", s.read(id))
        assertFalse(s.append("zzz", "x"))
        assertNull(s.read("zzz"))
    }

    @Test fun `delete removes the file`() {
        val s = store()
        val id = s.create("x")
        assertTrue(s.delete(id))
        assertNull(s.read(id))
        assertTrue(s.list().isEmpty())
        assertFalse(s.delete(id))
    }

    @Test fun `search matches text ignoring case, and a blank query is everything`() {
        val s = store()
        val pie = s.create("Apple pie\nwith cream")
        s.create("banana")
        assertEquals(listOf(pie), s.search("CREAM").map { it.id })
        assertEquals(2, s.search("  ").size)
        assertTrue(s.search("kiwi").isEmpty())
        assertEquals(listOf(pie), s.search(" apple ").map { it.id })
    }

    @Test fun `only markdown files are documents, and no temp file is left behind`() {
        val s = store()
        File(tmp.root, "notes.txt").writeText("x")
        val id = s.create("y")
        s.write(id, "z")
        assertEquals(listOf(id), s.list().map { it.id })
        assertTrue(tmp.root.list()!!.none { it.endsWith(".tmp") })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an id that is a path is refused`() {
        store().read("../escape")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an upper-case id is refused`() {
        store().write("ABC", "x")
    }

    @Test fun `the directory is created when missing`() {
        val dir = File(tmp.root, "a/b/docs")
        val s = store(dir)
        assertTrue(dir.isDirectory)
        s.create("x")
        assertEquals(1, s.list().size)
    }
}

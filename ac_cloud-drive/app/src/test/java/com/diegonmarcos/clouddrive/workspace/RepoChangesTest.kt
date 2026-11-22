package com.diegonmarcos.clouddrive.workspace

import com.diegonmarcos.cloudlib.gitsync.GitChange
import com.diegonmarcos.cloudlib.gitsync.GitFileStatus
import com.diegonmarcos.superapp.repoworkspace.RepoWorkspace
import com.diegonmarcos.superapp.repoworkspace.RepoWorkspace.ChangedFile
import com.diegonmarcos.superapp.repoworkspace.RepoWorkspace.RepoInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1855 P4a libs:repo-workspace v2, the server's pure rules: status mapping, row building, diff bounding, path validation, hand-off refusal. */
class RepoChangesTest {
    @Test fun `status letters map every change kind and a rename`() {
        assertEquals("A", RepoChanges.letter(GitChange.ADDED))
        assertEquals("M", RepoChanges.letter(GitChange.MODIFIED))
        assertEquals("D", RepoChanges.letter(GitChange.DELETED))
        assertEquals("?", RepoChanges.letter(GitChange.UNTRACKED))
        assertEquals("R", RepoChanges.letter("renamed"))
        assertEquals("M", RepoChanges.letter("something else"))
    }

    @Test fun `a file changed on both sides has two rows and counts come from the matching diff`() {
        val files = listOf(
            GitFileStatus("b.txt", staged = GitChange.MODIFIED, unstaged = GitChange.MODIFIED),
            GitFileStatus("a.txt", staged = null, unstaged = GitChange.UNTRACKED),
            GitFileStatus("c.txt", staged = GitChange.DELETED, unstaged = null),
        )
        val rows = RepoChanges.rows(files, mapOf("b.txt" to (3 to 1), "c.txt" to (0 to 9)), mapOf("b.txt" to (7 to 2))) { 5 }
        assertEquals(listOf(
            ChangedFile("a.txt", "?", false, 5, 0),
            ChangedFile("b.txt", "M", true, 3, 1), ChangedFile("b.txt", "M", false, 7, 2),
            ChangedFile("c.txt", "D", true, 0, 9),
        ), rows)
    }

    @Test fun `a conflicting file is still a row`() {
        val rows = RepoChanges.rows(listOf(GitFileStatus("x", conflicting = true)), emptyMap(), emptyMap())
        assertEquals(listOf(ChangedFile("x", "M", false)), rows)
    }

    @Test fun `a diff under the bound is returned whole`() {
        assertEquals("a\nb\n", RepoChanges.bounded("a\nb\n", 100))
    }

    @Test fun `a diff over the bound is cut on a line and marked`() {
        val text = (1..100).joinToString("\n") { "+line $it" } + "\n"
        val cut = RepoChanges.bounded(text, 50)
        assertTrue(cut.endsWith(RepoWorkspace.DIFF_TRUNCATED + "\n"))
        assertTrue(cut.removeSuffix(RepoWorkspace.DIFF_TRUNCATED + "\n").toByteArray().size <= 50)
        cut.lines().dropLast(2).forEach { assertTrue(it, it.matches(Regex("\\+line \\d+"))) }
    }

    @Test fun `bounding never splits a multibyte character and the server caps the ask`() {
        val cut = RepoChanges.bounded("ééééé\néé\n", 7)
        assertFalse(cut.contains('�'))
        assertEquals(RepoWorkspace.DIFF_MAX_BYTES, RepoChanges.bound(Int.MAX_VALUE))
        assertEquals(1, RepoChanges.bound(-5))
    }

    @Test fun `relative paths refuse traversal absolute and control characters`() {
        assertTrue(RepoWorkspace.validRelPath("src/a.kt"))
        assertFalse(RepoWorkspace.validRelPath("../etc/passwd"))
        assertFalse(RepoWorkspace.validRelPath("a/../../b"))
        assertFalse(RepoWorkspace.validRelPath("/abs"))
        assertFalse(RepoWorkspace.validRelPath("a\\b"))
        assertFalse(RepoWorkspace.validRelPath("a\u0000b"))
        assertFalse(RepoWorkspace.validRelPath(""))
        assertFalse(RepoWorkspace.validRelPath(null))
        assertFalse(RepoWorkspace.validRelPath("a//b"))
    }

    private val listed = listOf(RepoInfo("alpha", "/store/git/alpha", "store"))

    @Test fun `review is for a listed repository path only`() {
        assertEquals("", RepoChanges.reviewRefusal(listed, "/store/git/alpha"))
        assertTrue(RepoChanges.reviewRefusal(listed, "/store/git/other").isNotEmpty())
        assertTrue(RepoChanges.reviewRefusal(listed, "/etc").isNotEmpty())
        assertTrue(RepoChanges.reviewRefusal(listed, "").isNotEmpty())
        assertTrue(RepoChanges.reviewRefusal(listed, null).isNotEmpty())
    }

    @Test fun `the commit and push hand-off also needs the clone on the Git page`() {
        assertEquals("", RepoChanges.handOffRefusal(listed, setOf("/store/git/alpha"), "/store/git/alpha/"))
        assertTrue(RepoChanges.handOffRefusal(listed, emptySet(), "/store/git/alpha").contains("Git page"))
        assertTrue(RepoChanges.handOffRefusal(listed, setOf("/x"), "/x").isNotEmpty())
    }

    @Test fun `a diff is only served for a file that has a change`() {
        val changed = listOf(ChangedFile("a.txt", "M", false))
        assertEquals("", RepoChanges.diffRefusal(changed, "a.txt"))
        assertTrue(RepoChanges.diffRefusal(changed, "b.txt").isNotEmpty())
        assertTrue(RepoChanges.diffRefusal(changed, "../a.txt").isNotEmpty())
    }

    @Test fun `rows survive the wire and a hostile row decodes to null`() {
        val f = ChangedFile("dir/a b.txt", "R", true, 4, 2)
        assertEquals(f, ChangedFile.decode(f.encode()))
        assertNull(ChangedFile.decode("a\tX\t1\t0\t0"))
        assertNull(ChangedFile.decode(""))
        assertEquals(ChangedFile("a", "M", false, 0, 0), ChangedFile.decode("a\tM"))
    }

    @Test fun `diff line kinds`() {
        assertEquals('+', DiffLines.kind("+x")); assertEquals('-', DiffLines.kind("-x")); assertEquals('@', DiffLines.kind("@@ -1 +1 @@"))
        assertEquals('h', DiffLines.kind("+++ b/x")); assertEquals('h', DiffLines.kind("--- a/x")); assertEquals(' ', DiffLines.kind(" ctx"))
    }
}

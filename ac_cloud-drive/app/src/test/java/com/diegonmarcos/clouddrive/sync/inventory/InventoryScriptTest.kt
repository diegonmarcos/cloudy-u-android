package com.diegonmarcos.clouddrive.sync.inventory

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1315 the one discovery pass: the script's lines parse into entries in HOST form, a line the shell could not read is counted and
 * never a row, and the java.io walk of the same shape finds the same kinds (tree, bare, debris, never a `.git` FILE pointing outside).
 * Mutations proven red: swapping the origin and branch columns (parseReadsEveryColumn), dropping the debris prune (walkNamesDebris),
 * translating the guest path with a plain prefix strip (parseMapsGuestToHost: the `..` line must be skipped).
 */
class InventoryScriptTest {
    private val host: (String) -> String? = { g -> if (g == "/root/s" || g.startsWith("/root/s/")) (if (g.split('/').any { it == ".." }) null else "/data/user/0/o/files/home/s" + g.removePrefix("/root/s")) else null }

    @Test fun `parse reads every column of a tree line and the bare and debris shapes`() {
        val out = "tree\t/root/s/git/a\thttps://x/o/a.git\tmain\torigin/main\t1700000000\t1\n" +
            "bare\t/root/s/git/_bare/o/b.git\thttps://x/o/b.git\n" +
            "debris\t/root/s/git/c.incoming\n" +
            "gitfile\t/root/s/git/wt\n" +
            "tree\t/root/s/git/noorigin\t\t\t\t\t0\n" +
            "tree\t/root/s/git/d.git\thttps://x/o/d.git\tmain\t\t5\t0\t1\n"
        val p = InventoryScript.parse(out, host)
        assertEquals(0, p.malformed)
        val a = p.entries.first { it.name == "a" }
        assertEquals(InventoryScript.Kind.TREE, a.kind)
        assertEquals("/data/user/0/o/files/home/s/git/a", a.path)
        assertEquals("https://x/o/a.git", a.origin); assertEquals("main", a.branch); assertEquals("origin/main", a.upstream)
        assertEquals(1700000000_000L, a.headTimeMs); assertTrue(a.shallow)
        val b = p.entries.first { it.name == "b.git" }
        assertEquals("#1491 a bare repo is a TREE with the bare attribute", InventoryScript.Kind.TREE, b.kind); assertTrue(b.bare); assertFalse(a.bare); assertEquals("b", b.repoName); assertEquals("https://x/o/b.git", b.origin)
        assertEquals(InventoryScript.Kind.DEBRIS, p.entries.first { it.name == "c.incoming" }.kind)
        assertEquals(InventoryScript.Kind.GITFILE, p.entries.first { it.name == "wt" }.kind)
        val n = p.entries.first { it.name == "noorigin" }
        assertEquals("", n.origin); assertNull(n.branch); assertNull(n.upstream); assertFalse(n.shallow)
    }

    @Test fun `a malformed line is counted, never a row, and an outside path is skipped`() {
        val p = InventoryScript.parse("nonsense\n\t\ntree\t/elsewhere/x\turl\nbare\t/root/s/git/ok.git\t\n", host)
        assertEquals(2, p.malformed)
        assertEquals(1, p.skippedOutside)
        assertEquals(listOf("ok.git"), p.entries.map { it.name })
    }

    @Test fun `parse maps the guest path to the host form and refuses an escape`() {
        val p = InventoryScript.parse("tree\t/root/s/git/../x\turl\nbare\t/root/s/a.git\t\n", host)
        assertEquals(listOf("/data/user/0/o/files/home/s/a.git"), p.entries.map { it.path })
    }

    @Test fun `the script lists the roots, prunes debris and hidden folders, and reads the git facts`() {
        val s = InventoryScript.build(listOf("/root/s/git", "/root/s/other"), 3, listOf("*.incoming", "bad name", "*"), ::q)
        assertTrue(s.startsWith("for r in '/root/s/git' '/root/s/other'; do"))
        assertTrue("one .git per repository, pruned", s.contains("-name .git -prune -print"))
        assertTrue("debris pruned and printed", s.contains("-name '*.incoming'"))
        assertFalse("an unsafe glob never reaches the shell", s.contains("bad name"))
        assertFalse("the bare * glob is never a debris pattern", s.contains("-name '*' "))
        assertTrue("hidden and Android pruned", s.contains("-name '.*' -o -name Android"))
        assertTrue("depth counts the .git itself", s.contains("-maxdepth 4"))
        for (fact in listOf("remote get-url origin", "symbolic-ref -q --short HEAD", "--abbrev-ref '@{u}'", "log -1 --format=%ct", "shallow")) assertTrue(fact, s.contains(fact))
        assertTrue("never a non-zero exit from the pass itself", s.trimEnd().endsWith("true"))
    }

    @Test fun `the java io walk finds trees, bares and debris and skips a git file pointing outside`() {
        val root = Files.createTempDirectory("inv").toFile()
        try {
            File(root, "a/.git").mkdirs(); File(root, "a/.git/HEAD").writeText("ref: refs/heads/main\n")
            File(root, "a/.git/config").writeText("[core]\n\trepositoryformatversion = 0\n[remote \"origin\"]\n\turl = https://x/o/a.git\n[branch \"main\"]\n\tremote = origin\n\tmerge = refs/heads/main\n")
            File(root, "a/.git/shallow").writeText("abc\n")
            File(root, "deep/er/b/.git").mkdirs()
            File(root, "m.git").mkdirs(); File(root, "m.git/HEAD").writeText("ref: refs/heads/main\n"); File(root, "m.git/objects").mkdirs()
            File(root, "m.git/config").writeText("[remote \"origin\"]\n\turl = https://x/o/m.git\n")
            File(root, "c.incoming/.git").mkdirs()
            File(root, ".hidden/.git").mkdirs()
            File(root, "wt").mkdirs(); File(root, "wt/.git").writeText("gitdir: ../a/.git/worktrees/wt\n")
            File(root, "plain").mkdirs()
            val found = InventoryScript.walkIo(listOf(root), 3, 100, Long.MAX_VALUE, listOf("*.incoming"))
            val byName = found.associateBy { it.name }
            assertEquals(InventoryScript.Kind.TREE, byName.getValue("a").kind)
            assertEquals("https://x/o/a.git", byName.getValue("a").origin); assertEquals("main", byName.getValue("a").branch); assertEquals("origin/main", byName.getValue("a").upstream); assertTrue(byName.getValue("a").shallow)
            assertEquals(InventoryScript.Kind.TREE, byName.getValue("b").kind)
            assertEquals(InventoryScript.Kind.TREE, byName.getValue("m.git").kind); assertTrue(byName.getValue("m.git").bare); assertEquals("https://x/o/m.git", byName.getValue("m.git").origin)
            assertEquals(InventoryScript.Kind.DEBRIS, byName.getValue("c.incoming").kind)
            assertEquals(InventoryScript.Kind.GITFILE, byName.getValue("wt").kind)
            assertNull(".hidden is never entered", byName[".hidden"])
            assertNull("a plain folder is not a row", byName["plain"])
            assertTrue("a root is not its own row", found.none { it.path == root.absolutePath })
        } finally { root.deleteRecursively() }
    }

    @Test fun `the walk honours the depth and folder bounds`() {
        val root = Files.createTempDirectory("inv").toFile()
        try {
            File(root, "l1/l2/l3/l4/deep/.git").mkdirs()
            File(root, "near/.git").mkdirs()
            assertEquals(listOf("near"), InventoryScript.walkIo(listOf(root), 2, 1000, Long.MAX_VALUE, emptyList()).map { it.name })
            assertEquals(listOf("deep", "near").sorted(), InventoryScript.walkIo(listOf(root), 6, 1000, Long.MAX_VALUE, emptyList()).map { it.name }.sorted())
            assertTrue("max_dirs bounds the walk", InventoryScript.walkIo(listOf(root), 6, 1, Long.MAX_VALUE, emptyList()).isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun `entries round-trip through the persisted form`() {
        val l = listOf(
            InventoryScript.Entry("/h/a", InventoryScript.Kind.TREE, "https://x/a.git", "main", "origin/main", 5000L, true),
            InventoryScript.Entry("/h/b.git", InventoryScript.Kind.TREE, "https://x/b.git", bare = true),
            InventoryScript.Entry("/h/c.incoming", InventoryScript.Kind.DEBRIS),
        )
        assertEquals(l, InventoryScript.decode(InventoryScript.encode(l)))
        assertEquals(emptyList<InventoryScript.Entry>(), InventoryScript.decode(null))
        assertEquals(emptyList<InventoryScript.Entry>(), InventoryScript.decode("WHAT\t/x\n"))
    }

    @Test fun `only plain name globs are safe`() {
        assertTrue(InventoryScript.safeGlob("*.incoming")); assertTrue(InventoryScript.safeGlob("tmp_pack_*")); assertTrue(InventoryScript.safeGlob(".store-stage"))
        assertFalse(InventoryScript.safeGlob("*")); assertFalse(InventoryScript.safeGlob("a b")); assertFalse(InventoryScript.safeGlob("x/y")); assertFalse(InventoryScript.safeGlob("'")); assertFalse(InventoryScript.safeGlob(""))
    }

    private fun q(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}

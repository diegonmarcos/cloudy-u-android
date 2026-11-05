package com.diegonmarcos.clouddrive.sync

import com.diegonmarcos.clouddrive.Declarations
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1282 Clone bare: path, one bare per repository (origin URL + renamed_from), update instead of a second clone. */
class BareClonesTest {
    private val renames = mapOf("cloud-infra" to "cloudy-t-infra", "git-repos-master" to "_git-repos-master")
    private val canonical: (String) -> String = { renames[it] ?: it }
    private val q: (String) -> String = { "'$it'" }
    private val root = File(System.getProperty("user.dir")).let { if (File(it, "build.json").isFile) it else it.parentFile }

    @Test fun `the mirror path is root, owner, canonical repo dot git`() {
        assertEquals("/s/git/_bare/diegonmarcos/cloudy-t-infra.git", BareClones.path("/s/git/", "_bare", "https://github.com/diegonmarcos/cloud-infra.git", "cloud-infra", canonical))
        assertEquals("/s/git/_bare/diegonmarcos/_git-repos-master.git", BareClones.path("/s/git", "_bare", "git@github.com:diegonmarcos/_git-repos-master.git", "", canonical))
        assertNull("an unsafe root is refused", BareClones.path("/s/git", "../x", "https://github.com/o/r.git", "r", canonical))
        assertFalse(BareClones.validRoot("/abs")); assertTrue(BareClones.validRoot("_bare/mirrors"))
        assertEquals("o", BareClones.ownerOf("ssh://git@host:22/o/r.git"))
    }

    @Test fun `one bare per repository - an existing mirror under an old name is updated, duplicates collapse`() {
        val mirrors = listOf(BareClones.Mirror("/g/_bare/diegonmarcos/cloud-infra.git", "https://github.com/diegonmarcos/cloud-infra.git"))
        val targets = listOf(
            BareClones.Target("cloudy-t-infra", "https://github.com/diegonmarcos/cloudy-t-infra.git"),
            BareClones.Target("cloud-me", "https://github.com/diegonmarcos/cloud-me.git"),
            BareClones.Target("cloud-me", "git@github.com:diegonmarcos/cloud-me.git"),
        )
        val p = BareClones.plan(targets, mirrors, canonical)
        assertEquals(mapOf("cloudy-t-infra" to "/g/_bare/diegonmarcos/cloud-infra.git"), p.toUpdate)
        assertEquals(listOf("cloud-me"), p.toClone.map { it.name })
        assertEquals(mapOf("cloudy-t-infra" to "/g/_bare/diegonmarcos/cloud-infra.git"), BareClones.index(mirrors, canonical))
    }

    @Test fun `origin is read from a bare config and the commands never touch a working clone`() {
        val cfg = "[core]\n\tbare = true\n[remote \"upstream\"]\n\turl = x\n[remote \"origin\"]\n\turl = https://github.com/o/r.git\n\tfetch = +refs/*:refs/*\n\tmirror = true\n"
        assertEquals("https://github.com/o/r.git", BareClones.originOfConfig(cfg))
        assertEquals("clone --mirror 'u' '/g/_bare/o/r.git'", BareClones.cloneArgs("u", "/g/_bare/o/r.git", 0, q))
        assertEquals("clone --mirror --depth 1 'u' 'd'", BareClones.cloneArgs("u", "d", 1, q))
        assertEquals("-C 'd' remote update --prune", BareClones.updateArgs("d", q))
    }

    @Test fun `#1491 the old clone_bare kinds are gone and the bare root stays a plain relative folder`() {
        val git = Declarations.parseGitPage(Json.parseToJsonElement(File(root, "build.json").readText()).jsonObject["ui"]!!.jsonObject["sync"]!!.jsonObject["git"])
        assertEquals(setOf(Declarations.DEPTH_CLONE_MODE, Declarations.DEPTH_KEEP), git.depthActions.map { it.kind }.toSet())
        assertTrue(BareClones.validRoot(git.bareRoot))
    }

    @Test fun `a bare mirror is depth 1 - the clone is shallow and the update is a depth fetch, never remote update`() {
        assertEquals("clone --mirror --depth 1 'u' 'd'", BareClones.cloneArgs("u", "d", 1, q))
        assertEquals("-C 'd' fetch --prune --depth 1 origin", BareClones.updateArgs("d", q, 1))
        assertEquals("-C 'd' remote update --prune", BareClones.updateArgs("d", q, 0))
        val auth: (String) -> String = { "git $it" }
        val full = BareClones.updateScript("/g/m.git", 1, q, auth)
        assertFalse("depth 1 never runs remote update", full.contains("remote update"))
        assertTrue(full.contains("fetch --prune --depth 1 origin"))
        assertEquals("depth 0 keeps the plain update", "set -e; git -C '/g/m.git' remote update --prune", BareClones.updateScript("/g/m.git", 0, q, auth))
    }

    @Test fun `a full-history mirror is shrunk - fetch then reflog expire then gc, and the freed bytes are read back`() {
        val s = BareClones.updateScript("/g/m.git", 1, q) { "git $it" }
        val at = listOf("fetch --prune --depth 1 origin", "reflog expire --expire=now --all", "gc --prune=now").map { s.indexOf(it) }
        assertTrue("fetch, expire, gc in that order: $at", at.none { it < 0 } && at == at.sorted())
        assertTrue("a mirror without a shallow file is shrunk", s.contains("[ ! -f \"\$d/shallow\" ]"))
        assertTrue("a deeper-than-declared shallow mirror is shrunk", s.contains("\$((tips * 1))"))
        assertEquals(BareClones.UpdateOutcome(true, 1500L * 1024), BareClones.parseUpdate("shrunk=1\nfreed_kb=1500\n"))
        assertEquals("an already depth-1 mirror reports nothing freed", BareClones.UpdateOutcome(false, 0), BareClones.parseUpdate("shrunk=0\nfreed_kb=12\n"))
        assertEquals("a mirror that grew frees nothing", 0L, BareClones.parseUpdate("shrunk=1\nfreed_kb=-5").freedBytes)
    }

    @Test fun `the shrink script really shrinks a full local mirror to one commit per ref`() {
        val sh = File("/bin/sh"); val git = listOf("/usr/bin/git", "/bin/git").firstOrNull { File(it).canExecute() }
        org.junit.Assume.assumeTrue(sh.canExecute() && git != null)
        val tmp = java.nio.file.Files.createTempDirectory("bare1314").toFile()
        fun run(dir: File, vararg c: String) = ProcessBuilder(*c).directory(dir).redirectErrorStream(true).start().let { p -> val o = p.inputStream.bufferedReader().readText(); check(p.waitFor() == 0) { c.joinToString(" ") + "\n" + o }; o }
        try {
            val src = File(tmp, "src").apply { mkdirs() }
            run(src, "git", "init", "-q", "-b", "main"); run(src, "git", "config", "user.email", "a@b"); run(src, "git", "config", "user.name", "a")
            repeat(5) { i -> File(src, "f$i").writeText("x$i"); run(src, "git", "add", "-A"); run(src, "git", "commit", "-q", "-m", "c$i") }
            val m = File(tmp, "m.git"); run(tmp, "git", "clone", "-q", "--mirror", "file://${src.absolutePath}", m.absolutePath)
            run(tmp, "git", "-C", src.absolutePath, "config", "uploadpack.allowFilter", "true")
            assertEquals("5", run(m, "git", "rev-list", "--all", "--count").trim())
            val out = run(tmp, "sh", "-c", BareClones.updateScript(m.absolutePath, 1, { "'$it'" }) { "git $it" })
            assertEquals("1", run(m, "git", "rev-list", "--all", "--count").trim())
            assertTrue(File(m, "shallow").isFile)
            assertTrue(out, BareClones.parseUpdate(out).shrunk)
            val again = run(tmp, "sh", "-c", BareClones.updateScript(m.absolutePath, 1, { "'$it'" }) { "git $it" })
            assertFalse("an already depth 1 mirror is not gc'd again", BareClones.parseUpdate(again).shrunk)
        } finally { tmp.deleteRecursively() }
    }
}

package com.diegonmarcos.clouddrive.sync

import com.diegonmarcos.cloudlib.gitsync.ManagedRepo
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * THE STATUS IS THE DISK'S, PROVEN WITH REAL GIT. Origins with two branches are cloned BARE and SINGLE-BRANCH (the production argument
 * builder [BareClones.cloneArgs], run through [StoreGit.authed] in a real `sh`), the status model is read, every clone is deleted, the model is
 * read again, and they are cloned again. At every step the status model (CloneTruth.live + the page's summary line + the folder scan) must
 * equal the folder on disk, and every clone must hold exactly one branch: main. Nothing here is mocked.
 */
class CloneCycleTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun sh(dir: File?, vararg cmd: String, stdin: String = ""): Pair<Int, String> {
        val pb = ProcessBuilder(*cmd).redirectErrorStream(true)
        if (dir != null) pb.directory(dir)
        pb.environment()["GIT_TERMINAL_PROMPT"] = "0"
        val p = pb.start()
        p.outputStream.use { it.write(stdin.toByteArray()) }
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor(120, TimeUnit.SECONDS)) { "timeout: ${cmd.toList()}" }
        return p.exitValue() to out
    }

    private fun git(dir: File, vararg a: String): String {
        val (rc, out) = sh(dir, "git", "-c", "user.name=t", "-c", "user.email=t@t", "-c", "init.defaultBranch=main", *a)
        assertEquals("git ${a.toList()} -> $out", 0, rc)
        return out
    }

    private fun origin(root: File, name: String): File {
        val d = File(root, "$name.src").apply { mkdirs() }
        git(d, "init", "-q", "-b", "main")
        File(d, "f.txt").writeText(name)
        git(d, "add", "-A"); git(d, "commit", "-q", "-m", "one")
        git(d, "branch", "extra"); git(d, "branch", "feature/x") // other branches that must NEVER reach the device
        return d
    }

    private val q = { s: String -> "'" + s.replace("'", "'\\''") + "'" }
    private fun cloneBare(origin: File, dest: File) {
        val (rc, out) = sh(null, "sh", "-c", "set -e; mkdir -p ${q(dest.parent!!)}; " + StoreGit.authed(BareClones.cloneArgs("file://${origin.absolutePath}", dest.absolutePath, 0, q)))
        assertEquals("bare clone of ${origin.name}: $out", 0, rc)
    }

    private val labels = { k: String -> if (k == "c_cloned") "cloned" else "not cloned" }
    private fun summary(total: Int, cloned: Int) = GitTopBarState.summary(listOf("cloned", "not_cloned"), total, cloned, 0, 0, 0, "", labels)

    private fun assertStatus(step: String, declared: List<ManagedRepo>, expectedCloned: Int, clonesDir: File) {
        val live = CloneTruth.live(declared, CloneTruth::isCloneDir)
        val onDisk = (clonesDir.listFiles() ?: emptyArray()).filter { File(it, "HEAD").isFile && File(it, "objects").isDirectory }.map { it.absolutePath }.toSet()
        assertEquals("$step: model count", expectedCloned, live.size)
        assertEquals("$step: model == disk", onDisk, live.map { File(it.path).absolutePath }.toSet())
        assertEquals("$step: scan == disk", onDisk, CloneTruth.scan(clonesDir).map { it.absolutePath }.toSet())
        assertEquals("$step: summary", "$expectedCloned cloned · ${declared.size - expectedCloned} not cloned", summary(declared.size, live.size))
        declared.forEach { r -> assertEquals("$step: ${r.name}", File(r.path).absolutePath in onDisk, r in live) }
    }

    private fun assertOneBranch(dir: File) {
        val heads = git(dir, "for-each-ref", "--format=%(refname)", "refs/heads").lines().filter { it.isNotBlank() }
        assertEquals("${dir.name} local branches", listOf("refs/heads/main"), heads)
        val all = git(dir, "for-each-ref", "--format=%(refname)").lines().filter { it.startsWith("refs/heads") || it.startsWith("refs/remotes") }
        assertEquals("${dir.name} branch refs", listOf("refs/heads/main"), all)
        assertFalse("${dir.name} is bare: no working files", File(dir, "f.txt").exists())
    }

    @Test fun `clone all bare, delete all, clone all again - the status follows the disk at every step, one branch each`() {
        val src = tmp.newFolder("src")
        val clones = File(tmp.newFolder("store"), "git")
        val names = (1..8).map { "repo$it" }
        val origins = names.associateWith { origin(src, it) }
        val declared = names.map { ManagedRepo(id = it, name = it, path = File(clones, "$it.git").absolutePath) }

        assertStatus("empty folder, 8 declared", declared, 0, clones)

        // a. clone all bare, single branch
        names.forEach { cloneBare(origins.getValue(it), File(clones, "$it.git")) }
        // b. every repo CLONED
        assertStatus("after first clone", declared, 8, clones)
        declared.forEach { assertOneBranch(File(it.path)) }

        // c. delete all   d. every repo NOT cloned, count 0
        clones.listFiles()!!.forEach { assertTrue(it.deleteRecursively()) }
        assertStatus("after delete", declared, 0, clones)
        assertEquals("0 cloned · 8 not cloned", summary(8, CloneTruth.live(declared, CloneTruth::isCloneDir).size))

        // e. clone again   f. all CLONED again
        names.forEach { cloneBare(origins.getValue(it), File(clones, "$it.git")) }
        assertStatus("after second clone", declared, 8, clones)
        declared.forEach { assertOneBranch(File(it.path)) }

        // a partial delete drops exactly that many; an empty .git folder and a plain folder are never clones
        File(clones, "repo3.git").deleteRecursively(); File(clones, "repo5.git").deleteRecursively()
        File(clones, "empty/.git").mkdirs(); File(clones, "plain").mkdirs()
        assertStatus("after partial delete", declared, 6, clones)
    }

    @Test fun `a plain single-branch clone holds only main and counts as cloned`() {
        val src = tmp.newFolder("src2"); val o = origin(src, "w")
        val dest = File(tmp.newFolder("c2"), "w")
        val (rc, out) = sh(null, "sh", "-c", StoreGit.cloneScript("file://${o.absolutePath}", dest.absolutePath, null), stdin = "")
        assertEquals(out, 0, rc)
        assertTrue(CloneTruth.isCloneDir(dest))
        val refs = git(dest, "for-each-ref", "--format=%(refname)").lines().filter { it.isNotBlank() }
        assertEquals(listOf("refs/heads/main"), refs.filter { it.startsWith("refs/heads/") })
        // remote-tracking refs (origin/HEAD, origin/main) are expected; no other branch may have been fetched
        assertTrue(refs.toString(), refs.filter { it.startsWith("refs/remotes/") }.all { it == "refs/remotes/origin/main" || it == "refs/remotes/origin/HEAD" })
        dest.deleteRecursively()
        assertFalse(CloneTruth.isCloneDir(dest))
    }
}

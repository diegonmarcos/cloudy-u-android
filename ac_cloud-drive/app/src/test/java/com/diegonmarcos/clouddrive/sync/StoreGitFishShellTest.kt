package com.diegonmarcos.clouddrive.sync

import com.diegonmarcos.cloudlib.sharedstore.StoreExec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * #1353 every git job in cld.termux (clone, bare clone, sync = pull + push) failed: the guest's login shell is fish
 * (ac_cloud-termux rootfs.json default_shell), StoreExec handed it sh text quoted twice, and fish refused it
 * ("fish: Unsupported use of '='") before sh ran. This runs the REAL strings (StoreExec.guestWrap / jobStart,
 * StoreGit.cloneScript / syncScript / authed, BareClones.cloneArgs) the way cld.termux does: /bin/sh <termux shell> -c LINE,
 * the termux shell exec'ing fish like enter.sh -> login-exec -> root's login shell. Needs fish and git on PATH (CI installs fish).
 */
class StoreGitFishShellTest {

    private lateinit var home: File
    private lateinit var shellFile: File

    @Before fun setUp() {
        assertTrue("fish must be on PATH (the guest's login shell; the Ship workflow installs it)", which("fish"))
        assertTrue("git must be on PATH", which("git"))
        home = Files.createTempDirectory("fishguest").toFile()
        shellFile = File(home, ".termux/shell").apply { parentFile!!.mkdirs(); writeText("#!/bin/sh\nexec fish \"\$@\"\n"); setExecutable(true) }
        // the owner's ~/.gitconfig on the phone carries an identity; a rebase that replays a commit needs one
        File(home, ".gitconfig").writeText("[user]\n\tname = t\n\temail = t@t\n[init]\n\tdefaultBranch = main\n")
    }

    private fun which(b: String) = ProcessBuilder("sh", "-c", "command -v $b").start().waitFor() == 0

    private data class R(val exit: Int, val out: String)

    /** CLOUD_EXEC's argv: /system/bin/sh -c <guestWrap(script)>, here with the JVM host's /bin/sh. */
    private fun exec(wrapped: String, stdin: String = ""): R {
        val p = ProcessBuilder("/bin/sh", "-c", wrapped.replace("/system/bin/sh", "/bin/sh")).redirectErrorStream(true)
            .also { it.environment()["HOME"] = home.absolutePath }.start()
        p.outputStream.use { it.write(stdin.toByteArray()) }
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor(120, TimeUnit.SECONDS))
        return R(p.exitValue(), out)
    }

    private fun run(script: String, stdin: String = "") = exec(StoreExec.guestWrap(script), stdin)

    /** StoreExec.job: the detached start on the HOST (its own session through the termux shell, #1358), then poll the exit file and read the log. */
    private fun job(script: String, stdin: String = "", wrap: (String) -> String = { it }): R {
        val dir = File(home, "store/.jobs").absolutePath; val id = "j" + System.nanoTime()
        val started = exec(wrap(StoreExec.jobStart(script, dir, id)), stdin)
        if (started.exit != 0) return started
        val rc = File(dir, "$id.rc")
        repeat(240) { if (rc.isFile && rc.readText().isNotBlank()) return R(rc.readText().trim().toInt(), File(dir, "$id.log").readText()); Thread.sleep(500) }
        error("job $id never ended")
    }

    private fun q(s: String) = StoreExec.q(s)
    private fun git(dir: File, vararg a: String) = ProcessBuilder(listOf("git", "-C", dir.absolutePath) + a).redirectErrorStream(true).also { it.environment()["HOME"] = home.absolutePath }.start().let { it.inputStream.bufferedReader().readText().also { _ -> it.waitFor() } }

    private fun localRemote(name: String): File {
        val seed = File(home, "seed-$name"); seed.mkdirs()
        git(seed, "init", "-q", "-b", "main"); File(seed, "a.txt").writeText("one\n")
        git(seed, "add", "a.txt"); git(seed, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "one")
        val bare = File(home, "remotes/diegonmarcos/$name.git")
        ProcessBuilder("git", "clone", "-q", "--bare", seed.absolutePath, bare.absolutePath).start().waitFor()
        return bare
    }

    @Test fun theOldDoubleQuotedWrapIsRefusedByFish_theBugThisFixes() {
        val old = { s: String -> "exec /system/bin/sh \"\$HOME/.termux/shell\" -c " + q("sh -c " + q(s)) }
        val g = File(home, "store/git/bare/o/x.git").absolutePath
        val r = job("set -e; mkdir -p \"\$(dirname ${q(g)})\"; " + StoreGit.authed(BareClones.cloneArgs("file:///nonexistent", g, 1) { q(it) }), "", old)
        println("old wrap under fish -> exit ${r.exit}: ${r.out.trim()}")
        assertTrue("the old wrap must fail under fish (it did on the phone): ${r.out}", r.exit != 0 && "fish" in r.out)
    }

    @Test fun bareCloneAllSucceedsThroughFish() {
        // local origins only: no network in the test, and the single-branch rule clones main (the public octocat repos default to master)
        val targets = listOf("Hello-World", "Spoon-Knife", "cloudy-___").map { n -> "file://" + localRemote(n).absolutePath to n }
        val failures = targets.mapNotNull { (url, name) ->
            val g = File(home, "store/git/bare/o/$name.git").absolutePath
            val r = job("set -e; mkdir -p \"\$(dirname ${q(g)})\"; " + StoreGit.authed(BareClones.cloneArgs(url, g, 1) { q(it) }), stdin = "")
            println("bare $name -> exit ${r.exit}: ${r.out.trim()}")
            if (r.exit == 0 && File(g, "HEAD").isFile) null else BulkDepth.block("failed", name, BulkDepth.withTail("exit ${r.exit}", r.out))
        }
        assertEquals("every bare clone must succeed:\n" + failures.joinToString("\n"), emptyList<String>(), failures)
        assertTrue("the underscore name reached git verbatim", File(home, "store/git/bare/o/cloudy-___.git/HEAD").isFile)
    }

    @Test fun cloneCommitPushPullThroughFish() {
        val remote = localRemote("cloudy-sync")
        val url = "file://" + remote.absolutePath
        val a = File(home, "store/git/a"); val b = File(home, "store/git/b")
        assertEquals(0, job(StoreGit.cloneScript(url, a.absolutePath, null), stdin = "tok").exit)
        assertEquals(0, job(StoreGit.cloneScript(url, b.absolutePath, null), stdin = "").exit)
        File(a, "from-a.txt").writeText("a\n")
        val pushA = job(StoreGit.syncScript(a.absolutePath, "from a", "t", "t@t", rebase = true), stdin = "tok")
        assertEquals("sync (commit + pull + push) from a: ${pushA.out}", 0, pushA.exit)
        File(b, "from-b.txt").writeText("b\n")
        val pushB = job(StoreGit.syncScript(b.absolutePath, "from b", "t", "t@t", rebase = true), stdin = "")
        assertEquals("sync from b (pulls a's commit, pushes its own): ${pushB.out}", 0, pushB.exit)
        val pullA = job(StoreGit.syncScript(a.absolutePath, "nothing", "t", "t@t", rebase = true), stdin = "tok")
        assertEquals(0, pullA.exit)
        assertTrue("a pulled b's commit", File(a, "from-b.txt").isFile)
        assertTrue("the remote has both commits", git(remote, "log", "--format=%s", "main").let { "from a" in it && "from b" in it })
        // a short run() (the light probe path) with a quoted path still answers through fish
        assertEquals(0, run("git -C ${q(a.absolutePath)} rev-parse --git-dir >/dev/null 2>&1").exit)
    }

    @Test fun reportIsOneLinePerRepoWithTheRealReason() {
        // #1358 one line per repository: status, the literal name, the summary and git's own fatal line (not its progress).
        val msg = BulkDepth.withTail("bare clone in cld.termux failed (exit 128)", "Cloning into bare repository 'x'...\nfatal: repository not found\n")
        val run = BulkDepth.Run("clone_all_bare", "clone_bare", 1, 1, failed = mapOf("cloud-me-pvt-___" to msg), ended = true)
        assertEquals(listOf("failed  cloud-me-pvt-___  bare clone in cld.termux failed (exit 128): fatal: repository not found"), run.lines())
    }
}

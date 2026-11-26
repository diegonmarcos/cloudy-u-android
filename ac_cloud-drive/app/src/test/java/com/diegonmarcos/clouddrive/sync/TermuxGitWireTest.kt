package com.diegonmarcos.clouddrive.sync

import com.diegonmarcos.cloudlib.gitsync.GitChange
import com.diegonmarcos.cloudlib.gitsync.GitEngine
import com.diegonmarcos.cloudlib.gitsync.GitStatusSnapshot
import com.diegonmarcos.cloudlib.gitsync.GitTransport
import com.diegonmarcos.cloudlib.gitsync.GitWire
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #1070: the git screens' wire answered by the git CLI in cld.termux: the scripts it runs and the models it parses. */
class TermuxGitWireTest {
    private val dir = "/root/cloud-shared-store/git/r"
    private fun plan(verb: String, args: JSONObject = JSONObject(), auth: JSONObject? = null) = TermuxGitWire.plan(verb, dir, args, auth)

    @Test fun `every verb of the wire has a script`() {
        val args = JSONObject().put("paths", JSONArray(listOf("a.txt"))).put("path", "a.txt").put("sha", "abc123").put("name", "n").put("url", "u").put("message", "m").put("email", "e").put("side", "OURS")
        for (v in GitWire.VERBS) assertNotNull("verb $v", plan(v, args).first)
    }

    @Test fun `a path that leaves the repository is refused for every path verb`() {
        for (bad in listOf("../x", "/etc/passwd", "a/../../b")) {
            assertNull(plan("stage", JSONObject().put("paths", JSONArray(listOf(bad)))).first)
            assertNull(plan("discard", JSONObject().put("path", bad)).first)
            assertNull(plan("diff", JSONObject().put("path", bad)).first)
            assertNull(plan("resolve", JSONObject().put("path", bad).put("side", "OURS")).first)
        }
        assertNull(plan("commit_diff", JSONObject().put("sha", "x; rm -rf /")).first)
        assertNull(plan("push", JSONObject().put("remote", "--upload-pack=x")).first)
    }

    @Test fun `values are single-quoted and the credential travels on stdin, never in the script`() {
        val p = plan("commit", JSONObject().put("message", "it's \$(x)").put("name", "N").put("email", "e@x")).first!!
        assertTrue(p.script.contains("'it'\\''s \$(x)'"))
        val auth = JSONObject().put("kind", "https").put("username", "u").put("secret", "SECRET-TOKEN")
        val push = plan("push", JSONObject().put("remote", "origin"), auth).first!!
        assertFalse(push.script.contains("SECRET-TOKEN")); assertFalse(push.script.contains("U0VDUkVU"))
        assertTrue(push.network); assertTrue(push.stdin.startsWith("Authorization: Basic "))
        assertEquals("", plan("push", JSONObject()).first!!.stdin)
        val sess = plan("fetch", JSONObject(), JSONObject().put("kind", "session").put("header", "Cookie").put("value", "authelia_session=z")).first!!
        assertEquals("Cookie: authelia_session=z", sess.stdin)
    }

    @Test fun `pull honours rebase, sync commits then pulls then pushes, local verbs are not network jobs`() {
        assertTrue(plan("pull", JSONObject().put("rebase", false)).first!!.script.contains("pull --no-rebase"))
        val s = plan("sync", JSONObject().put("message", "m").put("name", "n").put("email", "e")).first!!.script
        assertTrue(s.indexOf("commit") < s.indexOf("pull") && s.indexOf("pull") < s.indexOf("push"))
        assertFalse(plan("status").first!!.network); assertFalse(plan("commit", JSONObject().put("message", "m")).first!!.network)
        assertTrue(plan("clone", JSONObject().put("url", "https://h/r.git").put("depth", 1)).first!!.script.contains("clone --depth 1 "))
    }

    @Test fun `status porcelain v2 becomes the engine's snapshot`() {
        val z = "\u0000"
        val out = "# branch.oid abc$z# branch.head main$z# branch.upstream origin/main$z# branch.ab +2 -1$z" +
            "1 MM N... 100644 100644 100644 h h src/a.kt$z" + "1 .D N... 100644 100644 000000 h h gone.txt$z" +
            "2 R. N... 100644 100644 100644 h h R100 new.txt$z" + "old.txt$z" +
            "u UU N... 100644 100644 100644 100644 h h h both.txt$z" + "? untracked dir/f$z" +
            "\n@@STATE MERGING\n"
        val s = TermuxGitWire.parseStatus(out)
        assertEquals("main", s.branch); assertEquals("origin/main", s.upstream); assertEquals(2, s.ahead); assertEquals(1, s.behind)
        assertEquals("MERGING", s.repositoryState)
        val by = s.files.associateBy { it.path }
        assertEquals(GitChange.MODIFIED, by.getValue("src/a.kt").staged); assertEquals(GitChange.MODIFIED, by.getValue("src/a.kt").unstaged)
        assertEquals(GitChange.DELETED, by.getValue("gone.txt").unstaged)
        assertEquals(GitChange.MODIFIED, by.getValue("new.txt").staged); assertFalse(by.containsKey("old.txt"))
        assertTrue(by.getValue("both.txt").conflicting); assertEquals("BOTH_MODIFIED", by.getValue("both.txt").stageState)
        assertEquals(GitChange.UNTRACKED, by.getValue("untracked dir/f").unstaged)
        assertEquals(1, s.conflicts.size)
        assertNull(TermuxGitWire.parseStatus("# branch.head (detached)\u0000\n@@STATE SAFE\n").branch)
        assertTrue(TermuxGitWire.parseStatus("\n@@STATE SAFE\n").isClean)
    }

    @Test fun `log, branches and remotes parse into the engine's models`() {
        val rec = "1111\u001fAda\u001fada@x\u001f1700000000\u001f2222 3333\u001fsubject\u001fsubject\n\nbody\n\u001e\n"
        val c = TermuxGitWire.parseLog(rec + rec.replace("1111", "4444")).first()
        assertEquals("1111", c.sha); assertEquals("subject", c.summary); assertEquals("subject\n\nbody", c.message)
        assertEquals(1700000000L, c.time); assertEquals(listOf("2222", "3333"), c.parents)
        assertEquals(2, TermuxGitWire.parseLog(rec + rec).size)
        val b = TermuxGitWire.parseBranches("refs/heads/main\u001fmain\u001faaa\u001f*\nrefs/remotes/origin/main\u001forigin/main\u001fbbb\u001f \nrefs/remotes/origin/HEAD\u001forigin\u001fbbb\u001f ")
        assertEquals(2, b.size); assertTrue(b[0].isCurrent); assertTrue(b[1].isRemote); assertFalse(b[0].isRemote)
        val r = TermuxGitWire.parseRemotes("origin\thttps://h/r.git (fetch)\norigin\tgit@h:r.git (push)\n")
        assertEquals("https://h/r.git", r.single().fetchUrl); assertEquals("git@h:r.git", r.single().pushUrl)
    }

    @Test fun `a GitEngine over this wire reads status the same as over the engine APK`() {
        val snapshot = GitWire.json.encodeToString(GitStatusSnapshot.serializer(), TermuxGitWire.parseStatus("# branch.head main\u0000? n\u0000\n@@STATE SAFE\n"))
        val t = object : GitTransport {
            override fun unavailable(): String? = null
            override fun session() = object : GitTransport.Session {
                override fun git(request: JSONObject) = JSONObject().put("ok", true).put("result", snapshot)
                override fun chunk(request: JSONObject) = JSONObject().put("ok", false)
                override fun close() {}
            }
        }
        val st = GitEngine(t, File(dir)).status()
        assertEquals("main", st.branch); assertEquals(GitChange.UNTRACKED, st.files.single().unstaged)
    }

    // #1934 the control sheet's plan steps run as git itself: the argv the preview printed, every word quoted, only allow-listed verbs.
    @Test fun `git_argv runs the previewed argv as git, quoted`() {
        val a = JSONObject().put("argv", JSONArray(listOf("pull", "--no-rebase", "-X", "theirs", "--autostash"))).put("name", "N").put("email", "e@x")
        val p = plan("git_argv", a, JSONObject().put("kind", "https").put("username", "u").put("secret", "S3CRET")).first!!
        assertTrue(p.script, p.script.contains("'pull' '--no-rebase' '-X' 'theirs' '--autostash'"))
        assertTrue(p.network); assertFalse(p.script.contains("S3CRET"))
        val c = plan("git_argv", JSONObject().put("argv", JSONArray(listOf("commit", "-m", "it's \$(x)")))).first!!
        assertTrue(c.script, c.script.contains("'commit' '-m' 'it'\\''s \$(x)'"))
    }

    @Test fun `git_argv refuses a verb outside the control sheet and any reset but hard at upstream`() {
        for (bad in listOf("rm", "config", "clean", "checkout", "remote", "filter-branch")) assertNull(bad, plan("git_argv", JSONObject().put("argv", JSONArray(listOf(bad, "x")))).first)
        assertNull(plan("git_argv", JSONObject().put("argv", JSONArray(listOf("reset", "--hard", "HEAD~5")))).first)
        assertNotNull(plan("git_argv", JSONObject().put("argv", JSONArray(listOf("reset", "--hard", "@{u}")))).first)
        assertNull(plan("git_argv", JSONObject()).first)
    }
}

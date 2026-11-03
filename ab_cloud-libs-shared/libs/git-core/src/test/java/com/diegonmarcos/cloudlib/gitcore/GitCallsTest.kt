package com.diegonmarcos.cloudlib.gitcore

import com.diegonmarcos.cloudlib.gitsync.GitAuth
import com.diegonmarcos.cloudlib.gitsync.GitBranchInfo
import com.diegonmarcos.cloudlib.gitsync.GitCommitInfo
import com.diegonmarcos.cloudlib.gitsync.GitOpResult
import com.diegonmarcos.cloudlib.gitsync.GitStatusSnapshot
import com.diegonmarcos.cloudlib.gitsync.GitWire
import kotlinx.serialization.builtins.ListSerializer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The engine's JSON surface on REAL repositories (temp directories, file:// remotes): a request in
 * GitWire's names answers the models the client decodes, the verbs that need credentials get them
 * from the request, an ssh key becomes files for one call and nothing after, a long diff comes back
 * in chunks, and the refusals (an unknown verb, shared storage without the all-files grant) name
 * themselves. The verbs' own behaviour is JGitEngineTest's.
 */
class GitCallsTest {
    private lateinit var root: File
    private lateinit var scratch: File
    private lateinit var work: File
    private lateinit var bare: File

    @Before fun setUp() {
        root = Files.createTempDirectory("git-calls-test").toFile()
        scratch = File(root, "scratch")
        work = File(root, "work")
        bare = File(root, "remote.git")
        org.eclipse.jgit.api.Git.init().setBare(true).setDirectory(bare).call().close()
    }

    @After fun tearDown() { root.deleteRecursively() }

    private fun calls(access: Boolean = true) = GitCalls(scratch, "7.8.0", { access })
    private fun req(dir: File, verb: String, args: JSONObject = JSONObject(), auth: JSONObject? = null) = GitWire.request(dir, verb, args, auth)
    private fun ok(answer: String): JSONObject = JSONObject(answer).also { assertTrue(it.toString(), it.getBoolean("ok")) }

    @Test fun anInitStageCommitStatusRoundTripAnswersTheModels() {
        val c = calls()
        ok(c.git(req(work, "init")))
        File(work, "a.md").writeText("hello\n")
        ok(c.git(req(work, "stage", JSONObject().put("paths", JSONArray(listOf("a.md"))))))
        val commit = GitWire.json.decodeFromString(GitCommitInfo.serializer(),
            ok(c.git(req(work, "commit", JSONObject().put("message", "first").put("name", "T").put("email", "t@x").put("amend", false)))).getString("result"))
        assertEquals("first", commit.summary)
        val status = GitWire.json.decodeFromString(GitStatusSnapshot.serializer(), ok(c.git(req(work, "status"))).getString("result"))
        assertTrue(status.isClean)
        val log = GitWire.json.decodeFromString(ListSerializer(GitCommitInfo.serializer()), ok(c.git(req(work, "log", JSONObject().put("max", 10)))).getString("result"))
        assertEquals(listOf("first"), log.map { it.summary })
        val branches = GitWire.json.decodeFromString(ListSerializer(GitBranchInfo.serializer()), ok(c.git(req(work, "branches"))).getString("result"))
        assertTrue(branches.any { it.isCurrent })
    }

    @Test fun aPushOverFileCarriesTheRemoteAndTheResultModel() {
        val c = calls()
        ok(c.git(req(work, "init")))
        File(work, "a.md").writeText("x")
        ok(c.git(req(work, "sync", JSONObject().put("message", "m").put("name", "T").put("email", "t@x").put("remote", "origin").put("rebase", true))))
        ok(c.git(req(work, "add_remote", JSONObject().put("name", "origin").put("url", "file://" + bare.absolutePath))))
        val pushed = GitWire.json.decodeFromString(GitOpResult.serializer(),
            ok(c.git(req(work, "push", JSONObject().put("remote", "origin")))).getString("result"))
        assertTrue(pushed.summary, pushed.ok)
    }

    @Test fun aSessionAndAHttpsAuthReachTheEngineAsTheModelsTheJgitEngineTakes() {
        val s = AuthFiles(scratch, GitWire.authToJson(GitAuth.Session("Cookie", "authelia_session=abc")))
        assertEquals(GitAuth.Session("Cookie", "authelia_session=abc"), s.auth)
        val h = AuthFiles(scratch, GitWire.authToJson(GitAuth.Https("me", "tok")))
        assertEquals(GitAuth.Https("me", "tok"), h.auth)
        assertEquals(GitAuth.None, AuthFiles(scratch, null).auth)
        assertEquals("a token or a session writes no file", 0, scratch.listFiles().orEmpty().size)
    }

    @Test fun anSshKeyBecomesPrivateFilesForOneCallAndAnExtendedKnownHostsComesBack() {
        val keyFile = File(root, "key"); keyFile.writeText("PRIVATE KEY TEXT\n")
        val hostsFile = File(root, "known_hosts"); hostsFile.writeText("old.example ssh-ed25519 AAAA\n")
        val sent = GitWire.authToJson(GitAuth.Ssh(keyFile.absolutePath, "pass", hostsFile.absolutePath))
        assertEquals("PRIVATE KEY TEXT\n", sent.getString("key"))
        assertEquals("old.example ssh-ed25519 AAAA\n", sent.getString("known_hosts"))

        val files = AuthFiles(scratch, sent)
        val ssh = files.auth as GitAuth.Ssh
        assertEquals("PRIVATE KEY TEXT\n", File(ssh.privateKeyPath).readText())
        assertEquals("pass", ssh.passphrase)
        assertNull("nothing changed yet, nothing to hand back", files.knownHostsIfChanged())
        File(ssh.knownHostsPath).appendText("new.example ssh-ed25519 BBBB\n")
        assertEquals("old.example ssh-ed25519 AAAA\nnew.example ssh-ed25519 BBBB\n", files.knownHostsIfChanged())
        files.delete()
        assertEquals("the key and known_hosts are gone after the call", 0, scratch.listFiles().orEmpty().size)
    }

    @Test fun aMissingSshKeyIsASentenceOnTheClientSide() {
        val e = runCatching { GitWire.authToJson(GitAuth.Ssh(File(root, "nokey").absolutePath, null, File(root, "kh").absolutePath)) }.exceptionOrNull()
        assertNotNull(e); assertTrue(e!!.message!!.contains("ssh key not found"))
    }

    @Test fun aLongDiffComesBackInChunksThatJoinToTheWhole() {
        val c = calls()
        ok(c.git(req(work, "init")))
        val big = "line of text that is long enough\n".repeat(20_000)   // ~660 kB
        File(work, "big.txt").writeText(big)
        val first = ok(c.git(req(work, "diff", JSONObject().put("path", "big.txt").put("staged", false))))
        assertTrue(first.getBoolean("more")); assertEquals(GitCalls.CHUNK, first.getString("result").length)
        val sb = StringBuilder(first.getString("result")); var more = true; val id = first.getString("id")
        while (more) {
            val next = ok(c.chunk(JSONObject().put("id", id).put("offset", sb.length)))
            sb.append(next.getString("result")); more = next.getBoolean("more")
        }
        assertTrue(sb.toString().contains("+++ b/big.txt"))
        assertTrue(sb.length > big.length)
        assertFalse(JSONObject(c.chunk(JSONObject().put("id", id).put("offset", 0))).getBoolean("ok"))
    }

    @Test fun anUnknownVerbIsRefusedAndNamesTheOnesItRuns() {
        val a = JSONObject(calls().git(req(work, "filter-branch")))
        assertFalse(a.getBoolean("ok")); assertTrue(a.getString("error").contains("status"))
    }

    @Test fun aSharedStorageRepositoryWithoutAllFilesAccessSaysWhatToGrant() {
        val a = JSONObject(calls(access = false).git(req(File("/storage/emulated/0/CloudDrive/git/x"), "status")))
        assertFalse(a.getBoolean("ok")); assertTrue(a.getString("error").contains("All files access")); assertEquals("STORAGE", a.getString("kind"))
        assertTrue("a path outside the shared store needs no grant", JSONObject(calls(access = false).git(req(work, "init"))).getBoolean("ok"))
    }

    @Test fun aFailingVerbAnswersItsMessageNotACrash() {
        val a = JSONObject(calls().git(req(File(root, "not-a-repo"), "status")))
        assertFalse(a.getBoolean("ok")); assertTrue(a.getString("error").isNotBlank())
    }

    @Test fun infoNamesTheVersionAndTheGrant() {
        val i = JSONObject(calls(access = false).info())
        assertEquals("7.8.0", i.getString("version")); assertFalse(i.getBoolean("files_access"))
    }
}

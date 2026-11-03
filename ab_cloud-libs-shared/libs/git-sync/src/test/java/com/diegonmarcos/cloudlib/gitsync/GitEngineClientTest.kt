package com.diegonmarcos.cloudlib.gitsync

import kotlinx.serialization.builtins.ListSerializer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The client half of the git sync engine (#871) on the JVM, against a FAKE engine: what the client sends
 * (the verb, the repository, the args, the credentials as text), what it does with the answers (the
 * models decoded, a long result reassembled from chunks, a known_hosts the engine extended written back,
 * a refusal raised with its sentence) and what it does with no engine at all. The engine's own half is
 * libs:git-core's GitCallsTest and JGitEngineTest.
 */
class GitEngineClientTest {
    private lateinit var dir: File
    private lateinit var work: File

    @Before fun setUp() {
        dir = Files.createTempDirectory("git-client-test").toFile()
        work = File(dir, "repos/notes")
    }

    private class Fake(val unavailable: String? = null, val answer: (String, JSONObject) -> JSONObject) : GitTransport {
        val calls = CopyOnWriteArrayList<Pair<String, JSONObject>>()
        override fun unavailable() = unavailable
        override fun session(): GitTransport.Session {
            if (unavailable != null) throw IOException(unavailable)
            return object : GitTransport.Session {
                private fun ask(method: String, request: JSONObject): JSONObject { calls += method to request; return answer(method, request) }
                override fun git(request: JSONObject) = ask("git", request)
                override fun chunk(request: JSONObject) = ask("chunk", request)
                override fun close() {}
            }
        }
    }

    private fun ok(result: String = "") = JSONObject().put("ok", true).put("result", result)
    private fun engine(t: GitTransport) = GitEngine(t, work)

    @Test fun aStatusIsOneGitCallForTheRepositoryAndDecodesTheSnapshot() {
        val snap = GitStatusSnapshot("main", "origin/main", 1, 2, listOf(GitFileStatus("a.md", unstaged = GitChange.MODIFIED)), "SAFE")
        val f = Fake { _, _ -> ok(GitWire.json.encodeToString(GitStatusSnapshot.serializer(), snap)) }
        val got = engine(f).status()
        assertEquals(snap, got)
        val (method, req) = f.calls.single()
        assertEquals("git", method); assertEquals("status", req.getString("verb")); assertEquals(work.absolutePath, req.getString("dir"))
        assertEquals(GitWire.KIND_NONE, req.getJSONObject("auth").getString("kind"))
    }

    @Test fun everyVerbTheScreensCallIsOneTheEngineRuns() {
        val seen = CopyOnWriteArrayList<String>()
        val f = Fake { _, req ->
            seen += req.getString("verb")
            when (req.getString("verb")) {
                "status" -> ok(GitWire.json.encodeToString(GitStatusSnapshot.serializer(), GitStatusSnapshot(null, null, 0, 0, emptyList(), "SAFE")))
                "commit" -> ok(GitWire.json.encodeToString(GitCommitInfo.serializer(), GitCommitInfo("a".repeat(40), "s", "s", "n", "e", 1, emptyList())))
                "push", "fetch", "pull", "force_push", "force_pull", "sync", "stash_push", "stash_pop", "set_upstream" -> ok(GitWire.json.encodeToString(GitOpResult.serializer(), GitOpResult(true, "ok")))
                "branches" -> ok("[]"); "log" -> ok("[]"); "remotes" -> ok("[]"); "stash_list" -> ok("[]")
                else -> ok()
            }
        }
        val e = engine(f)
        e.status(); e.stage(listOf("a")); e.stageAll(); e.unstage(listOf("a")); e.unstageAll(); e.discard("a")
        e.commit("m", "n", "e"); e.push(); e.fetch(); e.pull(); e.forcePush(); e.forcePull(); e.sync("m", "n", "e")
        e.branches(); e.log(); e.diff(); e.commitDiff("abc"); e.remotes(); e.addRemote("o", "u"); e.removeRemote("o"); e.setRemoteUrl("o", "u")
        e.resolve("a", ConflictSide.THEIRS); e.markResolved("a"); e.abortMerge()
        e.stashPush("m"); e.stashPop(); e.stashList(); e.setUpstream(); e.upstream()
        GitEngine.init(f, work); GitEngine.clone(f, "https://x/y.git", work, depth = 1)
        val unknown = seen.filter { it !in GitWire.VERBS }
        assertTrue("verbs the engine does not run: $unknown", unknown.isEmpty())
        assertEquals("every verb in the wire is one the client sends", GitWire.VERBS, seen.toSet())
    }

    @Test fun argumentsTravelAsNamedFields() {
        val f = Fake { _, _ -> ok(GitWire.json.encodeToString(GitOpResult.serializer(), GitOpResult(true, "ok"))) }
        engine(f).pull("upstream", rebase = false)
        val a = f.calls.single().second
        assertEquals("pull", a.getString("verb"))
        assertEquals("upstream", a.getJSONObject("args").getString("remote")); assertFalse(a.getJSONObject("args").getBoolean("rebase"))
    }

    @Test fun anHttpsTokenAndASessionTravelWithTheCallAndNothingElseDoes() {
        val f = Fake { _, _ -> ok(GitWire.json.encodeToString(GitOpResult.serializer(), GitOpResult(true, "ok"))) }
        engine(f).push("origin", GitAuth.Https("me", "tok"))
        engine(f).fetch("origin", GitAuth.Session("Cookie", "authelia_session=abc"))
        val (h, s) = f.calls.map { it.second.getJSONObject("auth") }
        assertEquals(GitWire.KIND_HTTPS, h.getString("kind")); assertEquals("tok", h.getString("secret")); assertEquals("me", h.getString("username"))
        assertEquals(GitWire.KIND_SESSION, s.getString("kind")); assertEquals("Cookie", s.getString("header")); assertEquals("authelia_session=abc", s.getString("value"))
    }

    @Test fun anSshKeyTravelsAsTextAndAnExtendedKnownHostsIsWrittenBack() {
        val key = File(dir, "id"); key.writeText("KEY TEXT\n")
        val hosts = File(dir, "private/known_hosts")
        val f = Fake { _, _ -> ok(GitWire.json.encodeToString(GitOpResult.serializer(), GitOpResult(true, "ok"))).put("known_hosts", "h.example ssh-ed25519 AAAA\n") }
        engine(f).fetch("origin", GitAuth.Ssh(key.absolutePath, "pp", hosts.absolutePath))
        val auth = f.calls.single().second.getJSONObject("auth")
        assertEquals(GitWire.KIND_SSH, auth.getString("kind")); assertEquals("KEY TEXT\n", auth.getString("key")); assertEquals("pp", auth.getString("passphrase")); assertEquals("", auth.getString("known_hosts"))
        assertEquals("h.example ssh-ed25519 AAAA\n", hosts.readText())
    }

    @Test fun aLongResultIsReassembledFromItsChunks() {
        val whole = "+" + "x".repeat(2000) + "\n"
        val f = Fake { m, req -> when (m) {
            "git" -> ok(whole.substring(0, 700)).put("more", true).put("id", "d1")
            else -> { assertEquals("d1", req.getString("id")); assertEquals(700, req.getInt("offset")); ok(whole.substring(700)).put("more", false) }
        } }
        assertEquals(whole, engine(f).diff("big.txt"))
        assertEquals(listOf("git", "chunk"), f.calls.map { it.first })
    }

    @Test fun anEngineRefusalIsAnIOExceptionWithItsSentence() {
        val f = Fake { _, _ -> JSONObject().put("ok", false).put("error", "the git sync engine has no All files access") }
        val e = runCatching { engine(f).status() }.exceptionOrNull()
        assertTrue(e is IOException); assertEquals("the git sync engine has no All files access", e!!.message)
    }

    @Test fun noEngineInstalledIsASentenceNotACrash() {
        val none = Fake(unavailable = "the git sync engine com.x is not installed — install it from Store") { _, _ -> error("never called") }
        assertEquals("the git sync engine com.x is not installed — install it from Store", none.unavailable())
        val e = runCatching { engine(none).remotes() }.exceptionOrNull()
        assertTrue(e is IOException); assertTrue(e!!.message!!.contains("not installed"))
    }

    @Test fun anEmptyCommitMessageNeverLeavesTheApp() {
        val f = Fake { _, _ -> error("never called") }
        assertTrue(runCatching { engine(f).commit("  ", "n", "e") }.exceptionOrNull() is IllegalArgumentException)
        assertNull(f.calls.firstOrNull())
    }

    @Test fun isRepositoryIsAPlainFileCheck() {
        assertFalse(GitEngine.isRepository(work))
        File(work, ".git").mkdirs()
        assertTrue(GitEngine.isRepository(work))
    }
}

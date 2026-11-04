package com.diegonmarcos.cloudlib.gitsync

import java.io.File
import java.io.IOException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import com.diegonmarcos.cloudlib.gitcore.GitErrorKind
import com.diegonmarcos.cloudlib.gitcore.GitException
import org.json.JSONArray
import org.json.JSONObject

/**
 * The git manager's engine, as the screens and the host see it: every verb the manager screen offers, on
 * ONE repository. Blocking — callers run it on an IO dispatcher.
 *
 * #1408: THIS CLASS RUNS NO GIT ITSELF. JGit lives in libs:git-core, answered in-process, and every verb is one
 * call to it over [GitTransport] (the binder in the app, a fake in the JVM suite). What the callers were
 * written against is unchanged — the same verbs, the same models, the same [GitOpResult] for a transport
 * outcome — with two differences they can see: it is built with a Context (GitEngines.kt: the Android
 * side of the constructor, so THIS file imports nothing from android.* and the suite runs it on the JVM),
 * and a verb whose engine is absent, too old or refused (no All-files access on the shared store) throws an
 * [IOException] with the sentence that says what to do.
 *
 * Credentials are the caller's: [GitAuth] travels in each call, the engine keeps none. An ssh key and its
 * known_hosts are files in the caller's private storage, so their TEXT travels, and a known_hosts the
 * engine extended (a first contact) is written back here.
 *
 * Each verb opens the repository, runs and closes it in the engine; nothing is held between calls, so
 * [close] has nothing to release and exists to keep `use { }` call sites as they were.
 */
class GitEngine(
    private val transport: GitTransport,
    val workTree: File,
    private val readText: (String) -> String? = { p -> File(p).takeIf { it.isFile }?.readText() },
) : AutoCloseable {

    override fun close() {}

    // ── status ───────────────────────────────────────────────────────────

    fun status(): GitStatusSnapshot = decode(GitStatusSnapshot.serializer(), call("status"))

    fun stage(paths: Collection<String>) { call("stage", paths("paths", paths)) }
    fun stageAll() { call("stage_all") }
    fun unstage(paths: Collection<String>) { call("unstage", paths("paths", paths)) }
    fun unstageAll() { call("unstage_all") }
    fun discard(path: String) { call("discard", JSONObject().put("path", path)) }

    // ── commit ───────────────────────────────────────────────────────────

    fun commit(message: String, authorName: String, authorEmail: String, amend: Boolean = false): GitCommitInfo {
        require(message.isNotBlank()) { "commit message is empty" }
        return decode(GitCommitInfo.serializer(), call("commit",
            JSONObject().put("message", message).put("name", authorName).put("email", authorEmail).put("amend", amend)))
    }

    // ── push / pull / fetch ──────────────────────────────────────────────

    fun push(remote: String = "origin", auth: GitAuth = GitAuth.None): GitOpResult = op("push", remote, auth)
    fun fetch(remote: String = "origin", auth: GitAuth = GitAuth.None): GitOpResult = op("fetch", remote, auth)
    fun forcePush(remote: String = "origin", auth: GitAuth = GitAuth.None): GitOpResult = op("force_push", remote, auth)
    fun forcePull(remote: String = "origin", auth: GitAuth = GitAuth.None): GitOpResult = op("force_pull", remote, auth)

    fun pull(remote: String = "origin", rebase: Boolean = true, auth: GitAuth = GitAuth.None): GitOpResult =
        op("pull", remote, auth, JSONObject().put("rebase", rebase))

    /** GitSync's one-tap sync: stage everything, commit if anything changed, pull, push. */
    fun sync(message: String, authorName: String, authorEmail: String, remote: String = "origin",
             rebase: Boolean = true, auth: GitAuth = GitAuth.None): GitOpResult =
        op("sync", remote, auth, JSONObject().put("message", message).put("name", authorName).put("email", authorEmail).put("rebase", rebase))

    // ── branches (read-only) ─────────────────────────────────────────────

    fun branches(): List<GitBranchInfo> = decode(ListSerializer(GitBranchInfo.serializer()), call("branches"))

    // ── log / diff ───────────────────────────────────────────────────────

    fun log(max: Int = 100, path: String? = null): List<GitCommitInfo> =
        decode(ListSerializer(GitCommitInfo.serializer()), call("log", JSONObject().put("max", max).apply { if (path != null) put("path", path) }))

    /** Unified diff of the worktree (or the index, when [staged]) — one path or everything. */
    fun diff(path: String? = null, staged: Boolean = false): String =
        call("diff", JSONObject().put("staged", staged).apply { if (path != null) put("path", path) })

    /** Unified diff of one commit against its first parent (or the empty tree for a root commit). */
    fun commitDiff(sha: String): String = call("commit_diff", JSONObject().put("sha", sha))

    // ── remotes ──────────────────────────────────────────────────────────

    fun remotes(): List<GitRemoteInfo> = decode(ListSerializer(GitRemoteInfo.serializer()), call("remotes"))
    fun addRemote(name: String, url: String) { call("add_remote", JSONObject().put("name", name).put("url", url)) }
    fun removeRemote(name: String) { call("remove_remote", JSONObject().put("name", name)) }
    fun setRemoteUrl(name: String, url: String, push: Boolean = false) {
        call("set_remote_url", JSONObject().put("name", name).put("url", url).put("push", push))
    }

    // ── conflicts ────────────────────────────────────────────────────────

    /** Take one whole side of a conflicted path and stage it as resolved. */
    fun resolve(path: String, side: ConflictSide) { call("resolve", JSONObject().put("path", path).put("side", side.name)) }

    /** The user edited the markers away by hand: stage the path as resolved. */
    fun markResolved(path: String) { call("mark_resolved", JSONObject().put("path", path)) }

    /** Abort an in-progress merge: back to HEAD, worktree included. */
    fun abortMerge() { call("abort_merge") }

    // #1408 stash and upstream.
    fun stashPush(message: String = "", includeUntracked: Boolean = true): GitOpResult =
        decode(GitOpResult.serializer(), call("stash_push", JSONObject().put("message", message).put("untracked", includeUntracked)))
    fun stashPop(): GitOpResult = decode(GitOpResult.serializer(), call("stash_pop"))
    fun stashList(): List<String> = decode(ListSerializer(String.serializer()), call("stash_list"))
    fun setUpstream(remote: String = "origin", branch: String? = null): GitOpResult =
        decode(GitOpResult.serializer(), call("set_upstream", JSONObject().put("remote", remote).apply { if (branch != null) put("branch", branch) }))
    /** "remote/branch", or null when the current branch tracks nothing. */
    fun upstream(): String? = call("upstream").ifEmpty { null }

    // ── the one call ─────────────────────────────────────────────────────

    private fun op(verb: String, remote: String, auth: GitAuth, extra: JSONObject = JSONObject()): GitOpResult =
        decode(GitOpResult.serializer(), call(verb, extra.put("remote", remote), auth))

    private fun paths(key: String, paths: Collection<String>) = JSONObject().put(key, JSONArray(paths.toList()))

    private fun <T> decode(serializer: KSerializer<T>, text: String): T = GitWire.json.decodeFromString(serializer, text)

    /** One verb through the engine; its result text (the model as JSON, or a diff), reassembled from chunks. */
    private fun call(verb: String, args: JSONObject = JSONObject(), auth: GitAuth = GitAuth.None, dir: File = workTree): String =
        transport.session().use { s ->
            val a = s.git(GitWire.request(dir, verb, args, GitWire.authToJson(auth, readText)))
            if (!a.optBoolean("ok", false)) {
                val why = a.optString("error").ifBlank { "git refused $verb" }
                val kind = GitErrorKind.of(a.optString("kind")).takeIf { it != GitErrorKind.UNKNOWN } ?: GitErrorKind.classify(why)
                throw GitException(kind, why)
            }
            keepKnownHosts(auth, a)
            val out = StringBuilder(a.optString("result"))
            var more = a.optBoolean("more", false)
            val id = a.optString("id")
            while (more) {
                val c = s.chunk(JSONObject().put("id", id).put("offset", out.length))
                if (!c.optBoolean("ok", false)) throw IOException(c.optString("error").ifBlank { "git lost the rest of $verb" })
                out.append(c.optString("result")); more = c.optBoolean("more", false)
            }
            out.toString()
        }

    /** A first contact with an ssh host extended the known_hosts the engine was given: keep it where it came from. */
    private fun keepKnownHosts(auth: GitAuth, answer: JSONObject) {
        if (auth !is GitAuth.Ssh || !answer.has("known_hosts")) return
        val f = File(auth.knownHostsPath)
        f.parentFile?.mkdirs()
        f.writeText(answer.getString("known_hosts"))
    }

    companion object {
        /** Set by a host that routes some directories elsewhere ([GitTransports]): true/false when it knows, null when it cannot say (for example on the main thread). */
        @Volatile var repositoryProbe: ((File) -> Boolean?)? = null

        private const val REPO_TTL_MS = 30_000L
        private val repoCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Boolean, Long>>()

        /** Forget what was learned about [dir] (after a clone or init changed the answer). */
        fun forgetRepository(dir: File) { repoCache.remove(dir.absolutePath) }

        /**
         * Blocking: a directory the host routes (a repo in the shared store, asked of cld.termux) is answered by
         * the host's probe, cached per path for [REPO_TTL_MS]. Call it OFF the main thread, or use
         * [isRepositoryAsync]: from the main thread a routed directory has no answer unless one is cached.
         */
        fun isRepository(dir: File): Boolean {
            if (File(dir, ".git").exists()) return true
            val key = dir.absolutePath
            val now = System.currentTimeMillis()
            repoCache[key]?.let { (v, at) -> if (now - at < REPO_TTL_MS) return v }
            val asked = repositoryProbe?.invoke(dir) ?: return false
            repoCache[key] = asked to now
            return asked
        }

        /** [isRepository] on the IO dispatcher: what UI code calls. */
        suspend fun isRepositoryAsync(dir: File): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { isRepository(dir) }

        fun init(transport: GitTransport, dir: File): GitEngine {
            GitEngine(transport, dir).call("init")
            forgetRepository(dir)
            return GitEngine(transport, dir)
        }

        /**
         * @param depth above 0 clones SHALLOW to that many commits.
         *   #603 the host seeds its store with depth 1: a working tree is what the user wants and
         *   the full history is megabytes it never asked for. 0 keeps the complete clone.
         */
        fun clone(transport: GitTransport, url: String, dir: File, auth: GitAuth = GitAuth.None, depth: Int = 0, bare: Boolean = false): GitEngine {
            GitEngine(transport, dir).call("clone", JSONObject().put("url", url).put("depth", depth).put("bare", bare), auth)
            forgetRepository(dir)
            return GitEngine(transport, dir)
        }
    }
}

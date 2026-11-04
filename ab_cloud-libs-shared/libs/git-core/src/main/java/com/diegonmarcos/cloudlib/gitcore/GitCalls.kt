package com.diegonmarcos.cloudlib.gitcore

import com.diegonmarcos.cloudlib.gitsync.ConflictSide
import com.diegonmarcos.cloudlib.gitsync.GitAuth
import com.diegonmarcos.cloudlib.gitsync.GitBranchInfo
import com.diegonmarcos.cloudlib.gitsync.GitCommitInfo
import com.diegonmarcos.cloudlib.gitsync.GitOpResult
import com.diegonmarcos.cloudlib.gitsync.GitRemoteInfo
import com.diegonmarcos.cloudlib.gitsync.GitStatusSnapshot
import com.diegonmarcos.cloudlib.gitsync.GitWire
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The engine's answers, as JSON in and JSON out (the wire is IGitSyncEngine.call(method, request), the
 * names are GitWire's). No Android in here: the service hands in the scratch dir and the all-files check,
 * and the JVM suite runs it on real repositories in a temp directory.
 *
 * Every answer is {"ok":true,...} or {"ok":false,"error":...}; nothing throws across the binder.
 *   info    {}                                    -> {version, files_access}
 *   git     {dir, verb, args, auth}               -> {result, more?, id?, known_hosts?}
 *   chunk   {id, offset}                          -> {result, more}      the rest of an over-long result
 *
 * THE CREDENTIALS ARE THE CALLER'S. A token lives in this call's stack; an ssh key and known_hosts are
 * written to a private scratch directory for this one call and deleted after, and a known_hosts that
 * a first contact extended is handed back so the caller stores it. Nothing is kept or logged.
 */
class GitCalls(
    private val scratch: File,
    private val engineVersion: String,
    private val filesAccess: () -> Boolean,
) {
    /** Over-long results (a big diff) wait here for `chunk`: one binder transaction carries about 1 MB. */
    private val pending = ConcurrentHashMap<String, String>()

    fun info(): String = JSONObject().put("ok", true).put("version", engineVersion).put("files_access", filesAccess()).toString()

    fun git(req: JSONObject): String {
        val verb = req.optString("verb")
        if (verb !in GitWire.VERBS && verb !in GitWire.COMPOUND) return fail("git verb '$verb' is not one this engine runs (${GitWire.VERBS.joinToString(", ")})")
        val dir = File(req.optString("dir").ifBlank { return fail("git: no dir" ) })
        if (touchesSharedStorage(dir) && !filesAccess())
            return fail("Cloud Drive has no All files access, so git cannot read or write the shared storage — Configs ▸ General ▸ Permissions ▸ All files access", GitErrorKind.STORAGE)
        val args = req.optJSONObject("args") ?: JSONObject()
        val files = AuthFiles(scratch, req.optJSONObject("auth"))
        try {
            val result = execute(verb, dir, args, files.auth)
            val out = JSONObject().put("ok", true)
            files.knownHostsIfChanged()?.let { out.put("known_hosts", it) }
            if (result.length <= CHUNK) return out.put("result", result).toString()
            val id = UUID.randomUUID().toString()
            pending[id] = result
            return out.put("result", result.substring(0, CHUNK)).put("more", true).put("id", id).toString()
        } catch (t: Throwable) {
            return fail(t.message ?: t.javaClass.simpleName, GitErrorKind.classify(t))
        } finally {
            files.delete()
        }
    }

    fun chunk(req: JSONObject): String {
        val id = req.optString("id")
        val whole = pending[id] ?: return fail("chunk: unknown or finished id")
        val from = req.optInt("offset", 0).coerceIn(0, whole.length)
        val to = minOf(whole.length, from + CHUNK)
        val more = to < whole.length
        if (!more) pending.remove(id)
        return JSONObject().put("ok", true).put("result", whole.substring(from, to)).put("more", more).toString()
    }

    private fun <T> enc(s: KSerializer<T>, v: T): String = GitWire.json.encodeToString(s, v)

    private fun execute(verb: String, dir: File, a: JSONObject, auth: GitAuth): String {
        when (verb) {
            "init" -> { JGitEngine.init(dir).close(); return "" }
            "clone" -> { JGitEngine.clone(a.getString("url"), dir, auth, a.optInt("depth", 0)).close(); return "" }
        }
        return JGitEngine(dir).use { e ->
            val remote = a.optString("remote", "origin")
            when (verb) {
                "status" -> enc(GitStatusSnapshot.serializer(), e.status())
                "stage" -> { e.stage(strings(a, "paths")); "" }
                "stage_all" -> { e.stageAll(); "" }
                "unstage" -> { e.unstage(strings(a, "paths")); "" }
                "unstage_all" -> { e.unstageAll(); "" }
                "discard" -> { e.discard(a.getString("path")); "" }
                "commit" -> enc(GitCommitInfo.serializer(), e.commit(a.getString("message"), a.getString("name"), a.getString("email"), a.optBoolean("amend", false)))
                "push" -> enc(GitOpResult.serializer(), e.push(remote, auth))
                "force_push" -> enc(GitOpResult.serializer(), e.forcePush(remote, auth))
                "fetch" -> enc(GitOpResult.serializer(), e.fetch(remote, auth))
                "pull" -> enc(GitOpResult.serializer(), e.pull(remote, a.optBoolean("rebase", true), auth))
                "force_pull" -> enc(GitOpResult.serializer(), e.forcePull(remote, auth))
                "sync" -> enc(GitOpResult.serializer(), e.sync(a.getString("message"), a.getString("name"), a.getString("email"), remote, a.optBoolean("rebase", true), auth))
                "branches" -> enc(ListSerializer(GitBranchInfo.serializer()), e.branches())
                "log" -> enc(ListSerializer(GitCommitInfo.serializer()), e.log(a.optInt("max", 100), a.optString("path").ifEmpty { null }))
                "diff" -> e.diff(a.optString("path").ifEmpty { null }, a.optBoolean("staged", false))
                "commit_diff" -> e.commitDiff(a.getString("sha"))
                "remotes" -> enc(ListSerializer(GitRemoteInfo.serializer()), e.remotes())
                "add_remote" -> { e.addRemote(a.getString("name"), a.getString("url")); "" }
                "remove_remote" -> { e.removeRemote(a.getString("name")); "" }
                "set_remote_url" -> { e.setRemoteUrl(a.getString("name"), a.getString("url"), a.optBoolean("push", false)); "" }
                "resolve" -> { e.resolve(a.getString("path"), ConflictSide.valueOf(a.getString("side"))); "" }
                "mark_resolved" -> { e.markResolved(a.getString("path")); "" }
                "abort_merge" -> { e.abortMerge(); "" }
                "stash_push" -> enc(GitOpResult.serializer(), e.stashPush(a.optString("message"), a.optBoolean("untracked", true)))
                "stash_pop" -> enc(GitOpResult.serializer(), e.stashPop())
                "stash_list" -> enc(ListSerializer(String.serializer()), e.stashList())
                "set_upstream" -> enc(GitOpResult.serializer(), e.setUpstream(remote, a.optString("branch").ifEmpty { null }))
                "upstream" -> e.upstream().orEmpty()
                "merge_upstream" -> enc(GitOpResult.serializer(), e.mergeUpstream(remote, auth))
                "force_push_lease" -> enc(GitOpResult.serializer(), e.forcePushLease(remote, auth))
                "stash" -> enc(GitOpResult.serializer(), e.stashPush(a.optString("message")))
                "checkout_main" -> enc(GitOpResult.serializer(), e.checkoutMain())
                "rebase_continue" -> enc(GitOpResult.serializer(), e.rebaseContinue())
                "shrink", "keep_depth" -> enc(GitOpResult.serializer(), GitOpResult(false, "$verb rewrites a clone's depth in place, which JGit cannot do: it runs where git is a binary (cld.termux)"))
                else -> throw IllegalArgumentException("git verb '$verb' has no implementation")
            }
        }
    }

    private fun strings(a: JSONObject, key: String): List<String> {
        val arr = a.optJSONArray(key) ?: return emptyList()
        return (0 until arr.length()).map { arr.getString(it) }
    }

    private fun fail(why: String, kind: GitErrorKind = GitErrorKind.classify(why)) =
        JSONObject().put("ok", false).put("error", why).put("kind", kind.name).toString()

    companion object {
        const val CHUNK = 400_000

        /** True when [dir] is in the shared store: only then does the all-files grant matter. */
        fun touchesSharedStorage(dir: File): Boolean {
            val p = dir.path
            return p.startsWith("/storage/") || p.startsWith("/sdcard") || p.startsWith("/mnt/") || p.startsWith("/data/media/")
        }
    }
}

/**
 * The caller's credentials for ONE call, as this process uses them. A token or session is just the
 * value; an ssh key and a known_hosts become files in a private scratch directory (JGit's jsch wants
 * paths), removed by [delete]; [knownHostsIfChanged] is the extended known_hosts text, or null.
 */
internal class AuthFiles(scratchRoot: File, sent: JSONObject?) {
    private val json: JSONObject = sent ?: JSONObject()
    private val kind: String = json.optString("kind")
    private val dir: File? = if (kind == GitWire.KIND_SSH) File(scratchRoot, "git-" + UUID.randomUUID()) else null
    private val knownHostsFile: File? = dir?.let { File(it, "known_hosts") }
    private val sentKnownHosts: String = json.optString("known_hosts")

    val auth: GitAuth = when (kind) {
        GitWire.KIND_HTTPS -> GitAuth.Https(json.optString("username"), json.optString("secret"))
        GitWire.KIND_SESSION -> GitAuth.Session(json.optString("header"), json.optString("value"))
        GitWire.KIND_SSH -> {
            val d = dir!!
            d.mkdirs()
            ownerOnly(d, directory = true)
            val key = File(d, "id")
            key.writeText(json.optString("key")); ownerOnly(key)
            val hosts = knownHostsFile!!
            hosts.writeText(sentKnownHosts); ownerOnly(hosts)
            GitAuth.Ssh(key.absolutePath, json.optString("passphrase").ifEmpty { null }, hosts.absolutePath)
        }
        else -> GitAuth.None
    }

    fun knownHostsIfChanged(): String? {
        val f = knownHostsFile ?: return null
        val now = runCatching { f.readText() }.getOrDefault(sentKnownHosts)
        return now.takeIf { it != sentKnownHosts }
    }

    fun delete() { dir?.deleteRecursively() }

    private fun ownerOnly(f: File, directory: Boolean = false) {
        f.setReadable(false, false); f.setWritable(false, false); if (directory) f.setExecutable(false, false)
        f.setReadable(true, true); f.setWritable(true, true); if (directory) f.setExecutable(true, true)
    }
}

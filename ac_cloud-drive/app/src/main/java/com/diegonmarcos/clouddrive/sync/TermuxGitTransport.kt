package com.diegonmarcos.clouddrive.sync

import android.content.Context
import android.os.Looper
import com.diegonmarcos.clouddrive.sync.store.StoreRoute
import com.diegonmarcos.clouddrive.sync.store.StoreShell
import com.diegonmarcos.cloudlib.gitsync.GitBranchInfo
import com.diegonmarcos.cloudlib.gitsync.GitCommitInfo
import com.diegonmarcos.cloudlib.gitsync.GitEngine
import com.diegonmarcos.cloudlib.gitsync.GitOpResult
import com.diegonmarcos.cloudlib.gitsync.GitRemoteInfo
import com.diegonmarcos.cloudlib.gitsync.GitTransport
import com.diegonmarcos.cloudlib.gitsync.GitTransports
import com.diegonmarcos.cloudlib.gitsync.GitWire
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Every git call for a repository in the shared store, answered by the git CLI inside cld.termux
 * (CLOUD_EXEC), in the engine's own wire: the git screens (status, stage, commit, diff, log, push,
 * pull, branches, remotes, conflicts) and the workers get the same models whichever side runs git.
 * The git engine APK is another uid and cannot open the store, so this is the only path to it.
 * Installed by [install]; a directory outside the store still goes to the engine APK.
 * #1315 the route and the runner are the ONE [StoreRoute] / [StoreShell].
 */
class TermuxGitTransport(private val ctx: Context) : GitTransport {

    private val shell: StoreShell = StoreShell.of(ctx)

    override fun unavailable(): String? =
        if (shell.route.viaTermux) null
        else "the shared store is in Cloud Terminal Termux (cld.termux), which is not installed or does not allow this app: open it once, then retry"

    override fun session(): GitTransport.Session {
        unavailable()?.let { throw IOException(it) }
        if (Looper.myLooper() == Looper.getMainLooper()) throw IOException("git in cld.termux cannot run on the main thread")
        return object : GitTransport.Session {
            override fun git(request: JSONObject): JSONObject = JSONObject(answer(request))
            override fun chunk(request: JSONObject): JSONObject = JSONObject().put("ok", false).put("error", "this transport answers in one piece")
            override fun close() {}
        }
    }

    private fun fail(why: String) = JSONObject().put("ok", false).put("error", why).toString()
    private fun <T> enc(s: KSerializer<T>, v: T) = GitWire.json.encodeToString(s, v)
    private fun ok(result: String) = JSONObject().put("ok", true).put("result", result).toString()

    private fun answer(req: JSONObject): String {
        val verb = req.optString("verb")
        val host = req.optString("dir")
        val guest = shell.route.guest(host) ?: return fail("$host is not inside the shared store")
        val (plan, refusal) = TermuxGitWire.plan(verb, guest, req.optJSONObject("args") ?: JSONObject(), req.optJSONObject("auth"))
        if (plan == null) return fail(refusal ?: "git $verb refused")
        val r = if (plan.network) shell.job(plan.script, stdin = plan.stdin) else shell.run(plan.script, stdin = plan.stdin.ifEmpty { null })
        if (r.exit == StoreShell.REFUSED) return fail(r.err.ifBlank { "git $verb could not run in cld.termux" })
        val text = (r.out + (if (r.err.isNotBlank()) "\n" + r.err else "")).trim().takeLast(4000)
        if (verb in TermuxGitWire.OPS) return ok(enc(GitOpResult.serializer(), GitOpResult(r.ok, if (r.ok) "$verb done (in cld.termux)" else "$verb failed in cld.termux (exit ${r.exit})", text)))
        if (!r.ok) {
            if (verb == "log" && r.err.contains("does not have any commits")) return ok("[]")
            return fail(r.err.ifBlank { r.out }.ifBlank { "git $verb failed in cld.termux (exit ${r.exit})" }.trim().takeLast(2000))
        }
        return ok(when (verb) {
            "status" -> enc(com.diegonmarcos.cloudlib.gitsync.GitStatusSnapshot.serializer(), TermuxGitWire.parseStatus(r.out))
            "commit" -> enc(GitCommitInfo.serializer(), TermuxGitWire.parseLog(r.out).firstOrNull() ?: return fail("commit produced no commit"))
            "log" -> enc(ListSerializer(GitCommitInfo.serializer()), TermuxGitWire.parseLog(r.out))
            "branches" -> enc(ListSerializer(GitBranchInfo.serializer()), TermuxGitWire.parseBranches(r.out))
            "remotes" -> enc(ListSerializer(GitRemoteInfo.serializer()), TermuxGitWire.parseRemotes(r.out))
            "diff", "commit_diff" -> r.out
            else -> ""
        })
    }

    companion object {
        /**
         * Route a GitEngine through cld.termux ONLY where the in-process engine (libs:git-core) cannot reach: a directory in
         * the shared store, which lives in cld.termux's private home. #1408 everything else, storage volumes (emulated, USB)
         * included, runs in-process under Cloud Drive's own All files access. Idempotent.
         */
        fun install(ctx: Context) {
            val app = ctx.applicationContext
            GitTransports.router = { c, dir -> StoreRoute.of(c).let { r -> if (r.inStore(dir.absolutePath) && r.viaTermux) TermuxGitTransport(c.applicationContext) else null } }
            GitEngine.repositoryProbe = { dir ->
                if (!StoreRoute.of(app).inStore(dir.absolutePath) || Looper.myLooper() == Looper.getMainLooper()) null else StoreGit.isRepository(app, dir)
            }
        }
    }
}

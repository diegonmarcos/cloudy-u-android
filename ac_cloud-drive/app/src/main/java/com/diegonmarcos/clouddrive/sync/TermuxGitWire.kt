package com.diegonmarcos.clouddrive.sync

import com.diegonmarcos.cloudlib.gitsync.GitBranchInfo
import com.diegonmarcos.cloudlib.gitsync.GitChange
import com.diegonmarcos.cloudlib.gitsync.GitCommitInfo
import com.diegonmarcos.cloudlib.gitsync.GitFileStatus
import com.diegonmarcos.cloudlib.gitsync.GitRemoteInfo
import com.diegonmarcos.cloudlib.gitsync.GitStatusSnapshot
import com.diegonmarcos.cloudlib.sharedstore.StoreExec.Companion.q
import org.json.JSONObject
import java.util.Base64

/**
 * The git sync engine's wire (GitWire: verb + args + auth in, the model as JSON out) answered by the
 * git CLI inside cld.termux, for repositories in the shared store. Pure: this builds the script for a
 * verb and parses its output into the SAME models the engine APK returns, so every git screen works
 * unchanged. [TermuxGitTransport] runs the scripts.
 */
object TermuxGitWire {

    /** What to run: [script] in the guest, [stdin] for the credential header, [network] = long-running (detached job). */
    data class Plan(val script: String, val stdin: String = "", val network: Boolean = false)

    /** Verbs that answer a GitOpResult. #1138 adds the compound ones the engine APK has no verb for (RepoCompound sends them). */
    val OPS = setOf("push", "force_push", "fetch", "pull", "force_pull", "sync", "force_push_lease", "merge_upstream", "stash", "stash_pop", "shrink", "checkout_main", "keep_depth", "rebase_continue")

    private const val FS = "%x1f"
    private const val RS = "%x1e"
    const val LOG_FORMAT = "%H${FS}%an${FS}%ae${FS}%at${FS}%P${FS}%s${FS}%B$RS"

    /** A repo-relative path the scripts may touch: no absolute path, no `..`, not empty. */
    fun safe(p: String): Boolean = p.isNotEmpty() && !p.startsWith("/") && p.split('/').none { it == ".." } && !p.contains('\u0000')

    private fun sha(s: String) = s.isNotEmpty() && s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    /** The `Name: value` header the auth travels as (stdin, never argv), or "" for none. Ssh keys stay in cld.termux's own ~/.ssh. */
    fun authHeader(auth: JSONObject?): String = when (auth?.optString("kind")) {
        "https" -> "Authorization: Basic " + Base64.getEncoder().encodeToString(
            ("${auth.optString("username").ifBlank { "x-access-token" }}:${auth.optString("secret")}").toByteArray())
        "session" -> "${auth.optString("header")}: ${auth.optString("value")}"
        else -> ""
    }

    private fun g(dir: String, args: String) = "git -C ${q(dir)} $args"
    /** `git` that adds the credential header held in $H (read from stdin first) when there is one. */
    private fun hdr(dir: String, args: String) =
        "if [ -n \"\$H\" ]; then git -C ${q(dir)} -c http.extraHeader=\"\$H\" $args; else git -C ${q(dir)} $args; fi"
    private fun gAuthed(dir: String, args: String) = "H=\"\$(cat)\"; " + hdr(dir, args)
    /**
     * #1392 a clone whose branch tracks nothing ("upstream (no upstream)") gets origin/<branch> as its upstream when that ref exists;
     * run after a clone and before every pull, force pull and merge, so '@{u}' resolves. Never fails the script.
     */
    fun track(dir: String, remote: String = "origin"): String =
        "{ " + g(dir, "rev-parse --abbrev-ref --symbolic-full-name '@{u}'") + " >/dev/null 2>&1 || { B=\"\$(" + g(dir, "symbolic-ref --short -q HEAD") + ")\"; " +
            "[ -n \"\$B\" ] && " + g(dir, "rev-parse -q --verify \"refs/remotes/${remote}/\$B\"") + " >/dev/null 2>&1 && " +
            g(dir, "config \"branch.\$B.remote\" ${q(remote)}") + " && " + g(dir, "config \"branch.\$B.merge\" \"refs/heads/\$B\"") + "; }; } || true"
    private fun paths(a: JSONObject): List<String>? {
        val arr = a.optJSONArray("paths") ?: return emptyList()
        val l = (0 until arr.length()).map { arr.getString(it) }
        return if (l.all(::safe)) l else null
    }

    /** The script for [verb] on guest directory [dir]; null with [refusal] set when the arguments are not safe. */
    fun plan(verb: String, dir: String, a: JSONObject, auth: JSONObject?): Pair<Plan?, String?> {
        val remote = a.optString("remote", "origin").ifBlank { "origin" }
        if (remote.startsWith("-") || remote.any { it.isWhitespace() }) return null to "bad remote name"
        val header = authHeader(auth)
        fun ok(s: String, net: Boolean = false, stdin: String = "") = Plan("set -e; $s", stdin, net) to null
        return when (verb) {
            "status" -> ok(g(dir, "status --porcelain=v2 --branch -z") + "; printf '\\n@@STATE '; GD=\"\$(git -C ${q(dir)} rev-parse --absolute-git-dir)\"; " +
                "if [ -d \"\$GD/rebase-merge\" ] || [ -d \"\$GD/rebase-apply\" ]; then echo REBASING; elif [ -f \"\$GD/MERGE_HEAD\" ]; then echo MERGING; " +
                "elif [ -f \"\$GD/CHERRY_PICK_HEAD\" ]; then echo CHERRY_PICKING; else echo SAFE; fi")
            "stage" -> paths(a)?.let { ok(g(dir, "add -- " + it.joinToString(" ") { p -> q(p) })) } ?: (null to "unsafe path")
            "stage_all" -> ok(g(dir, "add -A"))
            "unstage" -> paths(a)?.let { l -> val ps = l.joinToString(" ") { p -> q(p) }; ok(g(dir, "reset -q -- $ps") + " || " + g(dir, "rm -r --cached -q --ignore-unmatch -- $ps")) } ?: (null to "unsafe path")
            "unstage_all" -> ok(g(dir, "reset -q") + " || " + g(dir, "rm -r --cached -q --ignore-unmatch -- ."))
            "discard" -> a.optString("path").takeIf(::safe)?.let { p ->
                ok("if ${g(dir, "ls-files --error-unmatch -- ${q(p)}")} >/dev/null 2>&1; then ${g(dir, "checkout HEAD -- ${q(p)}")}; else rm -rf -- ${q("$dir/$p")}; fi")
            } ?: (null to "unsafe path")
            "commit" -> ok(g(dir, "-c user.name=${q(a.optString("name"))} -c user.email=${q(a.optString("email"))} commit -q " +
                (if (a.optBoolean("amend", false)) "--amend " else "") + "-m ${q(a.optString("message"))}") + "; " + g(dir, "log -1 --format=${q(LOG_FORMAT)}"))
            "push" -> ok(gAuthed(dir, "push ${q(remote)}"), true, header)
            "force_push" -> ok(gAuthed(dir, "push --force ${q(remote)}"), true, header)
            "fetch" -> ok(gAuthed(dir, "fetch ${q(remote)}"), true, header)
            "pull" -> ok(gAuthed(dir, "fetch ${q(remote)}") + "; " + track(dir, remote) + "; " + hdr(dir, "pull ${if (a.optBoolean("rebase", true)) "--rebase" else "--no-rebase"} ${q(remote)}"), true, header)
            "force_pull" -> ok(gAuthed(dir, "fetch ${q(remote)}") + "; " + track(dir, remote) + "; " + g(dir, "reset --hard '@{u}'"), true, header)
            // #1138 compound sync verbs and the local-only history trim.
            "force_push_lease" -> ok(gAuthed(dir, "push --force-with-lease ${q(remote)}"), true, header)
            "merge_upstream" -> ok(gAuthed(dir, "fetch ${q(remote)}") + "; " + track(dir, remote) + "; if " +
                g(dir, "-c user.name=${q(a.optString("name"))} -c user.email=${q(a.optString("email"))} merge --no-edit '@{u}'") + "; then :; else rc=\$?; " +
                "if [ -f \"\$(git -C ${q(dir)} rev-parse --absolute-git-dir)/MERGE_HEAD\" ]; then " + g(dir, "merge --abort") +
                "; echo 'merge conflict: merge aborted, nothing changed' >&2; exit 3; fi; exit \$rc; fi", true, header)
            "stash" -> ok(g(dir, "-c user.name=${q(a.optString("name"))} -c user.email=${q(a.optString("email"))} stash push --include-untracked -m ${q(a.optString("message"))}"))
            // #1356 Stash Pop: the newest stash back onto the tree; a conflict leaves it in the list and is reported.
            "stash_pop" -> ok(g(dir, "-c user.name=${q(a.optString("name"))} -c user.email=${q(a.optString("email"))} stash pop"))
            // #1289 Solve Conflicts: continue the rebase in progress once every file is staged; GIT_EDITOR=true keeps each message. Never a push.
            "rebase_continue" -> ok("GIT_EDITOR=true " + g(dir, "-c user.name=${q(a.optString("name"))} -c user.email=${q(a.optString("email"))} rebase --continue"))
            "checkout_main" -> ok(g(dir, "checkout main") + " 2>/dev/null || " + g(dir, "checkout master"))
            "shrink" -> a.optInt("depth", 0).takeIf { it in 1..1000 }?.let { d ->
                ok(gAuthed(dir, "fetch --depth $d ${q(remote)}") + "; " + g(dir, "reflog expire --expire=now --all") + "; " + g(dir, "gc --prune=now -q"), true, header)
            } ?: (null to "bad depth")
            // #1254 KEEP DEPTH: the SAME clone, shrunk in place. It re-checks the gate the page already applied (clean, an
            // upstream, nothing unpushed: exit 4 names which), fetches ONLY the remote's default branch at depth N, points that
            // branch at it, then drops the reflog and the unreachable objects. Never re-clones, never writes the remote.
            "keep_depth" -> if (!Regex("[A-Za-z0-9._-]+").matches(remote)) null to "bad remote name" else a.optInt("depth", 0).takeIf { it in 1..1000 }?.let { d ->
                val r = q(remote)
                ok("[ -z \"\$(" + g(dir, "status --porcelain") + ")\" ] || { echo 'skipped: uncommitted changes' >&2; exit 4; }; " +
                    "U=\"\$(" + g(dir, "rev-parse --abbrev-ref --symbolic-full-name '@{u}'") + " 2>/dev/null)\" || { echo 'skipped: no upstream' >&2; exit 4; }; " +
                    "[ \"\$(" + g(dir, "rev-list --count '@{u}..HEAD'") + ")\" = 0 ] || { echo 'skipped: unpushed commits' >&2; exit 4; }; " +
                    "B=\"\$(" + g(dir, "symbolic-ref --short refs/remotes/$r/HEAD") + " 2>/dev/null || true)\"; B=\"\${B#$remote/}\"; [ -n \"\$B\" ] || B=\"\${U#*/}\"; " +
                    gAuthed(dir, "fetch -q --depth $d $r \"+refs/heads/\$B:refs/remotes/$remote/\$B\"") + "; " +
                    g(dir, "checkout -q -B \"\$B\" \"refs/remotes/$remote/\$B\"") + "; " +
                    g(dir, "branch -q --set-upstream-to=\"$remote/\$B\" \"\$B\"") + "; " +
                    g(dir, "reflog expire --expire=now --all") + "; " + g(dir, "gc --prune=now -q") + "; echo \"kept depth $d on \$B\"", true, header)
            } ?: (null to "bad depth")
            "sync" -> ok("H=\"\$(cat)\"; " + g(dir, "add -A") + "; " + g(dir, "diff --cached --quiet") + " || " +
                g(dir, "-c user.name=${q(a.optString("name"))} -c user.email=${q(a.optString("email"))} commit -q -m ${q(a.optString("message"))}") + "; " +
                hdr(dir, "pull ${if (a.optBoolean("rebase", true)) "--rebase" else "--no-rebase"} ${q(remote)}") + "; " + hdr(dir, "push ${q(remote)}"), true, header)
            "branches" -> ok(g(dir, "for-each-ref --format=${q("%(refname)${FS}%(refname:short)${FS}%(objectname)${FS}%(HEAD)")} refs/heads refs/remotes"))
            "log" -> ok(g(dir, "log -n ${a.optInt("max", 100).coerceIn(1, 5000)} --format=${q(LOG_FORMAT)}" +
                (a.optString("path").takeIf { it.isNotEmpty() }?.let { if (safe(it)) " -- ${q(it)}" else return null to "unsafe path" } ?: "")))
            "diff" -> ok(g(dir, "diff ${if (a.optBoolean("staged", false)) "--cached " else ""}" +
                (a.optString("path").takeIf { it.isNotEmpty() }?.let { if (safe(it)) "-- ${q(it)}" else return null to "unsafe path" } ?: "")))
            "commit_diff" -> a.optString("sha").takeIf(::sha)?.let { ok(g(dir, "show --format= -m --first-parent $it")) } ?: (null to "bad commit id")
            "remotes" -> ok(g(dir, "remote -v"))
            "add_remote" -> ok(g(dir, "remote add ${q(a.optString("name"))} ${q(a.optString("url"))}"))
            "remove_remote" -> ok(g(dir, "remote remove ${q(a.optString("name"))}"))
            "set_remote_url" -> ok(g(dir, "remote set-url ${if (a.optBoolean("push", false)) "--push " else ""}${q(a.optString("name"))} ${q(a.optString("url"))}"))
            "resolve" -> a.optString("path").takeIf(::safe)?.let { p ->
                val side = if (a.optString("side") == "OURS") "--ours" else "--theirs"
                ok(g(dir, "checkout $side -- ${q(p)}") + "; " + g(dir, "add -- ${q(p)}"))
            } ?: (null to "unsafe path")
            "mark_resolved" -> a.optString("path").takeIf(::safe)?.let { ok(g(dir, "add -- ${q(it)}")) } ?: (null to "unsafe path")
            "abort_merge" -> ok(g(dir, "merge --abort") + " 2>/dev/null || " + g(dir, "reset --hard"))
            "init" -> ok("mkdir -p ${q(dir)}; git init -q ${q(dir)}")
            "clone" -> {
                val depth = a.optInt("depth", 0).let { if (it > 0) "--depth $it " else "" }
                ok("mkdir -p \"\$(dirname ${q(dir)})\"; H=\"\$(cat)\"; if [ -n \"\$H\" ]; then git -c http.extraHeader=\"\$H\" clone $depth${q(a.optString("url"))} ${q(dir)}; " +
                    "else git clone $depth${q(a.optString("url"))} ${q(dir)}; fi; " + track(dir), true, header)
            }
            else -> null to "git verb '$verb' has no implementation in cld.termux"
        }
    }

    // ── parsers ──────────────────────────────────────────────────────────

    private fun change(c: Char): GitChange? = when (c) { 'A' -> GitChange.ADDED; 'M', 'T', 'R', 'C' -> GitChange.MODIFIED; 'D' -> GitChange.DELETED; else -> null }

    private val CONFLICT = mapOf("DD" to "BOTH_DELETED", "AU" to "ADDED_BY_US", "UD" to "DELETED_BY_THEM", "UA" to "ADDED_BY_THEM",
        "DU" to "DELETED_BY_US", "AA" to "BOTH_ADDED", "UU" to "BOTH_MODIFIED")

    /** `status --porcelain=v2 --branch -z` + the `@@STATE` trailer → the engine's snapshot. */
    fun parseStatus(out: String): GitStatusSnapshot {
        val state = out.substringAfter("\n@@STATE ", "SAFE").trim().ifEmpty { "SAFE" }
        val tokens = out.substringBefore("\n@@STATE ").split('\u0000')
        var branch: String? = null; var upstream: String? = null; var ahead = 0; var behind = 0
        val files = ArrayList<GitFileStatus>()
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i++]
            if (t.isEmpty()) continue
            when {
                t.startsWith("# branch.head ") -> branch = t.removePrefix("# branch.head ").takeIf { it != "(detached)" }
                t.startsWith("# branch.upstream ") -> upstream = t.removePrefix("# branch.upstream ")
                t.startsWith("# branch.ab ") -> t.removePrefix("# branch.ab ").split(' ').let { ahead = it[0].removePrefix("+").toIntOrNull() ?: 0; behind = it.getOrNull(1)?.removePrefix("-")?.toIntOrNull() ?: 0 }
                t.startsWith("1 ") -> t.split(' ', limit = 9).let { f -> files += GitFileStatus(f[8], change(f[1][0]), change(f[1][1])) }
                t.startsWith("2 ") -> { t.split(' ', limit = 10).let { f -> files += GitFileStatus(f[9], change(f[1][0]), change(f[1][1])) }; i++ }
                t.startsWith("u ") -> t.split(' ', limit = 11).let { f -> files += GitFileStatus(f[10], null, null, true, CONFLICT[f[1]] ?: "BOTH_MODIFIED") }
                t.startsWith("? ") -> files += GitFileStatus(t.substring(2), null, GitChange.UNTRACKED)
            }
        }
        return GitStatusSnapshot(branch, upstream, ahead, behind, files, state)
    }

    /** [LOG_FORMAT] records → commits. */
    fun parseLog(out: String): List<GitCommitInfo> = out.split('\u001e').map { it.trim('\n') }.filter { it.isNotEmpty() }.mapNotNull { rec ->
        val f = rec.split('\u001f', limit = 7)
        if (f.size < 7) null else GitCommitInfo(f[0], f[5], f[6].trimEnd(), f[1], f[2], f[3].toLongOrNull() ?: 0L, f[4].split(' ').filter { it.isNotEmpty() })
    }

    fun parseBranches(out: String): List<GitBranchInfo> = out.lines().filter { it.isNotBlank() }.mapNotNull { l ->
        val f = l.split('\u001f')
        if (f.size < 4 || f[0].endsWith("/HEAD")) null
        else GitBranchInfo(f[1], f[0], f[0].startsWith("refs/remotes/"), f[3].trim() == "*", f[2])
    }

    fun parseRemotes(out: String): List<GitRemoteInfo> {
        val fetch = LinkedHashMap<String, String>(); val push = HashMap<String, String>()
        for (l in out.lines()) {
            val m = Regex("""^(\S+)\t(.+) \((fetch|push)\)$""").find(l.trim('\r')) ?: continue
            if (m.groupValues[3] == "fetch") fetch[m.groupValues[1]] = m.groupValues[2] else push[m.groupValues[1]] = m.groupValues[2]
        }
        return fetch.map { (n, u) -> GitRemoteInfo(n, u, push[n] ?: u) }
    }
}

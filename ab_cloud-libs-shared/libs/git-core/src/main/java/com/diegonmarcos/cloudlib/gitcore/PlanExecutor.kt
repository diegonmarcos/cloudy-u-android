package com.diegonmarcos.cloudlib.gitcore

import com.diegonmarcos.cloudlib.gitsync.ConflictSide
import com.diegonmarcos.cloudlib.gitsync.GitAuth
import com.diegonmarcos.cloudlib.gitsync.GitOpResult
import java.io.File

/**
 * #1934 EXECUTES a plan of [GitStep]s (the same argv the command preview prints) with the in-process engine ([JGitEngine]), one step after the
 * other, stopping at the first failure. [binary] runs the steps JGit cannot (a partial-clone filter, history truncation): the app wires it to
 * git inside cld.termux, the tests to the runner's git binary; null = those steps answer "needs the git binary".
 * [executed] records every argv actually run, which is what the preview-equals-executed-argv tests compare.
 */
class PlanExecutor(
    private val workTree: File,
    private val auth: GitAuth = GitAuth.None,
    private val authorName: String = "cloud-drive",
    private val authorEmail: String = "drive@cloud.local",
    private val binary: ((List<String>, File) -> GitOpResult)? = null,
) {
    val executed = mutableListOf<List<String>>()

    fun run(steps: List<GitStep>): GitOpResult {
        val lines = mutableListOf<String>()
        for (s in steps) {
            val r = runCatching { step(s) }.getOrElse { GitOpResult(false, it.message ?: it.toString()) }
            lines += s.line() + " -> " + r.summary
            if (!r.ok && !s.tolerant) return GitOpResult(false, lines.joinToString("; "), r.details)
        }
        return GitOpResult(true, lines.joinToString("; "))
    }

    private fun flag(a: List<String>, f: String) = f in a

    private fun step(s: GitStep): GitOpResult {
        executed += s.argv
        val a = s.argv
        require(a.firstOrNull() == "git" && a.size >= 2) { "not a git command: $a" }
        val verb = a[1]
        val rest = a.drop(2)
        if (a.any { it.startsWith("--recurse-submodules") }) return GitOpResult(false, "submodules: the in-process engine has no recursive submodule support")
        if (verb == "clone") return clone(a)
        // a shallow fetch / history truncation rewrites the clone's depth in place, which JGit cannot do: it runs where git is a binary
        if ("--depth" in a || verb == "reflog" || verb == "gc") return binary?.invoke(a, workTree) ?: GitOpResult(false, "git $verb needs the git binary")
        return JGitEngine(workTree).use { e ->
            when (verb) {
                "add" -> { e.stageAll(); GitOpResult(true, "staged") }
                "commit" -> {
                    val msg = rest.getOrNull(rest.indexOf("-m") + 1).orEmpty()
                    if (e.status().staged.isEmpty()) GitOpResult(false, "nothing to commit")
                    else GitOpResult(true, "committed " + e.commit(msg, authorName, authorEmail).sha.take(7))
                }
                "status" -> e.statusLine()
                "fetch" -> remotes(e, flag(rest, "--all")).map { r -> e.fetchWith(r, auth, flag(rest, "--prune"), flag(rest, "--tags"), flag(rest, "--dry-run")) }.let { l -> l.firstOrNull { !it.ok } ?: l.last() }
                "pull" -> pull(e, rest)
                "push" -> e.pushWith("origin", flag(rest, "--all"), flag(rest, "--tags"), flag(rest, "--force"), flag(rest, "--force-with-lease"), flag(rest, "--dry-run"), auth)
                "reset" -> if (rest == listOf("--hard", "@{u}")) e.forcePull("origin", auth) else GitOpResult(false, "unsupported reset: $rest")
                else -> binary?.invoke(a, workTree) ?: GitOpResult(false, "git $verb needs the git binary")
            }
        }
    }

    private fun remotes(e: JGitEngine, all: Boolean): List<String> = if (all) e.remoteNames().ifEmpty { listOf("origin") } else listOf("origin")

    private fun pull(e: JGitEngine, a: List<String>): GitOpResult {
        val dry = flag(a, "--dry-run")
        val x = a.indexOf("-X")
        val favor = if (x >= 0) (if (a.getOrNull(x + 1) == "theirs") ConflictSide.THEIRS else ConflictSide.OURS) else null
        if (flag(a, "--rebase") && favor != null) return GitOpResult(false, "winner with rebase is not supported by the engine")
        // The fetch side of a pull: prune and --all are fetch flags; a dry run downloads nothing and changes nothing.
        val pre = remotes(e, flag(a, "--all")).map { e.fetchWith(it, auth, flag(a, "--prune"), flag(a, "--tags"), dry) }.firstOrNull { !it.ok }
        if (pre != null) return pre
        if (dry) return GitOpResult(true, "dry run: pull skipped after fetch")
        val stashed = flag(a, "--autostash") && e.status().files.isNotEmpty() && e.stashPush("autostash").ok
        val r = e.pullWith("origin", rebase = flag(a, "--rebase"), ffOnly = flag(a, "--ff-only"), tags = flag(a, "--tags"), favor = favor, auth = auth)
        if (stashed) {
            val p = e.stashPop()
            if (!p.ok) return GitOpResult(false, r.summary + "; autostash " + p.summary, p.details)
        }
        return r
    }

    /** `git clone [--bare] [--single-branch --branch B] [--depth N] [--filter=F] URL DEST`: JGit, or the git binary for a partial-clone filter. */
    private fun clone(a: List<String>): GitOpResult {
        val rest = a.drop(2)
        val url = rest[rest.size - 2]
        val dest = File(rest.last())
        val filter = rest.firstOrNull { it.startsWith("--filter=") }?.removePrefix("--filter=").orEmpty()
        if (filter.isNotBlank()) return binary?.invoke(a, workTree) ?: GitOpResult(false, "a partial-clone filter needs the git binary")
        dest.parentFile?.mkdirs()
        val depth = rest.indexOf("--depth").let { if (it >= 0) rest[it + 1].toInt() else 0 }
        val branch = rest.indexOf("--branch").let { if (it >= 0) rest[it + 1] else "" }
        JGitEngine.clone(url, dest, auth, depth, "--bare" in rest, "--single-branch" in rest, branch).close()
        return GitOpResult(true, "cloned into ${dest.name}")
    }
}

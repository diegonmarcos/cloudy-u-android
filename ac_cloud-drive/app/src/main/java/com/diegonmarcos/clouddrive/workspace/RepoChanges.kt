package com.diegonmarcos.clouddrive.workspace

import com.diegonmarcos.cloudlib.gitrepos.AutoCommit
import com.diegonmarcos.cloudlib.gitsync.GitChange
import com.diegonmarcos.cloudlib.gitsync.GitEngine
import com.diegonmarcos.cloudlib.gitsync.GitFileStatus
import com.diegonmarcos.superapp.repoworkspace.RepoWorkspace
import com.diegonmarcos.superapp.repoworkspace.RepoWorkspace.ChangedFile
import java.io.File

/**
 * #1855 P4a libs:repo-workspace v2, the READ side Drive serves and its Commit screen shows: the changed files of a clone, one
 * unified diff of one file. The rules are pure (status mapping, row building, diff bounding, path validation) so the JVM suite
 * pins them; [read] and [diffOf] only gather from the git engine (JGit through libs:git-sync). Nothing here writes to a repository.
 */
object RepoChanges {
    /** Past this many changed files the per-file +/- counts are not computed (a whole-tree diff of a huge change is not worth the wait). */
    const val COUNT_LIMIT = 400
    /** An untracked file larger than this is never read whole (not for its line count, not for its diff). */
    const val UNTRACKED_READ_MAX = 1024 * 1024L

    /** JGit's change kinds as the contract's letters; a renamed one (a name a future engine may report) is R. */
    fun letter(c: GitChange): String = when (c) {
        GitChange.ADDED -> RepoWorkspace.ST_ADDED
        GitChange.MODIFIED -> RepoWorkspace.ST_MODIFIED
        GitChange.DELETED -> RepoWorkspace.ST_DELETED
        GitChange.UNTRACKED -> RepoWorkspace.ST_UNTRACKED
    }

    fun letter(name: String?): String = when (name?.uppercase()) {
        "ADDED", "A" -> RepoWorkspace.ST_ADDED
        "DELETED", "D" -> RepoWorkspace.ST_DELETED
        "RENAMED", "R" -> RepoWorkspace.ST_RENAMED
        "UNTRACKED", "?" -> RepoWorkspace.ST_UNTRACKED
        else -> RepoWorkspace.ST_MODIFIED
    }

    /**
     * The rows of [files]: a file with a change on both sides gets two (the index side, then the worktree side), conflicting files
     * are rows too (marked modified, unstaged). [counts] are the +/- per path of the staged and of the worktree diff.
     */
    fun rows(files: List<GitFileStatus>, staged: Map<String, Pair<Int, Int>>, worktree: Map<String, Pair<Int, Int>>, untrackedLines: (String) -> Int = { 0 }): List<ChangedFile> {
        val out = ArrayList<ChangedFile>()
        for (f in files.sortedBy { it.path }) {
            f.staged?.let { c -> val n = staged[f.path]; out += ChangedFile(f.path, letter(c), true, n?.first ?: 0, n?.second ?: 0) }
            val u = f.unstaged ?: if (f.conflicting && f.staged == null) GitChange.MODIFIED else null
            if (u != null) {
                val n = worktree[f.path]
                val untracked = u == GitChange.UNTRACKED
                out += ChangedFile(f.path, letter(u), false, if (untracked) untrackedLines(f.path) else n?.first ?: 0, if (untracked) 0 else n?.second ?: 0)
            }
        }
        return out
    }

    /** The server-side bound of a diff: the caller's ask, never above [RepoWorkspace.DIFF_MAX_BYTES], at least one byte. */
    fun bound(ask: Int): Int = ask.coerceIn(1, RepoWorkspace.DIFF_MAX_BYTES)

    /** [text] cut to at most [maxBytes] UTF-8 bytes on a line boundary (a character is never split), with the marker line appended when cut. */
    fun bounded(text: String, maxBytes: Int): String {
        val max = bound(maxBytes)
        if (text.toByteArray(Charsets.UTF_8).size <= max) return text
        val sb = StringBuilder()
        var used = 0
        for (line in text.split('\n')) {
            val n = line.toByteArray(Charsets.UTF_8).size + 1
            if (used + n > max) break
            sb.append(line).append('\n'); used += n
        }
        return sb.append(RepoWorkspace.DIFF_TRUNCATED).append('\n').toString()
    }

    /** "" = [path] may be reviewed (it is one of Drive's listed repositories); else the reason. */
    fun reviewRefusal(rows: List<RepoWorkspace.RepoInfo>, path: String?): String =
        if (path.isNullOrBlank()) "not a repository path"
        else if (RepoWorkspaceCatalog.statusOf(rows, path) == null) "Cloud Drive does not list that repository" else ""

    /** "" = Drive's Commit / Push screen may be opened for [path]: it is listed AND registered on Drive's Git page (the screen runs Drive's own jobs, which act on registered clones). */
    fun handOffRefusal(rows: List<RepoWorkspace.RepoInfo>, registered: Set<String>, path: String?): String {
        val r = reviewRefusal(rows, path)
        if (r.isNotEmpty()) return r
        return if (path!!.trimEnd('/') in registered) "" else "not on Cloud Drive's Git page yet: add the repository there first"
    }

    /** A file the contract may diff: a valid relative path that is among [changed]. */
    fun diffRefusal(changed: List<ChangedFile>, relpath: String?): String =
        if (!RepoWorkspace.validRelPath(relpath)) "not a repository file path"
        else if (changed.none { it.relpath == relpath }) "that file has no change" else ""

    // ---- gathering (JGit); blocking, off the main thread ----

    fun read(engine: GitEngine, dir: File): List<ChangedFile> {
        val files = engine.status().files
        if (files.isEmpty()) return emptyList()
        val countable = files.size <= COUNT_LIMIT
        val st = if (countable && files.any { it.hasStaged }) runCatching { AutoCommit.parseDiff(engine.diff(staged = true)) }.getOrDefault(emptyMap()) else emptyMap()
        val wt = if (countable && files.any { it.hasUnstaged }) runCatching { AutoCommit.parseDiff(engine.diff(staged = false)) }.getOrDefault(emptyMap()) else emptyMap()
        return rows(files, st, wt) { rel -> if (countable) untrackedLines(File(dir, rel)) else 0 }
    }

    fun untrackedLines(f: File): Int = runCatching {
        if (!f.isFile || f.length() > UNTRACKED_READ_MAX) 0 else f.useLines { it.count() }
    }.getOrDefault(0)

    /** The diff of [rel]: the staged part, then the worktree part, bounded to [maxBytes]. */
    fun diffOf(engine: GitEngine, dir: File, rel: String, maxBytes: Int): String {
        val sb = StringBuilder()
        val st = runCatching { engine.diff(rel, staged = true) }.getOrDefault("")
        if (st.isNotBlank()) sb.append(st).also { if (!st.endsWith("\n")) sb.append('\n') }
        val f = File(dir, rel)
        // An untracked file is never read whole past the cap (the engine would): say so instead.
        val wt = if (f.isFile && f.length() > UNTRACKED_READ_MAX && engine.status().files.any { it.path == rel && it.unstaged == GitChange.UNTRACKED })
            "--- /dev/null\n+++ b/$rel\n+(file larger than ${UNTRACKED_READ_MAX / 1024} KB, not shown)\n"
        else runCatching { engine.diff(rel, staged = false) }.getOrDefault("")
        sb.append(wt)
        return bounded(sb.toString(), maxBytes)
    }
}

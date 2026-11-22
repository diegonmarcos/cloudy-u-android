package com.diegonmarcos.clouddrive.workspace

import com.diegonmarcos.clouddrive.sync.GitStatusReader
import com.diegonmarcos.clouddrive.sync.GitStorage
import com.diegonmarcos.clouddrive.sync.LocalRepos
import com.diegonmarcos.superapp.repoworkspace.RepoWorkspace
import com.diegonmarcos.superapp.repoworkspace.RepoWorkspace.RepoInfo
import java.io.File

/**
 * #1855 P1 the PURE half of libs:repo-workspace's server: which repositories Drive knows, on which volume, from Drive's own
 * rules (GitStorage: a root's git folder, case-insensitively, and the sub-folders holding a `.git`). Plain java.io.File, so
 * RepoWorkspaceCatalogTest runs the exact code the binder serves. No listing of its own: [GitStorage.gitFolder] and
 * [GitStorage.repos] are the Git page's; no probe of its own: the status columns come from the page's [GitStatusReader] cache.
 */
object RepoWorkspaceCatalog {

    /** One place repositories live: a volume kind (a [GitStorage.Kind] id) and the root whose git folder holds them. */
    data class Source(val volume: String, val root: File?)

    /** Drive's roots as sources: the store, its legacy mirror (still a store volume), Emulated, every attached drive. */
    fun sources(roots: GitStorage.Roots, legacyMirror: File? = null): List<Source> =
        listOf(Source(GitStorage.Kind.STORE.id, roots.store), Source(GitStorage.Kind.STORE.id, legacyMirror), Source(GitStorage.Kind.EMULATED.id, roots.emulated)) +
            roots.drives.map { Source(GitStorage.Kind.EXTERNAL.id, it) }

    /** `ref: refs/heads/main` -> main; a raw sha -> its first 7 characters; "" when HEAD is unreadable. */
    fun branchOf(dir: File): String {
        val git = LocalRepos.gitDirOf(dir) ?: return ""
        val head = runCatching { File(git, "HEAD").readText().trim() }.getOrDefault("")
        return when {
            head.startsWith("ref:") -> head.removePrefix("ref:").trim().removePrefix("refs/heads/")
            head.length >= 7 -> head.take(7)
            else -> ""
        }
    }

    /** The clone's last fetch / pull (FETCH_HEAD time, epoch ms); 0 = never or unreadable. Cheap: one stat. */
    fun lastSyncOf(dir: File): Long {
        val git = LocalRepos.gitDirOf(dir) ?: return 0L
        val common = File(git, "commondir").takeIf { it.isFile }?.let { c -> runCatching { c.readText().trim() }.getOrNull()?.let { File(it).let { f -> if (f.isAbsolute) f else File(git, it) } } }
        return listOfNotNull(File(git, "FETCH_HEAD"), common?.let { File(it, "FETCH_HEAD") }).map { it.lastModified() }.maxOrNull() ?: 0L
    }

    /** One row for [dir] on [volume]; the state and summary come from [cached] (the Git page's status cache, by path) or stay "" (not read yet). */
    fun info(dir: File, volume: String, cached: Map<String, GitStatusReader.Status> = emptyMap()): RepoInfo {
        val st = cached[dir.absolutePath]?.takeIf { it.terminal }
        val p = st?.probe
        return RepoInfo(
            name = dir.name, path = dir.absolutePath, volume = volume,
            branch = p?.branch?.takeIf { it.isNotBlank() } ?: branchOf(dir),
            lastSyncMs = p?.fetchedAtMs?.takeIf { it > 0 } ?: lastSyncOf(dir),
            state = st?.state?.name?.lowercase().orEmpty(),
            summary = st?.label().orEmpty().let { if (st?.probe == null) "" else it },
        )
    }

    /** Every repository on every source, one row per clone (a clone reached from two sources counts once, by canonical path), by name. */
    fun list(sources: List<Source>, cached: Map<String, GitStatusReader.Status> = emptyMap()): List<RepoInfo> {
        val seen = HashSet<String>()
        val out = ArrayList<RepoInfo>()
        for (s in sources) {
            for (dir in GitStorage.repos(GitStorage.gitFolder(s.root))) {
                val key = runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath)
                if (seen.add(key)) out += info(dir, s.volume, cached)
            }
        }
        return out.sortedWith(compareBy({ it.name.lowercase() }, { it.path }))
    }

    /** The rows of [name]: the folder's name, or the repository name its origin ends in, case-insensitively. */
    fun find(rows: List<RepoInfo>, name: String): List<RepoInfo> {
        val n = name.trim()
        if (!RepoWorkspace.validName(n)) return emptyList()
        return rows.filter { it.name.equals(n, ignoreCase = true) || LocalRepos.repoNameOf(LocalRepos.originOf(File(it.path))).equals(n, ignoreCase = true) }
    }

    /** The row of the repository folder [path] when it is one of [rows] (exact path); null for any other path (no arbitrary-path probe). */
    fun statusOf(rows: List<RepoInfo>, path: String): RepoInfo? = rows.firstOrNull { it.path == path.trimEnd('/') }

    /** "" = the request is acceptable; else the reason. */
    fun refusal(name: String?): String = if (RepoWorkspace.validName(name)) "" else "not a repository name"
}

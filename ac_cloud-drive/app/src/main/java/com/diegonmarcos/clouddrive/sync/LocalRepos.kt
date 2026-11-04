package com.diegonmarcos.clouddrive.sync

import com.diegonmarcos.cloudlib.gitrepos.*

import java.io.File

/**
 * #1149 Sync ▸ Git ▸ Me ▸ Local Repos Parentless: the local folders that hold a `.git` and whose NAME is not
 * the name of the repository their `origin` points at (a clone made under another name, a folder renamed by hand).
 * #1315 the app discovers through THE ONE INVENTORY (sync/inventory) and renames through the executor's folder commands;
 * [scan] and [parentless] stay as the pure rules the JVM suite exercises, the rename moved to FolderActions / GitSyncCoordinator.
 *
 * Pure Kotlin over [File], like [GitRepoScan]: the JVM suite (LocalReposTest) runs the exact code the page runs
 * against real temporary directories. Reading the origin does not need git: it is one section of `.git/config`.
 *
 * THE COMPARISON IS CASE-INSENSITIVE. GitHub names are case-insensitive, an origin URL typed in another case still
 * reaches the same repository, and Android's shared storage is a case-insensitive volume, so a case-only difference
 * is neither a different repository nor a rename the file system can be trusted to perform.
 */
object LocalRepos {

    /** A folder that holds a repository. [origin] is the URL of remote `origin` ("" = none), [repoName] its basename ("" = none). */
    data class Found(val dir: File, val origin: String, val repoName: String, /** #1317 the name the origin URL literally ends in (an OLD name when [stale]). */ val originName: String = repoName) {
        /** #1317 the origin names the repository by a retired name (GitHub redirects it): the clone is fixable with `remote set-url`. */
        val stale: Boolean get() = originName.isNotBlank() && !originName.equals(repoName, true) && !repoName.equals(originName, true)
    }

    /** A [Found] whose folder name is not [Found.repoName]; [target] is the folder name the rename would give it. */
    data class Parentless(val dir: File, val origin: String, val repoName: String, val originName: String = repoName) {
        val target: File get() = File(dir.parentFile, repoName)
    }

    /**
     * The repository name an origin URL ends in: the last path segment with a trailing slash and a `.git` suffix
     * removed. Handles https, ssh:// and scp-like (`git@host:owner/name.git`) forms and a local path; "" when the URL
     * has no usable last segment.
     */
    fun repoNameOf(url: String): String {
        var u = url.trim().substringBefore('?').substringBefore('#').trimEnd('/')
        if (u.endsWith(".git", ignoreCase = true)) u = u.dropLast(4).trimEnd('/')
        val seg = u.substringAfterLast('/').substringAfterLast(':')
        return if (seg.isBlank() || seg == "." || seg == "..") "" else seg
    }

    /** Whether a folder called [folder] is parentless against a repository called [repoName] (a repo with no name never is). */
    fun isParentless(folder: String, repoName: String): Boolean = repoName.isNotBlank() && !folder.equals(repoName, ignoreCase = true)

    /** `remote "origin"`'s url out of git-config text; "" when the section or its url is absent. */
    fun originFromConfig(config: String): String {
        var inOrigin = false
        for (raw in config.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("[")) {
                inOrigin = Regex("""^\[\s*remote\s+"origin"\s*]""", RegexOption.IGNORE_CASE).containsMatchIn(line)
                continue
            }
            if (inOrigin && line.startsWith("url", ignoreCase = true)) {
                val eq = line.indexOf('=')
                if (eq > 0 && line.substring(0, eq).trim().equals("url", ignoreCase = true)) return line.substring(eq + 1).trim()
            }
        }
        return ""
    }

    /** The git directory of [dir]: its `.git` folder, or the folder a `.git` FILE (`gitdir: ...`: a worktree or submodule) points at. */
    private fun gitDirOf(dir: File): File? {
        val dot = File(dir, ".git")
        if (dot.isDirectory) return dot
        if (!dot.isFile) return null
        val target = runCatching { dot.readText().lineSequence().firstOrNull { it.startsWith("gitdir:") } }.getOrNull()?.removePrefix("gitdir:")?.trim() ?: return null
        val f = File(target)
        return (if (f.isAbsolute) f else File(dir, target)).takeIf { it.isDirectory }
    }

    /** The URL of `origin` of the repository in [dir], read from its git config ("" = none / unreadable). A worktree's config is its common dir's. */
    fun originOf(dir: File): String {
        var git = gitDirOf(dir) ?: return ""
        val common = File(git, "commondir")
        if (!File(git, "config").isFile && common.isFile) {
            val c = runCatching { common.readText().trim() }.getOrDefault("")
            if (c.isNotBlank()) git = File(c).let { if (it.isAbsolute) it else File(git, c) }
        }
        val config = File(git, "config")
        return if (config.isFile) runCatching { originFromConfig(config.readText()) }.getOrDefault("") else ""
    }

    /** #1317 `<name>.incoming` is a copy in flight or the leftover of an interrupted one (ExecCopier / FileCopier): never a clone to list. */
    fun isIncoming(f: File): Boolean = f.name.endsWith(INCOMING)
    const val INCOMING = ".incoming"

    /** #1317 a folder that is a symlink (a farm link into another clone) is not a clone of its own. */
    fun isSymlink(f: File): Boolean = runCatching { java.nio.file.Files.isSymbolicLink(f.toPath()) }.getOrDefault(false)

    /**
     * #1317 the folder's `.git` is its OWN: a directory, or a file whose gitdir holds its own config (a separated git dir).
     * A worktree's gitdir has a `commondir` file: its config is ANOTHER repository's, so it is not read as this folder's.
     */
    fun ownGit(dir: File): Boolean {
        val dot = File(dir, ".git")
        if (dot.isDirectory) return true
        if (!dot.isFile) return false
        val target = runCatching { dot.readText().lineSequence().firstOrNull { it.startsWith("gitdir:") } }.getOrNull()?.removePrefix("gitdir:")?.trim() ?: return false
        val f = File(target).let { if (it.isAbsolute) it else File(dir, target) }
        return f.isDirectory && !File(f, "commondir").exists()
    }

    /**
     * Every direct child of each of [roots] that holds its own `.git`, name order, each CLONE once (#1317: a symlinked folder and a
     * `.git` file pointing outside are skipped; the same clone reached from two roots counts once, by canonical path).
     * [names] folds `renamed_from`: [Found.repoName] is the CURRENT registry name, [Found.originName] what the URL says.
     */
    fun scan(roots: List<File>, names: RegistryNames = RegistryNames.NONE): List<Found> =
        roots.distinctBy { it.absolutePath }
            .flatMap { root -> root.listFiles().orEmpty().filter { it.isDirectory && !isIncoming(it) && !isSymlink(it) && ownGit(it) } }
            .distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }
            .sortedBy { it.name.lowercase() }
            .map { d -> originOf(d).let { found(d, it, names) } }

    /** A [Found] for [dir] whose origin is [origin], names folded through the registry. */
    fun found(dir: File, origin: String, names: RegistryNames = RegistryNames.NONE): Found {
        val raw = repoNameOf(origin)
        return Found(dir, origin, if (raw.isBlank()) "" else names.currentName(raw), raw)
    }

    /** The folders whose name is not their origin's repository name (registry name or GitHub name both count, old names resolve first). */
    fun parentless(found: List<Found>, names: RegistryNames = RegistryNames.NONE): List<Parentless> =
        found.filter { it.repoName.isNotBlank() && !names.carries(it.dir.name, it.repoName) }.map { Parentless(it.dir, it.origin, it.repoName, it.originName) }

    private fun usable(name: String): Boolean = name.isNotBlank() && name != "." && name != ".." && '/' !in name && '\u0000' !in name

    /**
     * Where [path] lives after the folder [old] became [new]: [new] itself, or [path] re-rooted under it. Null when
     * [path] is neither [old] nor inside it. A sibling that merely starts with the same letters is not inside.
     */
    fun repointPath(path: String, old: String, new: String): String? {
        val o = old.trimEnd('/')
        val n = new.trimEnd('/')
        return when {
            path.trimEnd('/') == o -> n
            path.startsWith("$o/") -> n + path.substring(o.length)
            else -> null
        }
    }

    /** #1317 [config] with `remote "origin"`'s url replaced by [url]; null when the section or its url line is absent. Nothing else changes. */
    fun withOrigin(config: String, url: String): String? {
        var inOrigin = false; var done = false
        val out = config.lines().map { raw ->
            val line = raw.trim()
            if (line.startsWith("[")) { inOrigin = Regex("""^\[\s*remote\s+"origin"\s*]""", RegexOption.IGNORE_CASE).containsMatchIn(line); raw }
            else if (inOrigin && !done && line.startsWith("url", ignoreCase = true) && line.substringBefore('=').trim().equals("url", true) && '=' in line) {
                done = true; raw.substringBefore('=') + "= " + url
            } else raw
        }
        return if (done) out.joinToString("\n") else null
    }

    /** #1317 rewrite the origin url of [dir] (its own `.git/config`) in place; true when written. */
    fun setOrigin(dir: File, url: String): Boolean {
        if (!File(dir, ".git").isDirectory) return false
        val c = File(dir, ".git/config")
        val next = runCatching { withOrigin(c.readText(), url) }.getOrNull() ?: return false
        return runCatching { c.writeText(next); true }.getOrDefault(false)
    }
}

package com.diegonmarcos.clouddrive.sync

import java.io.File

/**
 * #1376 the Git page's storage selector: which storage the page lists repositories from.
 *  STORE    Cloud-Store (Termux): the shared store, the page as it always was (GitReposScreen).
 *  EMULATED internal shared storage (/storage/emulated/0).
 *  EXTERNAL the attached USB-C drive (StorageVolumes, #1375).
 * On each storage the repositories are the folders holding a `.git` inside the root's git folder, matched
 * case-insensitively ("git", "GIT", "Git"). Pure java.io.File: GitStorageTest runs it on the JVM.
 */
object GitStorage {
    enum class Kind(val id: String) { STORE("store"), EMULATED("emulated"), EXTERNAL("external");
        companion object { fun of(id: String?): Kind = values().firstOrNull { it.id == id } ?: STORE } }

    /** The root's git folder: an exact "git" first, then any case ("GIT", "Git"), alphabetically; null when absent. */
    fun gitFolder(root: File?): File? {
        val dirs = root?.listFiles()?.filter { it.isDirectory && it.name.equals("git", ignoreCase = true) }.orEmpty()
        return dirs.firstOrNull { it.name == "git" } ?: dirs.minByOrNull { it.name }
    }

    /** The repositories in [gitFolder]: sub-folders with a `.git` (folder, or file for a worktree), by name. */
    fun repos(gitFolder: File?): List<File> =
        gitFolder?.listFiles()?.filter { it.isDirectory && File(it, ".git").exists() }?.sortedBy { it.name.lowercase() }.orEmpty()

    /** What the page shows for a non-store storage. */
    sealed class View {
        object NoDrive : View()
        data class NoGitFolder(val root: String) : View()
        data class Repos(val folder: String, val repos: List<File>) : View()
    }

    fun view(root: File?): View {
        if (root == null) return View.NoDrive
        val folder = gitFolder(root) ?: return View.NoGitFolder(root.absolutePath)
        return View.Repos(folder.absolutePath, repos(folder))
    }

    const val PREFS = "git_storage"
    const val KEY = "selected"
}

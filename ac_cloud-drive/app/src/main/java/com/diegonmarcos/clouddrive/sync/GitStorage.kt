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
        /** #1356 a USB drive is attached but Android did not mount it (opened raw through libaums): git cannot work on it. */
        object RawOnly : View()
        data class NoGitFolder(val root: String) : View()
        data class Repos(val folder: String, val repos: List<File>) : View()
    }

    fun view(root: File?, rawAttached: Boolean = false): View {
        if (root == null) return if (rawAttached) View.RawOnly else View.NoDrive
        val folder = gitFolder(root) ?: return View.NoGitFolder(root.absolutePath)
        return View.Repos(folder.absolutePath, repos(folder))
    }

    /** #1356 one place a clone can land: Cloud-Store, Emulated, or one attached drive ([driveId] its volume id); [root] null = not attached. */
    data class Target(val kind: Kind, val label: String, val root: File?, val driveId: String = "", val raw: Boolean = false)

    /** The clone target picker's choices: Cloud-Store, Emulated, then every attached drive by its label (none attached: one External row, root null). */
    fun targets(store: Target, emulated: Target, drives: List<Target>): List<Target> =
        listOf(store, emulated) + drives.ifEmpty { listOf(Target(Kind.EXTERNAL, "", null)) }

    /** The picker's default: the first choice of the selected storage. */
    fun defaultTarget(targets: List<Target>, selected: Kind): Target = targets.firstOrNull { it.kind == selected } ?: targets.first()

    /** How git may write on a volume: a mounted, writable path (all-files access); mounted but not writable (ask the volume's SAF grant); a raw drive Android did not mount (read-only for git). */
    enum class Access { WRITABLE, NEEDS_GRANT, RAW_READ_ONLY }
    fun access(raw: Boolean, canWrite: Boolean): Access = when { raw -> Access.RAW_READ_ONLY; canWrite -> Access.WRITABLE; else -> Access.NEEDS_GRANT }

    /** Where a clone of [name] goes on [t]. */
    sealed class CloneAt {
        object NoDrive : CloneAt()
        object RawReadOnly : CloneAt()
        /** The root has no git folder: offer to create [proposed]. */
        data class NeedsFolder(val proposed: File) : CloneAt()
        data class Ready(val dir: File) : CloneAt()
    }

    /** Store targets are handled by the store (null folder = the store's own layout); others clone into the root's git folder. */
    fun cloneAt(t: Target, name: String): CloneAt {
        if (t.raw) return CloneAt.RawReadOnly
        val root = t.root ?: return CloneAt.NoDrive
        val folder = gitFolder(root) ?: return CloneAt.NeedsFolder(File(root, "git"))
        return CloneAt.Ready(File(folder, name))
    }

    /** #1356 a path on a mounted shared volume (Emulated, an attached drive): its clones run on the store's transport, not a second path. */
    fun onVolume(path: String): Boolean = path.startsWith("/storage/") && !path.startsWith("/storage/self/")

    const val PREFS = "git_storage"
    const val KEY = "selected"
}

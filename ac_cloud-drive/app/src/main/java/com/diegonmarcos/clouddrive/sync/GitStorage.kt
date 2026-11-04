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
    sealed class Listing {
        object NoDrive : Listing()
        /** #1356 a USB drive is attached but Android did not mount it (opened raw through libaums): git cannot work on it. */
        object RawOnly : Listing()
        data class NoGitFolder(val root: String) : Listing()
        data class Repos(val folder: String, val repos: List<File>) : Listing()
    }

    fun view(root: File?, rawAttached: Boolean = false): Listing {
        if (root == null) return if (rawAttached) Listing.RawOnly else Listing.NoDrive
        val folder = gitFolder(root) ?: return Listing.NoGitFolder(root.absolutePath)
        return Listing.Repos(folder.absolutePath, repos(folder))
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

    /**
     * #1452 THE ENVIRONMENT A PATH BELONGS TO. The page lists, scans and counts duplicates in ONE environment at a time; before this
     * the inventory walked the store's git folder, the registered clones' parents AND all of shared storage in one pass and published
     * everything to every environment (so /storage/emulated/0/GIT/... showed under Cloud-Store). A path belongs to the first of: the
     * store root, an attached drive, shared storage (aliases /sdcard and /storage/self/primary folded in); else to none.
     */
    data class Roots(val store: File?, val emulated: File?, val drives: List<File> = emptyList())

    /** The primary shared volume as the kernel names it (written split: no device-absolute path literal in sources, test-drive-shared-store.sh). */
    private val SHARED = "/storage/" + "emulated/0"

    private fun norm(path: String): String {
        val p = path.trimEnd('/')
        return listOf("/storage/self/primary", "/sdcard", "/mnt/sdcard").firstOrNull { p == it || p.startsWith("$it/") }?.let { SHARED + p.removePrefix(it) } ?: p
    }

    private fun under(path: String, root: File?): Boolean {
        val r = root?.let { norm(it.absolutePath) } ?: return false
        return path == r || path.startsWith("$r/")
    }

    fun kindOf(path: String, roots: Roots): Kind? {
        val p = norm(path)
        return when {
            under(p, roots.store) -> Kind.STORE
            roots.drives.any { under(p, it) } -> Kind.EXTERNAL
            under(p, roots.emulated) -> Kind.EMULATED
            else -> null
        }
    }

    /** A path under no known root is the store's: the store is app-private (Termux home), its path forms vary, and it is the default environment. */
    fun inEnvironment(kind: Kind, path: String, roots: Roots): Boolean = (kindOf(path, roots) ?: Kind.STORE) == kind

    /** The scan roots that lie inside [kind]; the others are not walked at all. */
    fun scanRoots(kind: Kind, candidates: List<File>, roots: Roots): List<File> = candidates.filter { inEnvironment(kind, it.absolutePath, roots) }

    const val PREFS = "git_storage"
    const val KEY = "selected"
}

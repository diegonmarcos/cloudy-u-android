package com.diegonmarcos.clouddrive.sync.inventory

import com.diegonmarcos.clouddrive.Declarations
import com.diegonmarcos.clouddrive.sync.LocalRepos
import com.diegonmarcos.clouddrive.sync.RegistryNames

/**
 * #1315 THE ONE INVENTORY: every folder the discovery pass found, resolved against the registry (renamed_from chains and the
 * `self` entry folded by [RegistryNames]) and classified EXACTLY ONCE, plus one NOT_CLONED row per registry repository no
 * folder holds. Every table, count and filter of the Git page reads this list; nothing else decides what a folder is.
 * Pure, JVM-tested with the rows seen on the phone (RepoInventoryPhoneTest).
 *
 * #1491 ONE list of repo folders: a bare repository (`<repo>.git`, no working tree) is classified like any other repo folder, its
 * [Row.bare] a small attribute; there is no bare class, table or section. Its folder name is the repository name plus `.git`.
 *
 * Classes, first match wins per folder:
 *  DEBRIS      a leftover the copy or the pack writer abandoned (declared patterns, e.g. `*.incoming`)
 *  DUPLICATE   a second clone of a repository another folder already holds (the primary keeps the main-table row)
 *  MISNAMED    the folder does not carry its repository's registry name (Rename fixes it)
 *  STALE_ORIGIN the folder is named right but its origin names a retired name (Fix origin rewrites it)
 *  REGISTERED  a clone of a registry repository, named right, origin current
 *  UNLISTED    a clone whose origin names no registry repository, or that has no origin
 *  NOT_CLONED  a registry repository with no clone anywhere the pass looked
 */
object RepoInventory {

    enum class RepoClass { REGISTERED, STALE_ORIGIN, MISNAMED, DUPLICATE, UNLISTED, DEBRIS, NOT_CLONED }

    data class Row(
        /** Host path of the folder; "" for NOT_CLONED. */
        val path: String,
        /** The folder name, or the registry name for NOT_CLONED. */
        val name: String,
        /** The repository identity (lower-case current GitHub name); "" when none. */
        val identity: String,
        val entry: Declarations.RepoSotEntry?,
        val cls: RepoClass,
        val origin: String = "",
        /** The name the origin URL literally ends in ("" = no origin). */
        val originName: String = "",
        val branch: String? = null,
        val upstream: String? = null,
        val headTimeMs: Long = 0,
        val shallow: Boolean = false,
        /** The origin names a retired name of the repository (true for STALE_ORIGIN, and for a MISNAMED or DUPLICATE row whose origin is old). */
        val staleOrigin: Boolean = false,
        /** DUPLICATE: the folder that keeps the main-table row for this repository. */
        val primaryPath: String? = null,
        /** The number of folders holding this repository (1 = just this one). */
        val copies: Int = 1,
        /** The path is in the managed registry (the scheduler syncs it). */
        val managed: Boolean = false,
        /** #1491 a bare repository (an attribute of the repo, not a class). */
        val bare: Boolean = false,
    ) {
        /** The folder name Rename would give a MISNAMED row (the CURRENT registry name). */
        val registryName: String get() = entry?.name ?: name
        val isClone: Boolean get() = cls == RepoClass.REGISTERED || cls == RepoClass.STALE_ORIGIN || cls == RepoClass.MISNAMED || cls == RepoClass.DUPLICATE || cls == RepoClass.UNLISTED
    }

    data class Inventory(val rows: List<Row>, val readAtMs: Long = 0, val via: String = "", val truncated: Boolean = false, val malformed: Int = 0) {
        fun of(cls: RepoClass): List<Row> = rows.filter { it.cls == cls }
        val counts: Map<RepoClass, Int> get() = rows.groupingBy { it.cls }.eachCount()
        /** The main-table rows: one per registry repository (its primary clone, or NOT_CLONED). */
        val main: List<Row> get() = rows.filter { it.cls == RepoClass.REGISTERED || it.cls == RepoClass.STALE_ORIGIN || it.cls == RepoClass.MISNAMED || it.cls == RepoClass.NOT_CLONED }
        fun byPath(path: String): Row? = rows.firstOrNull { it.path == path }
        /** A registered repository's primary clone row (by lower-case github name), or null when not cloned. */
        fun cloneOf(identity: String): Row? = rows.firstOrNull { it.identity == identity.lowercase() && (it.cls == RepoClass.REGISTERED || it.cls == RepoClass.STALE_ORIGIN || it.cls == RepoClass.MISNAMED) }
    }

    /**
     * Classify [entries] against the registry ([names] over [sot]) and the managed paths. [primaryOf] breaks a tie between
     * several clones of one repository: the managed one, else the one carrying the registry name, else the newest HEAD.
     */
    fun classify(entries: List<InventoryScript.Entry>, names: RegistryNames, sot: Declarations.RepoSotDecl, managedPaths: Set<String>, now: Long = 0L, via: String = "", truncated: Boolean = false, malformed: Int = 0): Inventory {
        val rows = ArrayList<Row>()
        val trees = ArrayList<Pair<InventoryScript.Entry, String>>()
        for (e in entries) {
            when (e.kind) {
                InventoryScript.Kind.DEBRIS -> rows += Row(e.path, e.name, "", null, RepoClass.DEBRIS, managed = e.path in managedPaths)
                InventoryScript.Kind.GITFILE -> Unit
                InventoryScript.Kind.TREE -> {
                    val seg = LocalRepos.repoNameOf(e.origin)
                    val entry = if (seg.isBlank()) null else names.entryOf(seg)
                    if (entry == null) rows += Row(e.path, e.name, if (seg.isBlank()) "" else seg.lowercase(), null, RepoClass.UNLISTED, e.origin, seg, e.branch, e.upstream, e.headTimeMs, e.shallow, managed = e.path in managedPaths, bare = e.bare)
                    else trees += e to entry.github.lowercase()
                }
            }
        }
        // ONE CLONE PER REPOSITORY: the primary keeps the row, every other folder of that identity is a DUPLICATE of it.
        val groups = trees.groupBy({ it.second }, { it.first })
        for ((identity, folders) in groups) {
            val entry = names.entryOf(identity) ?: continue
            val primary = folders.sortedWith(compareBy<InventoryScript.Entry> { it.path !in managedPaths }.thenBy { !names.carries(it.repoName, entry.github) }.thenByDescending { it.headTimeMs }.thenBy { it.path }).first()
            for (e in folders) {
                val seg = LocalRepos.repoNameOf(e.origin)
                val stale = seg.isNotBlank() && names.isOld(seg)
                val misnamed = !names.carries(e.repoName, entry.github)
                val cls = when {
                    e !== primary -> RepoClass.DUPLICATE
                    misnamed -> RepoClass.MISNAMED
                    stale -> RepoClass.STALE_ORIGIN
                    else -> RepoClass.REGISTERED
                }
                rows += Row(e.path, e.name, identity, entry, cls, e.origin, seg, e.branch, e.upstream, e.headTimeMs, e.shallow, stale, if (e !== primary) primary.path else null, folders.size, e.path in managedPaths, e.bare)
            }
        }
        // Every registry repository no folder holds (the index repo `self` included; a pending one cannot be cloned).
        for (r in sot.listed) {
            if (r.pending) continue
            val id = r.github.lowercase()
            if (id in groups) continue
            rows += Row("", r.name, id, r, RepoClass.NOT_CLONED)
        }
        return Inventory(rows, now, via, truncated, malformed)
    }
}

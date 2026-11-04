package com.diegonmarcos.clouddrive.sync

import com.diegonmarcos.cloudlib.gitui.*

import com.diegonmarcos.cloudlib.gitrepos.*


/**
 * One table row's facts. A fact the page could not read (not cloned, not fetched yet, no upstream) is
 * NULL and prints [RepoTable.DASH]: a zero would claim "empty / clean / in sync", which is not known.
 */
data class RepoTableRow(
    val name: String,
    val isPrivate: Boolean?,
    val sizeMainBytes: Long?,
    val sizeTotalBytes: Long?,
    val branches: Int?,
    /** Local uncommitted changes: the changed-file count (0 = clean); null = unknown / not cloned. */
    val dirtyFiles: Int?,
    val ahead: Int?,
    val behind: Int?,
    /** The forge's printed word (GitHub / GitLab / Gitea / Other) from the remote URL's host; null = no remote URL known. */
    val provider: String? = null,
    /** #1195 the clone's bytes on this phone's disk (cached walk); null = not cloned / not measured yet. */
    val sizeDiskBytes: Long? = null,
    /** #1238 the registry's declarative `lfs` flag: true = LFS repository, false = normal, null = the registry does not list it (prints the dash). */
    val lfs: Boolean? = null,
    /** #1238 the leftmost column's kind (RepoStatus.kindOf); null = not computed. */
    val status: RepoStatus.Kind? = null,
    /** #1256 the last commit's time (epoch ms): the local HEAD commit when cloned, else the forge's pushed_at; null = unknown. */
    val lastCommitMs: Long? = null,
)

/** #1138 the table's pure rules: ranking, sorting, how each cell prints and where its facts come from. */
object RepoTable {
    const val DASH = "—"
    /** #1356 the columns that never scroll sideways: the status icon and the name, left of every fact column. */
    val PINNED = setOf(RepoColumn.STATUS, RepoColumn.NAME)
    val DEFAULT_SORT = RepoColumn.NAME
    /** #1288 THE column set and order of every repo table (the main one, Parentless, Unlisted): the one header draws exactly these. */
    val COLUMNS: List<RepoColumn> = RepoColumn.entries.toList()

    /** The sort after a header tap: the same column flips direction, another column starts ascending. */
    fun tap(column: RepoColumn, current: RepoColumn, ascending: Boolean): Pair<RepoColumn, Boolean> =
        if (column == current) column to !ascending else column to true

    private fun key(r: RepoTableRow, c: RepoColumn): Comparable<*>? = when (c) {
        RepoColumn.STATUS -> r.status?.ordinal
        RepoColumn.NAME -> r.name.lowercase()
        RepoColumn.PROVIDER -> r.provider?.lowercase()
        RepoColumn.VISIBILITY -> r.isPrivate?.let { if (it) 1 else 0 }
        RepoColumn.LFS -> r.lfs?.let { if (it) 1 else 0 }
        RepoColumn.SIZE_MAIN -> r.sizeMainBytes
        RepoColumn.SIZE_TOTAL -> r.sizeTotalBytes
        RepoColumn.SIZE_DISK -> r.sizeDiskBytes
        RepoColumn.BRANCHES -> r.branches
        RepoColumn.DIRTY -> r.dirtyFiles
        // Ahead first, then behind: one number that orders by ahead and breaks ties by behind.
        RepoColumn.AHEAD_BEHIND -> if (r.ahead == null || r.behind == null) null else r.ahead.toLong() * 1_000_000L + r.behind
        RepoColumn.LAST_COMMIT -> r.lastCommitMs
    }

    /** #1256 the Last commit cell: relative ("now", "5m", "3h", "2d", "4mo", "1y"); the full timestamp is the status window's. */
    fun age(ms: Long?, now: Long): String {
        if (ms == null || ms <= 0) return DASH
        val s = ((now - ms) / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "now"
            s < 3600 -> "${s / 60}m"
            s < 86_400 -> "${s / 3600}h"
            s < 86_400L * 30 -> "${s / 86_400}d"
            s < 86_400L * 365 -> "${s / (86_400L * 30)}mo"
            else -> "${s / (86_400L * 365)}y"
        }
    }

    /** #1256 which time the Last commit column shows: the clone's own HEAD commit when cloned and read, else the forge's pushed_at. */
    fun lastCommit(cloned: Boolean, localHeadMs: Long?, remotePushedMs: Long?): Long? =
        (if (cloned) localHeadMs?.takeIf { it > 0 } else null) ?: remotePushedMs?.takeIf { it > 0 }

    /** [items] ordered by [column]; unknown values always sink to the bottom whichever the direction; ties break by name. */
    fun <T> sort(items: List<T>, column: RepoColumn, ascending: Boolean, row: (T) -> RepoTableRow): List<T> {
        val known = items.filter { key(row(it), column) != null }
        val unknown = items.filter { key(row(it), column) == null }.sortedBy { row(it).name.lowercase() }
        @Suppress("UNCHECKED_CAST")
        val cmp = compareBy<T> { key(row(it), column) as Comparable<Any> }.let { if (ascending) it else it.reversed() }.thenBy { row(it).name.lowercase() }.thenBy { row(it).isPrivate?.let { p -> if (p) 1 else 0 } ?: -1 }
        return known.sortedWith(cmp) + unknown
    }

    /** Which repository's menu is open after a tap on [tapped]: a second tap on the same repo closes it ("" = none). */
    fun toggle(open: String, tapped: String): String = TapPanel.toggle(open, tapped)

    /** The status line over the table: one pass over the rows, [cloned] says whether a row has a local clone. */
    data class Summary(val total: Int, val cloned: Int, val notCloned: Int, val dirty: Int, val ahead: Int, val behind: Int)

    fun summarize(rows: List<Pair<Boolean, RepoTableRow>>): Summary = Summary(
        total = rows.size,
        cloned = rows.count { it.first },
        notCloned = rows.count { !it.first },
        dirty = rows.count { (it.second.dirtyFiles ?: 0) > 0 },
        ahead = rows.count { (it.second.ahead ?: 0) > 0 },
        behind = rows.count { (it.second.behind ?: 0) > 0 },
    )

    /** #1356 Size total: the forge's `size` of the whole packed history, in KiB (GitHub and Gitea both report KiB) -> bytes. */
    fun totalBytes(totalKb: Long): Long = totalKb * 1024L

    /**
     * #1356 Size main: a depth-1 checkout of the default branch (tip tree plus one commit). The forge gives the tip tree's
     * blob sizes UNCOMPRESSED ([treeBlobBytes], git/trees?recursive=1) while Size total is the COMPRESSED pack of every
     * commit, so the raw sum could read larger than the whole history. A depth-1 pack is a subset of the full pack, so it
     * can never exceed it: Size main is the smaller of the two, always <= Size total.
     */
    fun mainBytes(treeBlobBytes: Long, totalKb: Long): Long = minOf(treeBlobBytes, totalBytes(totalKb))

    fun size(bytes: Long?): String = if (bytes == null) DASH else GitRepoScan.humanBytes(bytes)
    fun count(n: Int?): String = n?.toString() ?: DASH
    fun visibility(isPrivate: Boolean?, pvt: String, pub: String): String = when (isPrivate) { null -> DASH; true -> pvt; false -> pub }
    /** #1238 the LFS | Normal cell: [lfs] is the registry's flag; unknown (not in the registry) prints the dash, never a guess. */
    fun lfs(lfs: Boolean?, lfsWord: String, normalWord: String): String = when (lfs) { null -> DASH; true -> lfsWord; false -> normalWord }
    fun dirty(files: Int?, yes: String, no: String): String = when {
        files == null -> DASH
        files == 0 -> no
        else -> "$yes · $files"
    }
    fun aheadBehind(ahead: Int?, behind: Int?): String = if (ahead == null || behind == null) DASH else "↑$ahead ↓$behind"

    /**
     * The row of one repository from what the page holds: the remote facts (cached REST), the local
     * glance (the git engine's status, no network) and whether the clone exists. [declaredPrivate] is the
     * listing's flag when there is one; the REST answer fills it for a repo no listing named.
     */
    fun rowOf(name: String, declaredPrivate: Boolean?, cloned: Boolean, glance: GitSyncCoordinator.Glance?, remote: RepoRemoteStats.Stats?, provider: String? = null, diskBytes: Long? = null, lfs: Boolean? = null, localHeadMs: Long? = null): RepoTableRow {
        val local = cloned && glance != null && glance.read && !glance.gone && glance.error == null && !glance.large
        val tree = local && glance!!.level != "light"
        return RepoTableRow(
            name = name,
            isPrivate = declaredPrivate ?: remote?.isPrivate,
            sizeTotalBytes = remote?.let { totalBytes(it.sizeTotalKb) },
            sizeMainBytes = remote?.let { mainBytes(it.sizeMainBytes, it.sizeTotalKb) },
            branches = remote?.branches,
            dirtyFiles = if (tree) glance!!.changed + glance.conflicts else null,
            ahead = if (local && glance!!.upstream != null) glance.ahead else null,
            behind = if (local && glance!!.upstream != null) glance.behind else null,
            provider = provider,
            // A size is only ever shown for a clone that exists; a stale cache entry of a removed clone prints the dash.
            sizeDiskBytes = if (cloned) diskBytes else null,
            lfs = lfs,
            status = RepoStatus.kindOf(cloned, glance),
            lastCommitMs = lastCommit(cloned, localHeadMs, remote?.pushedAtMs),
        )
    }
}

/**
 * #1288 the Parentless and Unlisted git folders tables: the SAME columns as the main table ([RepoTable.COLUMNS]), the same
 * header and cells; a fact a bare folder cannot have (Pvt/Pub, LFS, the forge's sizes and branches) is null and prints the dash.
 */
object FolderTable {
    val COLUMNS: List<RepoColumn> get() = RepoTable.COLUMNS

    fun rowOf(t: FolderActions.Target, glance: GitSyncCoordinator.Glance?, provider: String?, lastCommitMs: Long?): RepoTableRow =
        RepoTable.rowOf(t.name, declaredPrivate = null, cloned = true, glance = glance, remote = null, provider = provider, diskBytes = null, lfs = null, localHeadMs = lastCommitMs)
}

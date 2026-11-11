package com.diegonmarcos.clouddrive.sync

import com.diegonmarcos.cloudlib.gitui.*

import com.diegonmarcos.cloudlib.gitrepos.*


/** #1408 the row lives in libs:git-ui; Drive's rows carry its own status kind. */
typealias RepoTableRow = GitTableRow<RepoStatus.Kind>

/** #1408 the table's pure rules are libs:git-ui's [GitTable]; Drive adds how a row is read from its state and the menu toggle. */
object RepoTable {
    const val DASH = GitTable.DASH
    val PINNED get() = GitTable.PINNED
    val DEFAULT_SORT get() = GitTable.DEFAULT_SORT
    val COLUMNS: List<RepoColumn> get() = GitTable.COLUMNS
    fun tap(column: RepoColumn, current: RepoColumn, ascending: Boolean) = GitTable.tap(column, current, ascending)
    fun age(ms: Long?, now: Long) = GitTable.age(ms, now)
    fun lastCommit(cloned: Boolean, localHeadMs: Long?, remotePushedMs: Long?) = GitTable.lastCommit(cloned, localHeadMs, remotePushedMs)
    fun <T> sort(items: List<T>, column: RepoColumn, ascending: Boolean, row: (T) -> RepoTableRow): List<T> = GitTable.sort(items, column, ascending, row)
    /** Which repository's menu is open after a tap on [tapped]: a second tap on the same repo closes it ("" = none). */
    fun toggle(open: String, tapped: String): String = TapPanel.toggle(open, tapped)
    fun Summary(total: Int, cloned: Int, notCloned: Int, dirty: Int, ahead: Int, behind: Int) = GitTable.Summary(total, cloned, notCloned, dirty, ahead, behind)
    fun summarize(rows: List<Pair<Boolean, RepoTableRow>>) = GitTable.summarize(rows)
    fun totalBytes(totalKb: Long) = GitTable.totalBytes(totalKb)
    fun mainBytes(treeBlobBytes: Long, totalKb: Long) = GitTable.mainBytes(treeBlobBytes, totalKb)
    fun size(bytes: Long?) = GitTable.size(bytes)
    fun count(n: Int?) = GitTable.count(n)
    fun visibility(isPrivate: Boolean?, pvt: String, pub: String) = GitTable.visibility(isPrivate, pvt, pub)
    fun lfs(lfs: Boolean?, lfsWord: String, normalWord: String) = GitTable.lfs(lfs, lfsWord, normalWord)
    fun dirty(files: Int?, yes: String, no: String) = GitTable.dirty(files, yes, no)
    fun aheadBehind(ahead: Int?, behind: Int?) = GitTable.aheadBehind(ahead, behind)

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
            // #1645 the light git stats, each from the real read of the clone; null (the dash) when it was not read.
            cloned = cloned,
            stashed = if (local) glance!!.stashed else null,
            detached = if (local) glance!!.detached else null,
            unpushed = if (local && glance!!.upstream != null) glance.ahead else null,
            branch = if (local) glance!!.branch else null,
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

package com.diegonmarcos.clouddrive.sync

import com.diegonmarcos.cloudlib.gitui.*

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.clouddrive.ui.DriveMetrics
import com.diegonmarcos.clouddrive.ui.DriveTags
import com.diegonmarcos.clouddrive.ui.Pill
import com.diegonmarcos.clouddrive.ui.StatusDot
import com.diegonmarcos.cloudlib.gitsync.ManagedRepo
import java.text.DateFormat
import java.util.Date

@Composable
private fun kindText(k: RepoStatus.Kind): String = stringResource(when (k) {
    RepoStatus.Kind.NOT_CLONED -> R.string.repo_kind_not_cloned
    RepoStatus.Kind.READING -> R.string.repo_kind_reading
    RepoStatus.Kind.NOT_CHECKED -> R.string.repo_kind_not_checked
    RepoStatus.Kind.MISSING -> R.string.repo_kind_missing
    RepoStatus.Kind.LARGE -> R.string.repo_kind_large
    RepoStatus.Kind.ERROR -> R.string.repo_kind_error
    RepoStatus.Kind.CONFLICT -> R.string.repo_kind_conflict
    RepoStatus.Kind.DIRTY -> R.string.repo_kind_dirty
    RepoStatus.Kind.DIVERGED -> R.string.repo_kind_diverged
    RepoStatus.Kind.NO_UPSTREAM -> R.string.repo_kind_no_upstream
    RepoStatus.Kind.BEHIND -> R.string.repo_kind_behind
    RepoStatus.Kind.AHEAD -> R.string.repo_kind_ahead
    RepoStatus.Kind.CLEAN -> R.string.repo_kind_clean
    RepoStatus.Kind.BARE -> R.string.repo_kind_bare
})

/**
 * #1238 THE FULL-SCREEN STATUS WINDOW of one repository, opened by a tap on the leftmost status icon of its table row.
 * Everything in it is a fact the app already holds (the clone registry, the status reader's glance, the cached remote
 * stats, the registry's declarative flags); nothing is invented. Sections, top to bottom: the state, the STATUS block
 * (cloned, branch, dirty, ahead/behind, unpushed, last fetch, last error), the metadata table (path, remote URL, last sync,
 * LFS, visibility, sizes) and, LAST, the actions: the Clone repo button that used to sit in the table row.
 */
@Composable
internal fun RepoStatusWindow(
    name: String,
    owner: String,
    row: RepoTableRow,
    repo: ManagedRepo?,
    glance: GitSyncCoordinator.Glance?,
    icons: RepoStatus.Icons,
    cloneUrl: String,
    remote: RepoRemoteStats.Stats?,
    cloning: Boolean,
    fmt: DateFormat,
    onClone: () -> Unit,
    onCheckWorktree: () -> Unit = {},
    onClose: () -> Unit,
    /** #1254 this repository's outcome in the last depth command (ok / skipped: why / failed: why); null = not part of one. */
    depthOutcome: String? = null,
) {
    val kind = RepoStatus.kindOf(repo != null, glance)
    val block = RepoStatus.block(repo != null, glance)
    val unknown = stringResource(R.string.repo_unknown)
    // #1670 what the three old LOCAL | SERVER | SYNC lights said, as words of the ONE metadata group (nothing lost).
    val facts = statusFacts(block).copy(
        workingCopy = RepoStatus.kindWord(icons.local), remoteState = RepoStatus.kindWord(icons.server),
        syncJob = RepoStatus.kindWord(icons.sync) + ": " + syncDetail(icons), large = if (repo != null && glance != null && glance.read) glance.large else null,
    )
    val now = System.currentTimeMillis()
    val metaFields: List<Pair<RepoStatus.MetaField, Pair<String, String>>> = listOf(
        RepoStatus.MetaField.NAME to (stringResource(R.string.repo_meta_k_name) to name),
        RepoStatus.MetaField.OWNER to (stringResource(R.string.repo_meta_k_owner) to owner),
        RepoStatus.MetaField.PATH to (stringResource(R.string.repo_meta_k_path) to (repo?.path ?: RepoTable.DASH)),
        RepoStatus.MetaField.BRANCH to (stringResource(R.string.repo_meta_k_branch) to (block.branch ?: RepoTable.DASH)),
        RepoStatus.MetaField.UPSTREAM to (stringResource(R.string.repo_meta_k_upstream) to (block.upstream ?: RepoTable.DASH)),
        RepoStatus.MetaField.LAST_SYNC to (stringResource(R.string.repo_meta_k_last_sync) to
            if (repo != null && repo.lastSyncEpochSeconds > 0) fmt.format(Date(repo.lastSyncEpochSeconds * 1000)) + " · " + repo.lastSyncSummary else stringResource(R.string.sync_never)),
        RepoStatus.MetaField.LFS to (stringResource(R.string.repo_meta_k_lfs) to repoLfsWord(row.lfs)),
        RepoStatus.MetaField.VISIBILITY to (stringResource(R.string.repo_meta_k_visibility) to repoVisibilityWord(row.isPrivate)),
        RepoStatus.MetaField.PROVIDER to (stringResource(R.string.repo_meta_k_provider) to (row.provider ?: RepoTable.DASH)),
        RepoStatus.MetaField.REMOTE_URL to (stringResource(R.string.repo_meta_k_remote) to (repo?.remoteUrl?.takeIf { it.isNotBlank() } ?: cloneUrl).ifBlank { stringResource(R.string.git_meta_no_remote) }),
        RepoStatus.MetaField.DEFAULT_BRANCH to (stringResource(R.string.repo_meta_k_default_branch) to (remote?.defaultBranch ?: unknown)),
        RepoStatus.MetaField.SIZE to (stringResource(R.string.repo_meta_k_size) to (RepoTable.size(row.sizeMainBytes) + " / " + RepoTable.size(row.sizeTotalBytes))),
        RepoStatus.MetaField.SIZE_DISK to (stringResource(R.string.repo_meta_k_size_disk) to RepoTable.size(row.sizeDiskBytes)),
        RepoStatus.MetaField.BRANCHES to (stringResource(R.string.repo_meta_k_branches) to RepoTable.count(row.branches)),
        RepoStatus.MetaField.LAST_COMMIT to (stringResource(R.string.repo_meta_k_last_commit) to (row.lastCommitMs?.let { fmt.format(Date(it)) + " (" + RepoTable.age(it, now) + ")" } ?: RepoTable.DASH)),
    )
    val words = gitStatusWords()
    val statusFields = gitStatusFields(facts, words) { fmt.format(Date(it)) }.map { (f, v) -> f to (stringResource(gitStatusFieldLabelRes(f)) to v) }
    val merged = RepoStatus.mergeFields(statusFields, metaFields).map { it.second }
    // #1408 the window's layout is libs:git-ui's; Drive resolves its status model, strings and clone/check actions into it.
    GitStatusWindow(
        insets = Modifier.statusBarsPadding().navigationBarsPadding(),
        name = name, headerDot = { StatusDot(RepoStatus.light(kind), name) }, stateText = stringResource(R.string.repo_status_window_state, kindText(kind)),
        sections = fiveSections(row, name), debugLine = RepoStatus.debugLine(repo?.path.orEmpty(), glance),
        facts = facts, fmt = fmt, depthOutcome = depthOutcome,
        meta = metaFields.map { it.second },
        fields = merged,
        cloneAction = when (RepoStatus.cloneAction(repo != null, cloning)) { RepoStatus.CloneAction.CLONE -> GitCloneAction.CLONE; RepoStatus.CloneAction.CLONING -> GitCloneAction.CLONING; RepoStatus.CloneAction.NONE -> GitCloneAction.NONE },
        cloneLabel = stringResource(R.string.sync_clone_into_store), onClone = onClone,
        showCheckWorktree = kind == RepoStatus.Kind.LARGE, onCheckWorktree = onCheckWorktree, onClose = onClose,
    )
}

/** #1670 the window's five light sections, the same five lights as the row (the same row model): title = the column, text = its cell. */
@Composable
private fun fiveSections(row: RepoTableRow, name: String): List<GitStatusSection> {
    val yes = stringResource(R.string.repo_dirty_yes)
    val no = stringResource(R.string.repo_dirty_no)
    val yn: (Boolean?) -> String = { b -> if (b == null) RepoTable.DASH else if (b) yes else no }
    return GitLights.of(row).map { (c, l) ->
        val text = when (c) {
            RepoColumn.CLONED -> yn(row.cloned)
            RepoColumn.AHEAD_BEHIND -> GitTable.aheadBehind(row.ahead, row.behind)
            RepoColumn.DIRTY -> GitTable.dirtyStashed(row.dirtyFiles, row.stashed, yes, no)
            RepoColumn.DETACHED -> yn(row.detached)
            else -> GitTable.count(row.unpushed)
        }
        val t = stringResource(columnLabelRes(c))
        GitStatusSection(t, text) { GitLightDot(c, l, name, yes, no, com.diegonmarcos.clouddrive.ui.DriveTags.STATUS_LIGHT) }
    }
}

/** The old SYNC light's sentence: the last sync / fetch / push / pull job (running, never, ok or failed, with its age and summary). */
@Composable
private fun syncDetail(icons: RepoStatus.Icons): String {
    val now = System.currentTimeMillis()
    val last = icons.lastSync
    return when (icons.sync) {
        RepoStatus.SyncKind.RUNNING -> stringResource(R.string.repo_sync_running)
        RepoStatus.SyncKind.NEVER -> stringResource(R.string.repo_sync_never_ran)
        RepoStatus.SyncKind.OK -> stringResource(R.string.repo_sync_ok_at, RepoStatus.ageWord(now - (last?.epochSeconds ?: 0) * 1000), last?.summary.orEmpty())
        RepoStatus.SyncKind.ERROR -> stringResource(R.string.repo_sync_failed, RepoStatus.ageWord(now - (last?.epochSeconds ?: 0) * 1000), last?.summary.orEmpty())
    }
}

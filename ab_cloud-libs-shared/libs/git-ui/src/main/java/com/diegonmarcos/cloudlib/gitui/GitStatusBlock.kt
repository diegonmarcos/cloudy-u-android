package com.diegonmarcos.cloudlib.gitui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import java.text.DateFormat
import java.util.Date

/**
 * #1408 the facts of the STATUS block, resolved by the host from its status model: a count the page could not read is null and
 * prints the dash. [levelAge] = how long ago the status was read ("5m"), null when it was never read; [errorText] = the last
 * error as a sentence, null = none.
 */
data class GitStatusFacts(
    val cloned: Boolean, val branch: String?, val upstream: String?,
    val dirty: Int?, val modified: Int?, val untracked: Int?, val conflicts: Int?,
    /** "light" (refs only: dirty is unknown) / "full" / null = not read. */
    val level: String?, val levelAge: String?,
    val ahead: Int?, val behind: Int?, val unpushed: Int?, val lastFetchMs: Long?, val errorText: String?,
    /** #1645 stash entries / detached HEAD, real git reads; null = not read. */
    val stashed: Int? = null, val detached: Boolean? = null,
    /**
     * #1670 what the three old LOCAL | SERVER | SYNC lights said that the five lights do not, resolved by the host as words:
     * the working copy's kind, the remote's state (in sync / unreachable / no upstream / never fetched ...), the last sync job
     * (ok / error / running / never, with its age and summary); [large] = the size guard skipped the worktree scan. null = dash.
     */
    val workingCopy: String? = null, val remoteState: String? = null, val syncJob: String? = null, val large: Boolean? = null,
)

/** #1670 every field of the ONE status metadata group, in display order: the #1645 table first, then the older STATUS facts. */
enum class GitStatusField { CLONED, DIVERGENCE, DIRTY, DETACHED, UNPUSHED, BRANCH, UPSTREAM, FILES, CONFLICTS, CHECKED, LAST_FETCH, LAST_ERROR, WORKING_COPY, REMOTE, LAST_SYNC_JOB, LARGE }

fun gitStatusFieldLabelRes(f: GitStatusField): Int = when (f) {
    GitStatusField.CLONED -> R.string.repo_status_tbl_cloned
    GitStatusField.DIVERGENCE -> R.string.repo_status_tbl_divergence
    GitStatusField.DIRTY -> R.string.repo_status_tbl_dirty
    GitStatusField.DETACHED -> R.string.repo_status_tbl_detached
    GitStatusField.UNPUSHED -> R.string.repo_status_tbl_unpushed
    GitStatusField.BRANCH -> R.string.repo_status_tbl_branch
    GitStatusField.UPSTREAM -> R.string.repo_status_f_upstream
    GitStatusField.FILES -> R.string.repo_status_f_files
    GitStatusField.CONFLICTS -> R.string.repo_status_f_conflicts
    GitStatusField.CHECKED -> R.string.repo_status_f_checked
    GitStatusField.LAST_FETCH -> R.string.repo_status_f_fetch
    GitStatusField.LAST_ERROR -> R.string.repo_status_f_error
    GitStatusField.WORKING_COPY -> R.string.repo_status_f_working_copy
    GitStatusField.REMOTE -> R.string.repo_status_f_remote
    GitStatusField.LAST_SYNC_JOB -> R.string.repo_status_f_sync_job
    GitStatusField.LARGE -> R.string.repo_status_f_large
}

/** #1670 the words the merged fields print, in the device language. */
data class GitStatusWords(
    val yes: String, val no: String, val never: String, val none: String, val noUpstream: String,
    val notCheckedLight: String, val unknown: String, val light: String, val full: String,
)

/**
 * #1670 THE MERGED STATUS FIELDS: the #1645 table rows ([gitStatusTableRows], same values) followed by every fact the older STATUS
 * lines printed (upstream, the dirty breakdown, conflicts, the check level and age, last fetch, last error) and what the three old
 * lights said (working copy, remote, last sync job, large repo). Each field once; a fact that is not known prints the dash. Pure.
 */
fun gitStatusFields(b: GitStatusFacts, w: GitStatusWords, date: (Long) -> String): List<Pair<GitStatusField, String>> {
    val dash = GitTable.DASH
    val lead = gitStatusTableRows(b, w.yes, w.no).map { it.second }
    return listOf(
        GitStatusField.CLONED to lead[0], GitStatusField.DIVERGENCE to lead[1], GitStatusField.DIRTY to lead[2],
        GitStatusField.DETACHED to lead[3], GitStatusField.UNPUSHED to lead[4], GitStatusField.BRANCH to lead[5],
        GitStatusField.UPSTREAM to (b.upstream ?: if (b.branch != null) w.noUpstream else dash),
        GitStatusField.FILES to when {
            b.dirty != null -> "${b.dirty} (M ${b.modified ?: 0} · U ${b.untracked ?: 0} · C ${b.conflicts ?: 0})"
            b.level == "light" -> w.notCheckedLight
            else -> w.unknown
        },
        GitStatusField.CONFLICTS to GitTable.count(b.conflicts),
        GitStatusField.CHECKED to (if (b.level != null && b.levelAge != null) (if (b.level == "light") w.light else w.full) + " · " + b.levelAge else dash),
        GitStatusField.LAST_FETCH to (b.lastFetchMs?.let(date) ?: w.never),
        GitStatusField.LAST_ERROR to (b.errorText ?: w.none),
        GitStatusField.WORKING_COPY to (b.workingCopy ?: dash),
        GitStatusField.REMOTE to (b.remoteState ?: dash),
        GitStatusField.LAST_SYNC_JOB to (b.syncJob ?: dash),
        GitStatusField.LARGE to (b.large?.let { if (it) w.yes else w.no } ?: dash),
    )
}

/** #1670 the merged fields' words from this module's strings. */
@Composable
fun gitStatusWords(): GitStatusWords = GitStatusWords(
    yes = stringResource(R.string.repo_dirty_yes), no = stringResource(R.string.repo_dirty_no), never = stringResource(R.string.repo_status_never),
    none = stringResource(R.string.repo_status_no_error), noUpstream = stringResource(R.string.repo_status_no_upstream),
    notCheckedLight = stringResource(R.string.repo_status_f_not_checked_light), unknown = stringResource(R.string.repo_status_f_unknown),
    light = stringResource(R.string.repo_status_f_level_light), full = stringResource(R.string.repo_status_f_level_full),
)

/** #1670 the merged fields as (label, value) rows for [GitStatusTable]. */
@Composable
fun gitStatusFieldRows(b: GitStatusFacts, fmt: DateFormat): List<Pair<String, String>> {
    val w = gitStatusWords()
    return gitStatusFields(b, w) { fmt.format(Date(it)) }.map { (f, v) -> stringResource(gitStatusFieldLabelRes(f)) to v }
}

/** #1645 the STATUS TABLE rows (label res, value) from real facts, in the owner's order. Pure so the JVM can test it. */
fun gitStatusTableRows(b: GitStatusFacts, yes: String, no: String): List<Pair<Int, String>> {
    val dash = GitTable.DASH
    fun yn(v: Boolean?) = if (v == null) dash else if (v) yes else no
    return listOf(
        R.string.repo_status_tbl_cloned to yn(b.cloned),
        R.string.repo_status_tbl_divergence to GitTable.aheadBehind(b.ahead, b.behind).let { if (b.ahead == null || b.behind == null) it else "+${b.ahead}/-${b.behind}" },
        R.string.repo_status_tbl_dirty to GitTable.dirtyStashed(b.dirty, b.stashed, yes, no),
        R.string.repo_status_tbl_detached to yn(b.detached),
        R.string.repo_status_tbl_unpushed to GitTable.count(b.unpushed),
        R.string.repo_status_tbl_branch to (b.branch ?: dash),
    )
}

/** One monospace fact line of a panel. */
@Composable
private fun StatusLine(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

/** The STATUS block at the top of the open repository panel: cloned, branch, dirty, ahead/behind, not pushed, last fetch, last error. */
@Composable
fun GitStatusBlockView(b: GitStatusFacts, fmt: DateFormat) {
    val k = LocalGitUiKit.current
    val dash = GitTable.DASH
    Column(Modifier.fillMaxWidth().padding(top = k.gapWide).testTag(k.tagStatusBlock)) {
        Text(stringResource(R.string.repo_status_title), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        gitStatusTableRows(b, stringResource(R.string.repo_dirty_yes), stringResource(R.string.repo_dirty_no)).forEach { (label, value) ->
            androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth()) {
                Text(stringResource(label), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                Text(value, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
            }
        }
        StatusLine(stringResource(R.string.repo_status_cloned, stringResource(if (b.cloned) R.string.repo_dirty_yes else R.string.repo_dirty_no)))
        StatusLine(stringResource(R.string.repo_status_branch, b.branch ?: dash, b.upstream ?: stringResource(R.string.repo_status_no_upstream)))
        StatusLine(
            if (b.dirty == null) stringResource(if (b.level == "light") R.string.repo_status_dirty_light else R.string.repo_status_dirty_unknown)
            else stringResource(R.string.repo_status_dirty, b.dirty, b.modified ?: 0, b.untracked ?: 0, b.conflicts ?: 0),
        )
        if (b.level != null && b.levelAge != null) StatusLine(stringResource(if (b.level == "light") R.string.repo_status_level_light else R.string.repo_status_level_full, b.levelAge))
        StatusLine(stringResource(R.string.repo_status_ab, b.ahead?.toString() ?: dash, b.behind?.toString() ?: dash))
        StatusLine(stringResource(R.string.repo_status_unpushed, b.unpushed?.toString() ?: dash))
        StatusLine(stringResource(R.string.repo_status_fetch, b.lastFetchMs?.let { fmt.format(Date(it)) } ?: stringResource(R.string.repo_status_never)))
        StatusLine(stringResource(R.string.repo_status_error, b.errorText ?: stringResource(R.string.repo_status_no_error)))
    }
}

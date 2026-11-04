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
)

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

package com.diegonmarcos.clouddrive.sync

import com.diegonmarcos.cloudlib.gitrepos.*

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.diegonmarcos.clouddrive.Declarations
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.clouddrive.ui.DriveMetrics
import com.diegonmarcos.clouddrive.ui.DriveTags
import com.diegonmarcos.clouddrive.ui.IconCatalog
import com.diegonmarcos.clouddrive.ui.Pill
import com.diegonmarcos.clouddrive.ui.StatusDot
import java.text.DateFormat

/** #1254 the status window of a depth command: every repository's outcome, skipped (dirty / ahead / already cloned) and failed first. */
@Composable
internal fun DepthReportWindow(r: BulkDepth.Run, onCancel: () -> Unit, onClose: () -> Unit) {
    FullWindow(stringResource(R.string.repo_depth_report), DriveTags.SYNC_GIT_DEPTH_REPORT, onClose) {
        Text(stringResource(if (r.finished) (if (r.cancelled) R.string.repo_depth_cancelled else R.string.repo_depth_done) else R.string.repo_depth_progress,
            r.actionId, r.done, r.total, r.ok.size, r.skipped.size, r.failed.size), style = MaterialTheme.typography.bodyMedium)
        if (!r.finished) Pill(stringResource(R.string.repo_depth_cancel), onCancel, danger = true)
        LazyColumn(Modifier.fillMaxWidth()) {
            items(r.lines()) { l -> Text(l, Modifier.padding(vertical = DriveMetrics.tight), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
        }
    }
}

/** #1255 UNLISTED GIT FOLDERS: path, remote (or none) and the same three status icons, from the ONE reader's cached statuses. */
@Composable
internal fun UnlistedPanel(
    label: String,
    folders: List<UnlistedRepos.Folder>,
    glances: Map<String, GitSyncCoordinator.Glance>,
    lastCommits: Map<String, Long>,
    run: BulkDepth.Run?,
    coordinator: GitSyncCoordinator,
    onOpenReport: () -> Unit,
) {
    // #1288 the ONE folder table (the main table's columns and header); Archive / Delete per row, Rename when the origin names a repository.
    FolderRepoTable(
        tableId = "unlisted",
        title = label.ifBlank { stringResource(R.string.repo_unlisted_title) },
        hint = "",
        empty = stringResource(R.string.repo_unlisted_none),
        rows = folders.map { FolderActions.Target(it.path, it.origin, RegistryNames.live().currentName(LocalRepos.repoNameOf(it.origin))) },
        glances = glances, lastCommits = lastCommits, run = run,
        kinds = listOf(Declarations.FOLDER_RENAME, Declarations.FOLDER_ARCHIVE, Declarations.FOLDER_DELETE, Declarations.FOLDER_REPOINT),
        coordinator = coordinator,
        subtitle = { it.origin.ifBlank { stringResource(R.string.repo_unlisted_no_remote) }.let { o -> FolderActions.originLabel(o, RegistryNames.live()) } },
        onOpenReport = onOpenReport,
        modifier = Modifier.padding(vertical = DriveMetrics.pad).testTag(DriveTags.SYNC_GIT_UNLISTED),
    )
}

/**
 * #1356 LOGS (ui.sync.git.logs): one full-screen page with the declared tabs. App = the Git activity / jobs log
 * (#1258 ACTIVITY LOGS, formerly opened from More), Repos = per repository its commits (the engine log), its sync history
 * and its CI runs (forge REST, read only when asked). Read-only: nothing here runs a git operation.
 */
@Composable
internal fun GitLogsWindow(decl: Declarations.GitLogsDecl, initialTab: String, coordinator: GitSyncCoordinator, events: List<SyncEvent>, fmt: DateFormat, onClose: () -> Unit) {
    val tabs = decl.tabs.ifEmpty { listOf("repos" to "Repos", "app" to "App") }
    var tab by remember { mutableStateOf(initialTab.takeIf { t -> tabs.any { it.first == t } } ?: tabs.first().first) }
    FullWindow(decl.title, DriveTags.SYNC_GIT_ACTIVITY_WINDOW, onClose) {
        Row(Modifier.fillMaxWidth().testTag(DriveTags.SYNC_GIT_ACTIVITY_WINDOW + "_tabs"), horizontalArrangement = Arrangement.spacedBy(DriveMetrics.gap)) {
            tabs.forEach { (id, label) -> Pill(label, { tab = id }, filled = tab == id, modifier = Modifier.testTag(DriveTags.SYNC_GIT_ACTIVITY_WINDOW + "_tab_" + id)) }
        }
        when (tab) {
            "app" -> Column(verticalArrangement = Arrangement.spacedBy(DriveMetrics.gap)) {
                Text(stringResource(R.string.sync_history), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                ActivityLogsView(events, fmt)
            }
            else -> RepoLogsList(decl, coordinator, events, fmt)
        }
    }
}

/** Owner and name of a forge remote URL (https or scp-like); null when the URL says neither. */
internal fun ownerAndName(url: String): Pair<String, String>? =
    Regex("""[:/]([^/:]+)/([^/]+?)(?:\.git)?/?$""").find(url.trim())?.let { it.groupValues[1] to it.groupValues[2] }

@Composable
private fun RepoLogsList(decl: Declarations.GitLogsDecl, coordinator: GitSyncCoordinator, events: List<SyncEvent>, fmt: DateFormat) {
    val repos by coordinator.repos.collectAsState()
    val details by coordinator.details.collectAsState()
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf("") }
    val ci = remember { mutableStateMapOf<String, List<String>>() }
    if (repos.isEmpty()) { Text(decl.label("no_repos"), style = MaterialTheme.typography.bodySmall); return }
    LazyColumn(Modifier.fillMaxSize().testTag(DriveTags.SYNC_GIT_ACTIVITY_WINDOW + "_repos"), verticalArrangement = Arrangement.spacedBy(DriveMetrics.gap)) {
        items(repos.distinctBy { it.id }.sortedBy { it.name.lowercase() }, key = { it.id }) { repo ->
            val expanded = open == repo.id
            Column(Modifier.fillMaxWidth().clickable {
                open = if (expanded) "" else repo.id
                if (!expanded && details[repo.id] == null) coordinator.loadDetails(repo, Declarations.sync.git.historyMax)
            }.padding(vertical = DriveMetrics.gap)) {
                Text((if (expanded) "▾ " else "▸ ") + repo.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                if (expanded) {
                    val d = details[repo.id]
                    Text(decl.label("commits"), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    when {
                        d == null || d.loading -> Text(decl.label("loading"), style = MaterialTheme.typography.bodySmall)
                        d.error != null -> Text(d.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        d.commits.isEmpty() -> Text(decl.label("empty"), style = MaterialTheme.typography.bodySmall)
                        else -> d.commits.forEach { c -> Text("${c.shortSha} · ${fmt.format(java.util.Date(c.time * 1000))} · ${c.summary}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                    Text(decl.label("history"), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    val mine = events.filter { it.repoId == repo.id || it.repoName == repo.name }.sortedByDescending { it.epochSeconds }.take(Declarations.sync.git.historyMax)
                    if (mine.isEmpty()) Text(decl.label("empty"), style = MaterialTheme.typography.bodySmall)
                    mine.forEach { e -> Text((if (e.ok) "✓ " else "✗ ") + fmt.format(java.util.Date(e.epochSeconds * 1000)) + " · " + e.summary, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    Text(decl.label("ci"), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    val runs = ci[repo.id]
                    val on = ownerAndName(repo.remoteUrl)
                    when {
                        runs != null -> if (runs.isEmpty()) Text(decl.label("empty"), style = MaterialTheme.typography.bodySmall) else runs.forEach { r -> Text(r, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        on != null -> Pill(decl.label("ci_load"), {
                            ci[repo.id] = listOf(decl.label("loading"))
                            scope.launch { ci[repo.id] = coordinator.runFeed(on.first, on.second).getOrElse { listOf(it.message ?: it.toString()) } }
                        })
                        else -> Text(decl.label("empty"), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun FullWindow(title: String, tag: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize().testTag(tag), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = DriveMetrics.sectionInset), verticalArrangement = Arrangement.spacedBy(DriveMetrics.gap)) {
                Row(Modifier.fillMaxWidth().padding(vertical = DriveMetrics.pad), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Pill(stringResource(R.string.repo_status_window_close), onClose)
                }
                content()
            }
        }
    }
}

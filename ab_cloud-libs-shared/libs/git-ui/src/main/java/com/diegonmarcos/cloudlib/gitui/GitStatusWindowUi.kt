package com.diegonmarcos.cloudlib.gitui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.text.DateFormat

/** What the window's last section offers: Clone for a repository with no clone, a progress word while it clones, a note for a clone that exists. */
enum class GitCloneAction { CLONE, CLONING, NONE }

/** One of the window's three sections (LOCAL, SERVER, SYNC): the host's light, a title and a sentence. */
data class GitStatusSection(val title: String, val text: String, val dot: @Composable () -> Unit)

/**
 * #1238 THE FULL-SCREEN STATUS WINDOW of one repository. The layout is this module's; every fact is the host's, already resolved:
 * [stateText] (the kind sentence), [sections], [debugLine] (the exact path read and the reader's outcome), the STATUS block's
 * [facts], the optional [depthOutcome], the metadata [meta] rows, and the last section's actions ([cloneAction], [showCheckWorktree]).
 */
@Composable
fun GitStatusWindow(
    name: String, headerDot: @Composable () -> Unit, stateText: String, sections: List<GitStatusSection>, debugLine: String,
    facts: GitStatusFacts, fmt: DateFormat, depthOutcome: String?, meta: List<Pair<String, String>>,
    cloneAction: GitCloneAction, cloneLabel: String, onClone: () -> Unit, showCheckWorktree: Boolean, onCheckWorktree: () -> Unit, onClose: () -> Unit,
) {
    val k = LocalGitUiKit.current
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize().testTag(k.tagStatusWindow), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = k.sectionInset)) {
                Row(Modifier.fillMaxWidth().padding(vertical = k.pad), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(k.pad)) {
                    headerDot()
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.repo_status_window_title), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    k.pill(GitPill(stringResource(R.string.git_window_close), onClose))
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(k.gapWide)) {
                    Text(stateText, style = MaterialTheme.typography.bodyMedium)
                    Column(verticalArrangement = Arrangement.spacedBy(k.gap)) {
                        sections.forEach { sec ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(k.pad)) {
                                sec.dot()
                                Column(Modifier.weight(1f)) {
                                    Text(sec.title, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(sec.text, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    // Debug line: the exact path read and the reader's outcome, so a "?" can never be silent again.
                    Text(debugLine, Modifier.testTag(k.tagDebugLine), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    GitStatusBlockView(facts, fmt)
                    depthOutcome?.let { Text(stringResource(R.string.repo_depth_outcome, it), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
                    RepoMetaTable(meta)
                    // LAST SECTION: the actions. Clone repo lives here, not in the table row (#1238).
                    Text(stringResource(R.string.repo_status_window_actions), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = k.pad))
                    when (cloneAction) {
                        GitCloneAction.CLONE -> k.pill(GitPill(cloneLabel, onClone, Modifier.testTag(k.tagStatusClone), filled = true))
                        GitCloneAction.CLONING -> Text(stringResource(R.string.git_window_cloning), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        GitCloneAction.NONE -> Text(stringResource(R.string.repo_status_window_cloned_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (showCheckWorktree) k.pill(GitPill(stringResource(R.string.repo_check_worktree), onCheckWorktree, Modifier.testTag(k.tagStatusCheck), filled = true))
                    Spacer(Modifier.padding(bottom = k.listBottom))
                }
            }
        }
    }
}

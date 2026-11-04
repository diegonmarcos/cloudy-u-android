package com.diegonmarcos.cloudlib.gitui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import com.diegonmarcos.cloudlib.gitsync.ConflictSide
import com.diegonmarcos.cloudlib.gitsync.GitStatusSnapshot

/** #1289 which Workflow panel is open: the uncommitted files, the unmerged paths, or the conflict solver. */
enum class GitWorkflowMode { DIRTY, CONFLICTS, SOLVE }

/** One monospace fact line of a panel. */
@Composable
private fun MetaLine(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

/** A row of the host's pills (internal: the sheet tail uses it too). */
@Composable
internal fun PillLine(content: @Composable () -> Unit) {
    val k = LocalGitUiKit.current
    Row(Modifier.fillMaxWidth().padding(top = k.pad), horizontalArrangement = Arrangement.spacedBy(k.pad), verticalAlignment = Alignment.CenterVertically) { content() }
}

/**
 * #1289 Workflow: List Dirty / List Conflicts / the conflict solver, from the one status read ([tree], null = still reading).
 * The solver keeps the owner's per-file [choices] (reported through [onChoice]); [onEdit] opens the file in the host's editor
 * (the choice becomes EDITED); [canSolve] = nothing else is running; [onSolve] receives the plan to confirm ([GitSolveConfirm]).
 */
@Composable
fun GitWorkflowPanel(
    tree: Result<GitStatusSnapshot>?, mode: GitWorkflowMode, choices: Map<String, RepoWorkflow.Choice>,
    onChoice: (String, RepoWorkflow.Choice) -> Unit, onEdit: (String) -> Unit, canSolve: Boolean, onSolve: (RepoWorkflow.Plan) -> Unit,
) {
    val k = LocalGitUiKit.current
    Column(Modifier.padding(top = k.gapWide).testTag(k.tagWorkflow), verticalArrangement = Arrangement.spacedBy(k.gap)) {
        if (tree == null) Text(stringResource(R.string.git_wf_reading), style = MaterialTheme.typography.bodySmall)
        else tree.fold(
            onFailure = { MetaLine(stringResource(R.string.git_wf_unreadable, it.message ?: it.javaClass.simpleName)) },
            onSuccess = { snap ->
                val conflicts = RepoWorkflow.conflicts(snap)
                when (mode) {
                    GitWorkflowMode.DIRTY -> RepoWorkflow.dirty(snap).let { list ->
                        if (list.isEmpty()) Text(stringResource(R.string.repo_wf_clean), style = MaterialTheme.typography.bodySmall)
                        else list.forEach { MetaLine(it.code + " " + it.path) }
                    }
                    GitWorkflowMode.CONFLICTS -> if (conflicts.isEmpty()) Text(stringResource(R.string.repo_wf_no_conflicts), style = MaterialTheme.typography.bodySmall) else conflicts.forEach { MetaLine("UU " + it) }
                    GitWorkflowMode.SOLVE -> {
                        if (conflicts.isEmpty()) Text(stringResource(R.string.repo_wf_no_conflicts), style = MaterialTheme.typography.bodySmall)
                        conflicts.forEach { path ->
                            MetaLine(path)
                            PillLine {
                                k.pill(GitPill(stringResource(R.string.repo_wf_ours), { onChoice(path, RepoWorkflow.Choice.OURS) }, filled = choices[path] == RepoWorkflow.Choice.OURS))
                                k.pill(GitPill(stringResource(R.string.repo_wf_theirs), { onChoice(path, RepoWorkflow.Choice.THEIRS) }, filled = choices[path] == RepoWorkflow.Choice.THEIRS))
                                k.pill(GitPill(stringResource(R.string.repo_wf_editor), { onEdit(path); onChoice(path, RepoWorkflow.Choice.EDITED) }, filled = choices[path] == RepoWorkflow.Choice.EDITED))
                            }
                        }
                        val plan = RepoWorkflow.solvePlan(conflicts, choices, snap.repositoryState)
                        if (conflicts.isNotEmpty()) PillLine {
                            k.pill(GitPill(stringResource(R.string.repo_wf_stage_continue), { onSolve(plan) }, filled = true, enabled = plan.ready && canSolve))
                        }
                        if (plan.missing.isNotEmpty() && conflicts.isNotEmpty()) Text(stringResource(R.string.repo_wf_missing, plan.missing.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
        )
    }
}

/** The confirmation of a solve [plan]: every step listed, and the promise that nothing is pushed. */
@Composable
fun GitSolveConfirm(plan: RepoWorkflow.Plan, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val k = LocalGitUiKit.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.repo_wf_confirm_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(k.gap)) {
                plan.steps.forEach { st ->
                    MetaLine(when (st) {
                        is RepoWorkflow.Step.Resolve -> stringResource(if (st.side == ConflictSide.OURS) R.string.repo_wf_step_ours else R.string.repo_wf_step_theirs, st.path)
                        is RepoWorkflow.Step.Stage -> stringResource(R.string.repo_wf_step_stage, st.path)
                        is RepoWorkflow.Step.Continue -> stringResource(R.string.repo_wf_step_continue, st.what.name.lowercase())
                    })
                }
                Text(stringResource(R.string.repo_wf_never_push), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.repo_wf_stage_continue)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.git_wf_cancel)) } },
    )
}

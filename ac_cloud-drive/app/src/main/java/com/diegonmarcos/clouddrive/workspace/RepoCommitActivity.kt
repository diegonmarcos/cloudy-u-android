package com.diegonmarcos.clouddrive.workspace

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import com.diegonmarcos.clouddrive.sync.GitSyncCoordinator
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.clouddrive.ui.DriveMetrics
import com.diegonmarcos.clouddrive.ui.DriveTheme
import com.diegonmarcos.cloudlib.gitsync.GitEngine
import com.diegonmarcos.cloudlib.gitsync.GitStatusSnapshot
import com.diegonmarcos.cloudlib.gitsync.ManagedRepo
import com.diegonmarcos.superapp.repoworkspace.RepoWorkspace
import com.diegonmarcos.superapp.repoworkspace.RepoWorkspace.ChangedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * #1855 P4a Drive's own Commit screen, reached by the repo-workspace intents COMMIT and PUSH (signature permission in the manifest, so
 * only fleet apps open it). The staged and unstaged files of one clone, a per-file diff, the message field, Commit, then Push, each
 * confirmed here by the user. Git is Drive's one path: staging goes through [GitSyncCoordinator.setStaged], the commit and the push
 * through its jobs ([GitSyncCoordinator.OP_COMMIT_STAGED], [GitSyncCoordinator.OP_PUSH]); this screen holds no git code of its own.
 */
class RepoCommitActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent?.getStringExtra(RepoWorkspace.EXTRA_PATH)?.trim().orEmpty()
        val pushFocus = intent?.action == RepoWorkspace.ACTION_PUSH
        setContent {
            DriveTheme {
                Surface(Modifier.fillMaxSize()) { CommitScreen(path, pushFocus) { finish() } }
            }
        }
    }

    @Composable
    private fun CommitScreen(path: String, pushFocus: Boolean, onClose: () -> Unit) {
        val scope = rememberCoroutineScope()
        var git by remember { mutableStateOf<GitSyncCoordinator?>(null) }
        var repo by remember { mutableStateOf<ManagedRepo?>(null) }
        var snap by remember { mutableStateOf<GitStatusSnapshot?>(null) }
        var rows by remember { mutableStateOf<List<ChangedFile>>(emptyList()) }
        var problem by remember { mutableStateOf("") }
        var message by remember { mutableStateOf("") }
        var picked by remember { mutableStateOf<ChangedFile?>(null) }
        var diff by remember { mutableStateOf("") }
        var confirmPush by remember { mutableStateOf(false) }
        val reload = remember { mutableIntStateOf(0) }

        LaunchedEffect(Unit) {
            val made = withContext(Dispatchers.IO) { runCatching { GitSyncCoordinator(applicationContext, scope) }.getOrNull() }
            git = made
            val found = made?.repos?.value?.firstOrNull { it.path.trimEnd('/') == path.trimEnd('/') }
            repo = found
            if (found == null) problem = getString(if (path.isBlank()) R.string.commit_no_repo else R.string.commit_not_registered)
        }
        val running = git?.running?.collectAsState()?.value.orEmpty()
        val busy = repo?.let { running.containsKey(it.id) } == true
        // Re-read whenever a job of this repository ends (stage, commit, push all move the tree).
        LaunchedEffect(repo, busy, reload.intValue) {
            val r = repo ?: return@LaunchedEffect
            if (busy) return@LaunchedEffect
            withContext(Dispatchers.IO) {
                runCatching { GitEngine(applicationContext, File(r.path)).use { e -> e.status() to RepoChanges.read(e, File(r.path)) } }
            }.onSuccess { (s, c) -> snap = s; rows = c }.onFailure { problem = it.message ?: getString(R.string.commit_read_failed) }
        }
        LaunchedEffect(picked, rows) {
            val r = repo; val p = picked
            diff = if (r == null || p == null) "" else withContext(Dispatchers.IO) {
                runCatching { GitEngine(applicationContext, File(r.path)).use { e -> RepoChanges.diffOf(e, File(r.path), p.relpath, RepoWorkspace.DIFF_DEFAULT_BYTES) } }.getOrDefault("")
            }.ifBlank { getString(R.string.commit_no_diff_text) }
        }
        BackHandler { if (picked != null) picked = null else onClose() }

        val staged = rows.filter { it.staged }
        val unstaged = rows.filter { !it.staged }
        val result = repo?.let { git?.opResults?.collectAsState()?.value?.get(it.id) }
        val ahead = snap?.ahead ?: 0

        Column(Modifier.fillMaxSize().padding(DriveMetrics.padWide), verticalArrangement = Arrangement.spacedBy(DriveMetrics.gapWide)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text((repo?.name ?: File(path).name) + (snap?.branch?.let { "  ($it)" } ?: ""), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onClose) { Text(stringResource(R.string.commit_close)) }
            }
            Text(stringResource(R.string.commit_summary, staged.size, unstaged.size, ahead) + if (busy) "  " + stringResource(R.string.commit_working) else "", style = MaterialTheme.typography.bodySmall)
            if (problem.isNotEmpty()) Text(problem, color = colorResource(R.color.drive_diff_del))
            val p = picked
            if (p != null) {
                Text("${p.status}  ${p.relpath}  +${p.added} -${p.removed}", style = MaterialTheme.typography.labelMedium)
                DiffView(diff, Modifier.weight(1f).fillMaxWidth())
                OutlinedButton(onClick = { picked = null }) { Text(stringResource(R.string.commit_back)) }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(DriveMetrics.pad)) {
                    OutlinedButton(enabled = !busy && unstaged.isNotEmpty() && repo != null, onClick = {
                        scope.launch { git?.setStaged(repo!!, unstaged.map { it.relpath }.distinct(), true); reload.intValue++ }
                    }) { Text(stringResource(R.string.commit_stage_all)) }
                    OutlinedButton(enabled = !busy && staged.isNotEmpty() && repo != null, onClick = {
                        scope.launch { git?.setStaged(repo!!, staged.map { it.relpath }.distinct(), false); reload.intValue++ }
                    }) { Text(stringResource(R.string.commit_unstage_all)) }
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    items(rows, key = { (if (it.staged) "s:" else "w:") + it.relpath }) { f ->
                        Row(Modifier.fillMaxWidth().clickable { picked = f }.padding(vertical = DriveMetrics.tight), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Checkbox(checked = f.staged, enabled = !busy && repo != null, onCheckedChange = { on ->
                                scope.launch { git?.setStaged(repo!!, listOf(f.relpath), on); reload.intValue++ }
                            })
                            Text(f.status, Modifier.width(DriveMetrics.glyphIcon), fontFamily = FontFamily.Monospace, color = colorResource(statusColor(f.status)))
                            Text(f.relpath, Modifier.weight(1f), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                            Text("+${f.added} -${f.removed}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                OutlinedTextField(message, { message = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.commit_message)) }, minLines = 2, maxLines = 4)
                Row(horizontalArrangement = Arrangement.spacedBy(DriveMetrics.pad)) {
                    Button(enabled = !busy && repo != null && staged.isNotEmpty() && message.isNotBlank(), onClick = {
                        git?.runOp(repo!!, GitSyncCoordinator.OP_COMMIT_STAGED, message.trim()); message = ""
                    }) { Text(stringResource(R.string.commit_commit)) }
                    (if (pushFocus) Button(enabled = !busy && repo != null && ahead > 0, onClick = { confirmPush = true }) { Text(stringResource(R.string.commit_push, ahead)) }
                    else OutlinedButton(enabled = !busy && repo != null && ahead > 0, onClick = { confirmPush = true }) { Text(stringResource(R.string.commit_push, ahead)) })
                }
                result?.let { Text((if (it.ok) "" else stringResource(R.string.commit_failed) + " ") + it.summary, style = MaterialTheme.typography.bodySmall, color = colorResource(if (it.ok) R.color.drive_diff_add else R.color.drive_diff_del)) }
            }
        }
        if (confirmPush) AlertDialog(
            onDismissRequest = { confirmPush = false },
            title = { Text(stringResource(R.string.commit_push_title, ahead)) },
            text = { Text(stringResource(R.string.commit_push_text, repo?.name.orEmpty())) },
            confirmButton = { TextButton(onClick = { confirmPush = false; git?.runOp(repo!!, GitSyncCoordinator.OP_PUSH) }) { Text(stringResource(R.string.commit_push_confirm)) } },
            dismissButton = { TextButton(onClick = { confirmPush = false }) { Text(stringResource(R.string.commit_cancel)) } },
        )
    }

    @Composable
    private fun DiffView(text: String, modifier: Modifier) {
        val h = rememberScrollState()
        LazyColumn(modifier.background(colorResource(R.color.drive_diff_bg)).horizontalScroll(h)) {
            items(text.lines()) { line ->
                Text(line.ifEmpty { " " }, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall, color = diffColor(DiffLines.kind(line)), softWrap = false)
            }
        }
    }

    private fun statusColor(s: String) = when (s) {
        RepoWorkspace.ST_ADDED, RepoWorkspace.ST_UNTRACKED -> R.color.drive_diff_add
        RepoWorkspace.ST_DELETED -> R.color.drive_diff_del
        else -> R.color.drive_diff_warn
    }

    @Composable
    private fun diffColor(kind: Char) = colorResource(when (kind) {
        '+' -> R.color.drive_diff_add
        '-' -> R.color.drive_diff_del
        '@' -> R.color.drive_diff_hunk
        'h' -> R.color.drive_diff_meta
        else -> R.color.drive_text
    })
}

/** The class of one unified-diff line (pure, pinned by RepoChangesTest): '+' added, '-' removed, '@' hunk header, 'h' file header, ' ' context. */
object DiffLines {
    fun kind(line: String): Char = when {
        line.startsWith("+++") || line.startsWith("---") || line.startsWith("diff ") || line.startsWith("index ") -> 'h'
        line.startsWith("@@") -> '@'
        line.startsWith("+") -> '+'
        line.startsWith("-") -> '-'
        else -> ' '
    }
}

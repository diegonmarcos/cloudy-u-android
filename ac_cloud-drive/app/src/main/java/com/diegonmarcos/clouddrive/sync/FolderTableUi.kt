package com.diegonmarcos.clouddrive.sync

import com.diegonmarcos.cloudlib.gitui.*

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.diegonmarcos.superapp.uikit.KitStoreDot
import com.diegonmarcos.superapp.uikit.KitStoreQuick
import com.diegonmarcos.superapp.uikit.KitStoreRow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.diegonmarcos.clouddrive.Declarations
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.clouddrive.ui.DriveMetrics
import com.diegonmarcos.clouddrive.ui.DriveTags
import com.diegonmarcos.clouddrive.ui.Hairline
import com.diegonmarcos.clouddrive.ui.Pill
import com.diegonmarcos.clouddrive.ui.SectionHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One pending confirm: the command, its folders, and (Delete) the guard read from each clone. */
internal data class FolderConfirm(val action: Declarations.GitFolderActionDecl, val targets: List<FolderActions.Target>)

/**
 * #1288 THE ONE FOLDER TABLE of the Parentless and Unlisted git folders sections: the main table's header ([RepoTableHeader],
 * [RepoTable.COLUMNS], sortable the same way), status cell ([RepoStatusCell]) and fact cells ([RepoCells]); facts a bare folder
 * has not got print the dash ([FolderTable.rowOf]). Each row carries the declared folder commands ([kinds] filters which);
 * every run goes through [GitSyncCoordinator.runFolderAction], the depth runner. [bulk] = a "Rename all" style pill.
 */
@Composable
internal fun FolderRepoTable(
    tableId: String,
    title: String,
    hint: String,
    empty: String,
    rows: List<FolderActions.Target>?,
    glances: Map<String, GitSyncCoordinator.Glance>,
    lastCommits: Map<String, Long>,
    run: BulkDepth.Run?,
    kinds: List<String>,
    coordinator: GitSyncCoordinator,
    subtitle: @Composable (FolderActions.Target) -> String,
    bulk: List<Pair<String, String>> = emptyList(),
    repoint: (java.io.File, java.io.File) -> String = { _, _ -> "" },
    onOpenReport: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val scroll = rememberScrollState()
    var sort by remember { mutableStateOf(RepoTable.DEFAULT_SORT to true) }
    var confirm by remember { mutableStateOf<FolderConfirm?>(null) }
    var open by remember { mutableStateOf("") }
    val actions = kinds.mapNotNull { Declarations.sync.git.folderAction(it) }
    val busy = run?.finished == false
    val list = rows

    Column(modifier.fillMaxWidth()) {
        SectionHeader(title, count = list?.size)
        if (hint.isNotBlank()) Text(hint, Modifier.padding(horizontal = DriveMetrics.sectionInset), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (list != null && list.isEmpty()) Text(empty, Modifier.padding(horizontal = DriveMetrics.sectionInset, vertical = DriveMetrics.gap), style = MaterialTheme.typography.bodySmall)
        if (!list.isNullOrEmpty()) {
            bulk.forEach { (kind, label) ->
                Declarations.sync.git.folderAction(kind)?.let { a ->
                    // #1317 Fix all origins lists only the clones whose origin names a retired name.
                    val targets = if (kind == Declarations.FOLDER_REPOINT) list.filter { FolderActions.fixedOrigin(it.origin, RegistryNames.live()) != null } else list
                    if (targets.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(horizontal = DriveMetrics.sectionInset, vertical = DriveMetrics.gap), horizontalArrangement = Arrangement.End) {
                        Pill(label, { confirm = FolderConfirm(a, targets) }, filled = true, enabled = !busy, modifier = Modifier.testTag(DriveTags.PARENTLESS_ALL + "_" + tableId + (if (kind == Declarations.FOLDER_REPOINT) "_fix" else "")))
                    }
                }
            }
            RepoTableHeader(sort.first, sort.second, scroll, tag = DriveTags.folderTableHeader(tableId)) { c -> sort = RepoTable.tap(c, sort.first, sort.second) }
            val cells = list.associate { t ->
                t.path to FolderTable.rowOf(t, glances[UnlistedRepos.statusKey(t.path)], t.origin.takeIf { it.isNotBlank() }?.let { RepoProvider.labelOf(it, Declarations.providers) }, lastCommits[LastCommits.keyOf(t.path)])
            }
            val words = remember(ctx) { repoMessageWords(ctx) }
            val bareWord = stringResource(R.string.repo_kind_bare)
            RepoTable.sort(list, sort.first, sort.second) { cells.getValue(it.path) }.forEach { t ->
                val g = glances[UnlistedRepos.statusKey(t.path)]
                // #1452 ONE LINE PER FOLDER: the last command's outcome (it used to wrap in a report under the table) is the Status Message cell; the commands are the last cell.
                val outcome = run?.takeIf { it.kind in Declarations.FOLDER_KINDS }?.outcomeOf(t.name)
                // #1452 a bare repository (the Bare section): its status is "bare", the Status Message adds what the table says about it
                val bare = tableId == "bare"
                val say = RepoMessage.ofFolder(outcome, g.takeUnless { bare }, (if (bare) bareWord + " · " else "") + subtitle(t), words)
                val row = cells.getValue(t.path).copy(message = say.text, messageProblem = say.problem, status = if (bare) RepoStatus.Kind.BARE else cells.getValue(t.path).status)
                val toggle = { open = if (open == t.path) "" else t.path }
                RepoTableLine(scroll, Modifier.testTag(DriveTags.parentlessRow(tableId + "_" + t.name)), onClick = toggle) {
                    if (bare) com.diegonmarcos.cloudlib.gitui.RepoStatusCell(toggle, "", dots = {
                        KitStoreDot("\u25EF", KitStoreRow.MISSING, Modifier.testTag(DriveTags.STATUS_LIGHT).semantics { contentDescription = t.name + " " + bareWord })
                    }) else RepoStatusCell(RepoStatus.icons(true, g, false, emptyList(), ""), t.name, toggle)
                    RepoNameCell(t.name)
                    RepoCells(row)
                    // the Store's quick verb (a small filled box) for each command
                    Row(Modifier.widthIn(min = DriveMetrics.tap), horizontalArrangement = Arrangement.spacedBy(DriveMetrics.gap, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                        actions.filter { a -> a.kind != Declarations.FOLDER_REPOINT || FolderActions.fixedOrigin(t.origin, RegistryNames.live()) != null }.forEach { a ->
                            KitStoreQuick(a.label, if (a.kind == Declarations.FOLDER_DELETE) KitStoreRow.BAD else KitStoreRow.BLUE_FILL,
                                Modifier.alpha(if (busy) 0.4f else 1f).testTag(DriveTags.folderAction(a.kind, t.name)), onClick = if (busy) null else ({ confirm = FolderConfirm(a, listOf(t)) }))
                        }
                    }
                }
                if (open == t.path) Text(
                    t.path + "\n" + t.origin.ifBlank { stringResource(R.string.repo_unlisted_no_remote) }, Modifier.padding(horizontal = DriveMetrics.sectionInset),
                    style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Hairline()
            }
        }
        // The outcome of the last folder command (the full list is the depth report; every line is in Activity Logs too).
        if (run != null && run.kind in Declarations.FOLDER_KINDS && list != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = DriveMetrics.sectionInset), horizontalArrangement = Arrangement.End) { Pill(stringResource(R.string.repo_depth_report), onOpenReport) }
        }
    }

    confirm?.let { c -> FolderConfirmDialog(c, coordinator, repoint, onDone = { confirm = null }) }
}

@Composable
internal fun FolderConfirmDialog(c: FolderConfirm, coordinator: GitSyncCoordinator, repoint: (java.io.File, java.io.File) -> String, onDone: () -> Unit) {
    val isDelete = c.action.kind == Declarations.FOLDER_DELETE
    // Delete reads each clone first: uncommitted changes and unpushed commits decide the strict (typed) confirm.
    var guards by remember(c) { mutableStateOf<Map<String, FolderActions.Guard>?>(if (isDelete) null else emptyMap()) }
    var typed by remember(c) { mutableStateOf("") }
    if (isDelete) LaunchedEffect(c) { guards = withContext(Dispatchers.IO) { c.targets.associate { it.path to coordinator.deleteGuard(it.path) } } }
    val strict = guards?.filterValues { it.strict }.orEmpty()
    // The typed confirm is per folder: one strict folder at a time (the per-row chip), so the typed name is unambiguous.
    val strictName = strict.keys.singleOrNull()?.let { java.io.File(it).name }
    val canGo = guards != null && (strict.isEmpty() || (strictName != null && FolderActions.typedOk(typed, strictName)))
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(stringResource(R.string.folder_confirm_title, c.action.label, c.targets.size)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(DriveMetrics.gap)) {
                Text(stringResource(when (c.action.kind) {
                    Declarations.FOLDER_RENAME -> R.string.folder_confirm_rename
                    Declarations.FOLDER_REPOINT -> R.string.folder_confirm_repoint
                    Declarations.FOLDER_ARCHIVE -> R.string.folder_confirm_archive
                    else -> R.string.folder_confirm_delete
                }, Declarations.sync.git.archiveRoot))
                c.targets.take(PREVIEW).forEach { t ->
                    Text(t.name + when (c.action.kind) { Declarations.FOLDER_RENAME -> " → " + t.repoName; Declarations.FOLDER_REPOINT -> " → " + (FolderActions.fixedOrigin(t.origin, RegistryNames.live()) ?: t.origin); else -> "" }, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
                    guards?.get(t.path)?.takeIf { it.strict }?.let { g ->
                        g.reasons().forEach { Text("  ! $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
                        g.changed.take(PREVIEW).forEach { Text("    $it", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall) }
                    }
                }
                if (c.targets.size > PREVIEW) Text(stringResource(R.string.parentless_confirm_more, c.targets.size - PREVIEW), style = MaterialTheme.typography.labelSmall)
                if (isDelete && guards == null) Text(stringResource(R.string.folder_delete_reading), style = MaterialTheme.typography.labelSmall)
                if (strictName != null) OutlinedTextField(typed, { typed = it }, Modifier.fillMaxWidth().testTag(DriveTags.FOLDER_DELETE_TYPED),
                    label = { Text(stringResource(R.string.folder_delete_type, strictName)) }, singleLine = true)
                if (strict.size > 1) Text(stringResource(R.string.folder_delete_one_at_a_time), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                coordinator.runFolderAction(c.action, c.targets, typed = if (strictName != null && canGo) strict.keys else emptySet(), repoint = repoint)
                onDone()
            }, enabled = canGo, modifier = Modifier.testTag(DriveTags.FOLDER_CONFIRM)) { Text(c.action.label) }
        },
        dismissButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.chrome_cancel)) } },
    )
}

private const val PREVIEW = 8

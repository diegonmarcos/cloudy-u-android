package com.diegonmarcos.clouddrive.sync

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.diegonmarcos.clouddrive.Declarations
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.clouddrive.SharedStore
import com.diegonmarcos.cloudlib.gitcore.CloneOptions
import com.diegonmarcos.cloudlib.gitcore.GitStep
import com.diegonmarcos.cloudlib.gitcore.SyncOptions
import com.diegonmarcos.cloudlib.gitsync.ManagedRepo
import com.diegonmarcos.cloudlib.gitui.*
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * #1934 THE UNIFIED REPO CONTROL SHEET of one repository, hosted: libs:git-ui draws it from the declaration (ui.git_control_sheet);
 * this resolves the REAL state into the fields the declaration names (status, metadata) and dispatches each control to the git engine.
 * A control whose `action` is one of the earlier menu's ids goes to [legacy] (the page's own `act`); the rest are handled here. Sync Now and
 * Clone run the option model's steps: the same argv the command preview prints.
 */
@Composable
internal fun ControlSheetBody(
    page: Declarations.GitPageDecl, repo: ManagedRepo?, name: String, owner: String, row: RepoTableRow, isPrivate: Boolean?,
    glance: GitSyncCoordinator.Glance?, icons: RepoStatus.Icons, remote: RepoRemoteStats.Stats?, fmt: DateFormat, cloneUrl: String, cloning: Boolean,
    coordinator: GitSyncCoordinator, busy: Boolean, output: String?, active: Set<String>, readOnly: Boolean,
    /** #608 an operation that cannot run now (a read-only remote refuses push and force push); its control is drawn disabled. */
    blocked: (Declarations.GitOpDecl) -> Boolean = { false },
    legacy: (String) -> Unit, onClone: () -> Unit, onClose: () -> Unit,
    /** A tab was selected: the page scrolls the sheet back to its top. */
    onTabSelected: () -> Unit = {},
    /** #1934 the pinned top (header + tab row), the scrolling body, or both; [sheetState] is shared between the two parts when the page splits them. */
    part: GitSheetPart = GitSheetPart.ALL, sheetState: GitSheetState? = null,
) {
    val ctx = LocalContext.current
    val lang = java.util.Locale.getDefault().language
    val sheet = remember(lang) { GitSheet.parse(Declarations.controlSheet, lang) }
    if (sheet.tabs.isEmpty()) return
    val state = sheetState ?: rememberGitSheetState(sheet, repo?.id ?: name)
    val cloned = repo != null
    val kind = RepoStatus.kindOf(cloned, glance)
    val block = RepoStatus.block(cloned, glance)
    val unknown = stringResource(R.string.repo_unknown)
    val facts = statusFacts(block).copy(
        workingCopy = RepoStatus.kindWord(icons.local), remoteState = RepoStatus.kindWord(icons.server),
        syncJob = RepoStatus.kindWord(icons.sync), large = if (repo != null && glance != null && glance.read) glance.large else null,
    )
    val dash = RepoTable.DASH
    val now = System.currentTimeMillis()
    val fields = gitStatusFields(facts, gitStatusWords()) { fmt.format(Date(it)) }.toMap()
    fun f(x: GitStatusField) = fields[x] ?: dash
    val remoteUrl = (repo?.remoteUrl?.takeIf { it.isNotBlank() } ?: cloneUrl).ifBlank { stringResource(R.string.git_meta_no_remote) }
    val visibility = repoVisibilityWord(isPrivate)
    val sizeWord = RepoTable.size(row.sizeMainBytes) + " / " + RepoTable.size(row.sizeTotalBytes)
    val autoSync = repo?.autoSync == true
    val minutes = (repo?.syncIntervalMinutes?.takeIf { it > 0 } ?: page.repoMenu.autoSync.defaultMinutes).toInt()
    val values = mapOf(
        "state" to stringResource(R.string.repo_status_window_state, kindText(kind)), "cloned" to f(GitStatusField.CLONED), "divergence" to f(GitStatusField.DIVERGENCE),
        "dirty" to f(GitStatusField.DIRTY), "detached" to f(GitStatusField.DETACHED), "unpushed" to f(GitStatusField.UNPUSHED), "branch" to f(GitStatusField.BRANCH),
        "files" to f(GitStatusField.FILES), "conflicts" to f(GitStatusField.CONFLICTS), "checked" to f(GitStatusField.CHECKED), "last_fetch" to f(GitStatusField.LAST_FETCH),
        "last_error" to f(GitStatusField.LAST_ERROR), "working_copy" to f(GitStatusField.WORKING_COPY), "remote_state" to f(GitStatusField.REMOTE),
        "last_sync_job" to f(GitStatusField.LAST_SYNC_JOB), "large" to f(GitStatusField.LARGE), "message" to kindText(kind),
        "debug_line" to RepoStatus.debugLine(repo?.path.orEmpty(), glance), "depth_outcome" to dash, "readonly" to if (readOnly) stringResource(R.string.git_readonly_blocked) else dash,
        "name" to name, "owner" to owner, "remote_url" to remoteUrl, "local_path" to (repo?.path ?: stringResource(R.string.repo_kind_not_cloned)),
        "visibility" to visibility, "provider" to (row.provider ?: dash), "lfs" to repoLfsWord(row.lfs), "default_branch" to (remote?.defaultBranch ?: unknown),
        "upstream" to (block.upstream ?: dash), "clone_profile" to dash,
        "last_commit" to (row.lastCommitMs?.let { fmt.format(Date(it)) + " (" + RepoTable.age(it, now) + ")" } ?: dash),
        "remote_size" to sizeWord, "size_disk" to RepoTable.size(row.sizeDiskBytes), "branches_tags" to RepoTable.count(row.branches),
        "auth_autosync" to ((repo?.authKind ?: dash) + " · " + if (autoSync) "$minutes min" else "off"),
        "autosync_state" to if (autoSync) "ON · $minutes min" else "OFF",
        "last_sync" to (if (repo != null && repo.lastSyncEpochSeconds > 0) fmt.format(Date(repo.lastSyncEpochSeconds * 1000)) + " · " + repo.lastSyncSummary else stringResource(R.string.sync_never)),
        "summary" to "$owner · ${block.branch ?: dash} · ${RepoTable.size(row.sizeMainBytes)}", "clone_state" to (if (cloned) "Cloned" else "Not Cloned"),
        "not_cloned_banner" to dash,
    )
    val url = cloneUrl.ifBlank { page.cloneUrl(name, Declarations.REMOTE_HTTPS) }
    val host = GitSheetHost(
        cloned = cloned, values = values, active = active, busy = busy || cloning, autoSyncOn = autoSync, autoSyncMinutes = minutes, output = output,
        cloneUrl = url, repoName = name, storeRoot = SharedStore.gitRoot().absolutePath, workspaceRoot = File(SharedStore.root(), "workspace").absolutePath,
        mirrorRoot = File(SharedStore.gitRoot(), page.bareRoot).absolutePath, readOnly = readOnly,
        unavailable = sheet.controls(false).mapNotNull { c -> page.op(c.action)?.takeIf { blocked(it) }?.let { c.id to stringResource(R.string.git_readonly_blocked) } }.toMap(),
    )
    fun clip(text: String) = SheetEffects.copy(ctx, name, text)
    var renameAsk by androidx.compose.runtime.remember(name) { androidx.compose.runtime.mutableStateOf<String?>(null) }
    val actions = object : GitSheetActions {
        override fun action(c: SheetControl) {
            when {
                c.action == "close" -> onClose()
                c.action == "refresh" -> coordinator.refresh(true)
                c.action == "fetch_status" -> repo?.let { coordinator.fetchStatus(it) }
                c.action == "check_light" -> repo?.let { coordinator.checkRepos(listOf(it), false) }
                c.action == "check_full" -> repo?.let { coordinator.checkRepos(listOf(it), true) }
                c.action == "check_worktree" -> repo?.let { coordinator.checkWorktree(it) }
                c.action == "copy_url" -> clip(url)
                c.action == "clone_now" -> onClone()
                c.action == "test_auth" -> repo?.let { coordinator.runPlan(it, "test_auth", listOf(GitStep(listOf("git", "fetch", "--dry-run")))) }
                c.action.startsWith("remote_mode:") -> repo?.let { rp -> page.remoteMode(c.action.removePrefix("remote_mode:"))?.let { coordinator.setRemote(rp, it, page.owner, name) } }
                c.action == "rename_remote" -> renameAsk = name
                c.action == "archive_remote" -> coordinator.hostingCall(repo, owner, name, "archive")
                c.action == "delete_remote" -> coordinator.hostingCall(repo, owner, name, "delete_remote")
                else -> legacy(c.action)
            }
        }
        override fun runSync(o: SyncOptions) { repo?.let { coordinator.runPlan(it, "sync", o.steps()) } }
        override fun runClone(o: CloneOptions) {
            val into = when (o.dest) { "workspace" -> File(SharedStore.root(), "workspace"); "mirror" -> File(SharedStore.gitRoot(), page.bareRoot); else -> null }
            coordinator.cloneOptions(GitSyncCoordinator.CloneTarget(name, url, GitSyncCoordinator.AUTH_HTTPS, "", ""), o, into)
        }
        override fun copy(text: String) = clip(text)
        override fun setAutoSync(on: Boolean, minutes: Int) { repo?.let { coordinator.setAutoSync(it, on, minutes.toLong()) } }
    }
    GitControlSheetView(sheet, state, host, actions, Modifier, onTabSelected = { onTabSelected() }, part = part)
    // #1934 Rename Remote: the new name is typed here, then the same confirm as the other danger-zone actions; the request is HostingApi.rename (mock-tested).
    renameAsk?.let { old ->
        var typed by androidx.compose.runtime.remember(old) { androidx.compose.runtime.mutableStateOf(old) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { renameAsk = null },
            title = { androidx.compose.material3.Text(stringResource(R.string.git_rename_remote_title, owner, old)) },
            text = { androidx.compose.material3.OutlinedTextField(typed, { typed = it }, singleLine = true, modifier = Modifier.testTag("git_sheet_rename_remote_name")) },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { coordinator.hostingCall(repo, owner, name, "rename", typed); renameAsk = null }, enabled = typed.isNotBlank() && typed.trim() != old) { androidx.compose.material3.Text(stringResource(R.string.git_rename_remote_confirm)) } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { renameAsk = null }) { androidx.compose.material3.Text(stringResource(R.string.chrome_cancel)) } },
        )
    }
}

/**
 * #1934 THE ALL-REPOS SHEET: the same five tabs applied to the selection ([targets]: the filtered clones). Overview aggregates the status [counts]; Sync and Clone
 * run the option model on every target (per-repo progress and result under Workflow, [results]); a bulk control that existed on the top bar is dispatched to that
 * item through its `legacy` id; every destructive control confirms first, with the repositories listed.
 */
@Composable
internal fun BulkControlSheet(
    page: Declarations.GitPageDecl, targets: List<String>, counts: Map<String, Int>, results: List<Pair<String, String>>, output: String?,
    onTop: (Declarations.GitTopItemDecl) -> Unit, onSync: (SyncOptions) -> Unit, onClone: (CloneOptions) -> Unit,
    part: GitSheetPart = GitSheetPart.ALL, sheetState: GitSheetState? = null,
) {
    val lang = java.util.Locale.getDefault().language
    val sheet = remember(lang) { GitSheet.parse(Declarations.controlSheet, lang) }
    if (sheet.bulkTabs.isEmpty()) return
    val state = sheetState ?: rememberGitSheetState(sheet, "bulk")
    val values = counts.mapKeys { "count:" + it.key }.mapValues { it.value.toString() }
    val host = GitSheetHost(cloned = true, values = values, output = output, targets = targets, bulk = true, results = results)
    val actions = object : GitSheetActions {
        override fun action(c: SheetControl) {
            val ref = c.legacy.firstOrNull { it.startsWith("bulk_top:") }?.removePrefix("bulk_top:") ?: return
            val (g, i) = ref.split('.', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            page.topBar.groups.firstOrNull { it.id == g }?.items?.firstOrNull { it.id == i }?.let(onTop)
        }
        override fun runSync(o: SyncOptions) = onSync(o)
        override fun runClone(o: CloneOptions) = onClone(o)
        override fun copy(text: String) {}
        override fun setAutoSync(on: Boolean, minutes: Int) {}
    }
    GitControlSheetView(sheet, state, host, actions, Modifier, part = part)
}

/** #1934 the effects of the controls that touch the device, pure enough to be executed by ControlEffectsTest. */
internal object SheetEffects {
    /** Copy URL / Copy command: [text] becomes the primary clip. */
    fun copy(ctx: Context, label: String, text: String) { (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(ClipData.newPlainText(label, text)) }

    /** Open on Web: the repository's page on the platform. */
    fun webUrl(page: Declarations.GitPageDecl, name: String): String = page.webUrl(name)

    /** Folder: reveal the clone's folder in Drive Files (the shell's openPath behind [com.diegonmarcos.clouddrive.DriveActions.openInFiles]). */
    fun openFolder(actions: com.diegonmarcos.clouddrive.DriveActions, path: String) = actions.openInFiles(path)
}

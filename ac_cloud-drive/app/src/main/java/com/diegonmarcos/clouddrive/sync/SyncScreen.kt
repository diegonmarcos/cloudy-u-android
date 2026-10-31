package com.diegonmarcos.clouddrive.sync

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.diegonmarcos.clouddrive.Declarations
import com.diegonmarcos.clouddrive.DriveActions
import com.diegonmarcos.clouddrive.DrivePrefs
import com.diegonmarcos.clouddrive.GitSyncWorker
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.superapp.navbartop2.NavBarTopSecondRow
import com.diegonmarcos.clouddrive.ui.DriveMetrics
import com.diegonmarcos.clouddrive.ui.DriveTags
import com.diegonmarcos.clouddrive.ui.EmptyState
import com.diegonmarcos.clouddrive.ui.IconCatalog
import com.diegonmarcos.clouddrive.ui.IslandAction
import com.diegonmarcos.clouddrive.StoreReady
import com.diegonmarcos.cloudlib.sharedstore.StoreAccess
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Terminal
import com.diegonmarcos.clouddrive.ui.Pill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * #1149 the former Sync tab is three bottom-nav pages: Git (two sub-pages, Me and Cloud, drawn by libs:bottomnav's
 * PageTabs over ui.sections[git].pages), Fsync and Mounts (sync/FsyncPage.kt and sync/MountsPage.kt, #1173). This file routes the Git page,
 * it does not draw a card; each page keeps the island with the terminal action it always had.
 *
 * GitPageScreen's dispatch on a page id is the ONE place Kotlin names them; test-drive-shell.sh diffs it against the
 * declaration in both directions. [page] is a DECLARED page id another screen asked for (an Apps tile's route); it is
 * applied once and [onPageConsumed] clears the request.
 */
@Composable
fun GitPageScreen(
    git: GitSyncCoordinator,
    prefs: DrivePrefs,
    actions: DriveActions,
    page: String? = null,
    onPageConsumed: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val pages = Declarations.navPages(Declarations.SECTION_GIT)
    var current by rememberSaveable { mutableStateOf(pages.firstOrNull()?.id ?: "") }
    LaunchedEffect(page) {
        if (page != null && pages.any { it.id == page }) current = page
        if (page != null) onPageConsumed()
    }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val nextRun = rememberGitNextRun()
    fun say(msg: String) { scope.launch { snackbar.showSnackbar(msg) } }

    // #1376 the storage selector (the strip the Explorer draws its tabs with): Cloud-Store keeps the page as it was;
    // Emulated / External list the repositories in that root's git folder (GitStoragePage.kt).
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var storage by remember { mutableStateOf(gitStorageSelected(ctx)) }
    val pagesStorage = gitStoragePages()
    Column(modifier.fillMaxSize()) {
        // #1356 no title row: the Git page opens on its sub-page strip (ui.sections[git].title_row = false);
        // the terminal shortcut it carried is the Termux tile of Home ▸ Apps (data/drive-apps.json).
        // #868 the strip is libs:bottomnav's PageTabs over ui.sections[git].pages.
        NavBarTopSecondRow(pages = pagesStorage, selectedId = storage.id, onSelect = { storage = GitStorage.Kind.of(it.id); gitStorageSelect(ctx, storage) }, modifier = Modifier.padding(horizontal = DriveMetrics.gap).testTag("git_storage_strip"), underTopChrome = false)
        if (storage != GitStorage.Kind.STORE) { GitStorageRepos(storage, git, Modifier.weight(1f)); SnackbarHost(snackbar); return@Column }
        NavBarTopSecondRow(pages = pages, selectedId = current, onSelect = { current = it.id }, modifier = Modifier.padding(horizontal = DriveMetrics.gap).testTag(DriveTags.SYNC_STRIP), underTopChrome = false, compact = true)
        AnimatedContent(targetState = current, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "git_page", modifier = Modifier.weight(1f)) { id ->
            when (id) {
                "me" -> GitReposScreen(git, actions, nextRun, view = GitView.ME, prefs = prefs)
                "cloud" -> GitReposScreen(git, actions, nextRun, view = GitView.CLOUD, prefs = prefs)
                else -> EmptyState(IconCatalog.vectorOrDefault(Declarations.iconDefault), stringResource(R.string.chrome_unknown_tab), "")
            }
        }
        SnackbarHost(snackbar)
    }
}

/** WorkManager's own word on minutes until the git sync fires next; null when it will not say. Shared by the Git page and Configs ▸ Git. */
@Composable
fun rememberGitNextRun(): Long? {
    val ctx = LocalContext.current
    var nextRun by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(Unit) {
        nextRun = withContext(Dispatchers.IO) {
            runCatching {
                val infos = WorkManager.getInstance(ctx).getWorkInfosForUniqueWorkFlow(GitSyncWorker.WORK_NAME).first()
                val next = infos.firstOrNull { it.state == WorkInfo.State.ENQUEUED }?.nextScheduleTimeMillis
                SyncSchedule.minutesUntilNext(next, System.currentTimeMillis())
            }.getOrNull()
        }
    }
    return nextRun
}


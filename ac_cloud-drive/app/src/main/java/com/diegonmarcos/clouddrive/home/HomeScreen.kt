package com.diegonmarcos.clouddrive.home

import android.os.Environment
import android.os.StatFs
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.diegonmarcos.clouddrive.Declarations
import com.diegonmarcos.clouddrive.DriveActions
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.clouddrive.SharedStore
import com.diegonmarcos.clouddrive.apps.AppsGrid
import com.diegonmarcos.clouddrive.files.FileOps
import com.diegonmarcos.clouddrive.sync.GitSyncCoordinator
import com.diegonmarcos.clouddrive.ui.DriveCard
import com.diegonmarcos.clouddrive.ui.DriveMetrics
import com.diegonmarcos.clouddrive.ui.DriveTags
import com.diegonmarcos.clouddrive.ui.EmptyState
import com.diegonmarcos.clouddrive.ui.IconCatalog
import com.diegonmarcos.clouddrive.ui.topIslandInsets
import com.diegonmarcos.clouddrive.ui.Pill
import com.diegonmarcos.clouddrive.ui.PillRow
import com.diegonmarcos.clouddrive.ui.SectionHeader
import com.diegonmarcos.clouddrive.ui.StatusLight
import com.diegonmarcos.clouddrive.ui.StorageBar
import com.diegonmarcos.superapp.navbartop2.NavBarTopSecondRow
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * #609 HOME — REDESIGNED into two sections, both read from state that already exists
 * (never a second source of a number): APPS is the fleet's data-app grid, the very
 * [AppsGrid] the old Apps tab drew, now embedded here since that tab is gone; VOLUMES
 * is a snapshot/resume of everything the Volumes and Sync tabs hold — the two DECLARED
 * Files sections (ui.files.sections) with a live [StorageBar], the four declared volume
 * classes (ui.volumes.classes) as a fleet-apps/containers/machines/remotes count, the
 * seeded git repositories' clean/dirty/ahead-behind glance (GitSyncCoordinator, the same
 * one Sync ▸ Git reads), and the container mesh's declared status (data/drive-connections.json).
 * Every card's quick action is a CALLBACK: the tab ids it selects live in MainActivity's
 * dispatch, the one place Kotlin names them.
 */
@Composable
fun HomeScreen(
    git: GitSyncCoordinator,
    actions: DriveActions,
    onOpenFiles: () -> Unit,
    onOpenVolumes: () -> Unit,
    onOpenSync: () -> Unit,
    onRoute: (tab: String, page: String) -> Unit,
    onSyncAll: () -> Unit,
    manage: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    page: String? = null,
    onPageConsumed: () -> Unit = {},
) {
    // #1484 Home = Apps | Manage (ui.sections[home].pages): the strip is drawn here, the Manage body is the host's slot.
    val pages = Declarations.navPages(Declarations.SECTION_HOME)
    var current by rememberSaveable { mutableStateOf(pages.firstOrNull()?.id ?: "") }
    LaunchedEffect(page) {
        if (page != null && pages.any { it.id == page }) current = page
        if (page != null) onPageConsumed()
    }
    Column(modifier.fillMaxSize()) {
        NavBarTopSecondRow(pages = pages, selectedId = current, onSelect = { current = it.id }, modifier = Modifier.fillMaxWidth().padding(horizontal = DriveMetrics.gap).testTag(DriveTags.HOME_STRIP), underTopChrome = false)
        Box(Modifier.weight(1f).consumeWindowInsets(topIslandInsets()).testTag(DriveTags.homePage(current))) {
            when (current) {
                "home_apps" -> HomeAppsPage(git, actions, onOpenFiles, onOpenVolumes, onOpenSync, onRoute, onSyncAll)
                "manage" -> manage()
                else -> EmptyState(IconCatalog.vectorOrDefault(Declarations.iconDefault), stringResource(R.string.chrome_unknown_tab), "")
            }
        }
    }
}

@Composable
private fun HomeAppsPage(
    git: GitSyncCoordinator,
    actions: DriveActions,
    onOpenFiles: () -> Unit,
    onOpenVolumes: () -> Unit,
    onOpenSync: () -> Unit,
    onRoute: (tab: String, page: String) -> Unit,
    onSyncAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sections = Declarations.files.sections
    val connections = Declarations.connections
    val containerConnections = remember(connections) { connections.filter { it.machine == Declarations.MACHINE_CONTAINER } }
    val machineConnections = remember(connections) { connections.filter { it.machine in Declarations.volumes.machineKinds } }
    val s3Remotes = remember { Declarations.remotes.filter { it.type in Declarations.volumes.s3RemoteTypes } }
    var usage by remember { mutableStateOf(emptyMap<String, Pair<Long, Long>>()) }
    LaunchedEffect(Unit) {
        // The same coordinator Sync ▸ Git reads; a second read here never re-implements the glance.
        git.refresh()
        usage = withContext(Dispatchers.IO) { sections.mapNotNull { s -> statOf(dirOf(s.id))?.let { s.id to it } }.toMap() }
    }
    val repos by git.repos.collectAsState()
    val glances by git.glances.collectAsState()
    val dirty = repos.count { r -> val g = glances[com.diegonmarcos.clouddrive.sync.GitSyncCoordinator.statusKey(r)]; g != null && (g.changed > 0 || g.conflicts > 0) }
    val ahead = repos.sumOf { r -> glances[com.diegonmarcos.clouddrive.sync.GitSyncCoordinator.statusKey(r)]?.ahead ?: 0 }
    val behind = repos.sumOf { r -> glances[com.diegonmarcos.clouddrive.sync.GitSyncCoordinator.statusKey(r)]?.behind ?: 0 }
    val reachableContainers = containerConnections.count { it.status == "ok" }

    Column(modifier.fillMaxSize()) {
        // #1356 no title row (the terminal shortcut is the Termux tile of Home ▸ Apps): the content starts under the top bar.
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            item { SectionHeader(stringResource(R.string.home_apps_section), count = Declarations.apps.size) }
            item {
                Text(
                    stringResource(R.string.home_apps_hint), Modifier.padding(horizontal = DriveMetrics.sectionInset),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item { AppsGrid(actions, onRoute) }

            item { SectionHeader(stringResource(R.string.home_volumes_section)) }
            items(sections.size) { i ->
                val s = sections[i]
                val dir = dirOf(s.id)
                DriveCard(s.label, light = StatusLight.of(dir?.isDirectory), summary = dir?.absolutePath, summaryMonospace = true, tag = DriveTags.HOME_CARD) {
                    usage[s.id]?.let { u ->
                        StorageBar(u, stringResource(R.string.files_storage_bar, FileOps.humanBytes(u.first), FileOps.humanBytes(u.second)), tag = DriveTags.HOME_STORAGE_BAR)
                    }
                    PillRow { Pill(stringResource(R.string.home_open_files), onOpenFiles, filled = true) }
                }
            }
            item {
                DriveCard(
                    stringResource(R.string.home_classes_card),
                    summary = stringResource(R.string.home_classes_summary, Declarations.constellation.size, containerConnections.size, machineConnections.size, s3Remotes.size),
                    tag = DriveTags.HOME_CARD,
                ) {
                    PillRow { Pill(stringResource(R.string.home_open_volumes), onOpenVolumes, filled = true) }
                }
            }
            item {
                DriveCard(
                    stringResource(R.string.home_git_card),
                    light = if (repos.isEmpty()) StatusLight.State.UNKNOWN else StatusLight.of(dirty == 0),
                    summary = stringResource(R.string.home_git_summary, repos.size, dirty, ahead, behind),
                    tag = DriveTags.HOME_CARD,
                ) {
                    PillRow {
                        Pill(stringResource(R.string.home_sync_now), onSyncAll, filled = true)
                        Pill(stringResource(R.string.home_open_sync), onOpenSync)
                    }
                }
            }
            item {
                DriveCard(
                    stringResource(R.string.home_containers_card),
                    light = if (containerConnections.isEmpty()) StatusLight.State.UNKNOWN else StatusLight.State.UNVERIFIABLE,
                    summary = stringResource(R.string.home_containers_summary, containerConnections.size, reachableContainers),
                    tag = DriveTags.HOME_CARD,
                ) {
                    PillRow { Pill(stringResource(R.string.home_open_volumes), onOpenVolumes) }
                }
            }
        }
    }
}

/** The folder a declared Files section stands for; null when this device has no such folder. */
private fun dirOf(sectionId: String): File? {
    val place = Declarations.files.places.firstOrNull { it.section == sectionId && (it.kind == "shared_root" || it.kind == "external_root") } ?: return null
    return if (place.kind == "shared_root") SharedStore.root() else Environment.getExternalStorageDirectory()
}

private fun statOf(dir: File?): Pair<Long, Long>? =
    if (dir == null) null else runCatching { StatFs(dir.absolutePath).let { it.availableBytes to it.totalBytes } }.getOrNull()

/** #813 the declared Home sub-page id (data/drive-apps.json `route.page`). */

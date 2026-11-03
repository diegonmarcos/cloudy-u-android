package com.diegonmarcos.clouddrive.configs

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.diegonmarcos.clouddrive.Declarations
import com.diegonmarcos.clouddrive.DriveActions
import com.diegonmarcos.clouddrive.DrivePrefs
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.superapp.navbartop2.NavBarTopSecondRow
import com.diegonmarcos.clouddrive.storage.StorageCoordinator
import com.diegonmarcos.clouddrive.sync.RcloneCoordinator
import com.diegonmarcos.clouddrive.backups.MirrorRunner
import com.diegonmarcos.clouddrive.sync.GitPart
import com.diegonmarcos.clouddrive.sync.GitReposScreen
import com.diegonmarcos.clouddrive.sync.GitSyncCoordinator
import com.diegonmarcos.clouddrive.sync.GitView
import com.diegonmarcos.clouddrive.sync.rememberGitNextRun
import com.diegonmarcos.clouddrive.ui.DriveMetrics
import com.diegonmarcos.clouddrive.ui.DriveTags
import com.diegonmarcos.clouddrive.ui.EmptyState
import com.diegonmarcos.clouddrive.ui.IconCatalog
import com.diegonmarcos.clouddrive.ui.Pill
import com.diegonmarcos.superapp.fleetconfigs.FleetConfigsPageBody

/**
 * #1216 CONFIGS: ONE island whose strip is build.json ui.sections[configs].pages: General (sections Explorer, Manage, Agents, then
 * the former Others content), Git (the sign-in cards, host settings and pull settings), Fsync (servers, APIs and SFTPs), Mounts
 * (rclone setup, one line per provider incl. Others) and Webserver (opens cloud-c3-webserver). The Backups and Others pages are gone.
 * The strip is tabs inside the tab, not a stack — back leaves the app. This file routes, it does not draw a card.
 *
 * The dispatch on a page id is the ONE place Kotlin names them; test-drive-shell.sh diffs
 * it against the declaration in both directions.
 *
 * [page] is a DECLARED page id another screen asked for (an Apps tile's route); it is
 * applied once and [onPageConsumed] clears the request.
 */
@Composable
fun ConfigsScreen(
    mirrors: MirrorRunner,
    prefs: DrivePrefs,
    actions: DriveActions,
    hasAccess: Boolean,
    rcloneVersion: String?,
    git: GitSyncCoordinator,
    storage: StorageCoordinator,
    rclone: RcloneCoordinator,
    page: String? = null,
    onPageConsumed: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val pages = Declarations.configs.pages
    var current by rememberSaveable { mutableStateOf(pages.firstOrNull()?.id ?: "") }
    LaunchedEffect(page) {
        if (page != null && pages.any { it.id == page }) current = page
        if (page != null) onPageConsumed()
    }

    Column(modifier.fillMaxSize()) {
        // #1356 no title row (the terminal shortcut is the Termux tile of Home ▸ Apps): the content starts under the top bar.
        // #868 the strip is libs:bottomnav's PageTabs over ui.sections[configs].pages.
        NavBarTopSecondRow(pages = Declarations.navPages("configs"), selectedId = current, onSelect = { current = it.id }, modifier = Modifier.padding(horizontal = DriveMetrics.gap).testTag(DriveTags.CONFIGS_STRIP), underTopChrome = false)
        AnimatedContent(targetState = current, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "configs_page", modifier = Modifier.weight(1f)) { id ->
            when (id) {
                // #1443 General | Perms | About are the fleet's shared pages (libs:ui-nav-bars); Drive is this app's own former General.
                "general" -> FleetConfigsPageBody(id, onAppConfigs = { current = "drive" })
                "perms" -> FleetConfigsPageBody(id)
                "about" -> FleetConfigsPageBody(id)
                "drive" -> GeneralPage(prefs, actions, hasAccess, rcloneVersion)
                "webserver" -> WebserverPage(actions)
                "fsync" -> ConfigsFsyncPage(storage, actions)
                "mounts" -> ConfigsMountsPage(storage, rclone, prefs)
                "git" -> GitReposScreen(git, actions, rememberGitNextRun(), view = GitView.ME, prefs = prefs, part = GitPart.SETUP)
                else -> EmptyState(IconCatalog.vectorOrDefault(Declarations.iconDefault), stringResource(R.string.chrome_unknown_tab), "")
            }
        }
    }
}

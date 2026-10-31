package com.diegonmarcos.clouddrive.files

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.diegonmarcos.clouddrive.Declarations
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.clouddrive.ui.DriveMetrics
import com.diegonmarcos.clouddrive.ui.DriveTags
import com.diegonmarcos.clouddrive.ui.EmptyState
import com.diegonmarcos.clouddrive.ui.IconCatalog
import com.diegonmarcos.clouddrive.ui.topIslandInsets
import com.diegonmarcos.superapp.navbartop2.NavBarTopSecondRow

/**
 * EXPLORER (was Files): two sub-pages from ui.sections[explorer].pages, drawn by the fleet's
 * PageTabs. Local is the file manager ([FilesScreen]); Cloud is the former Volumes tab, moved here
 * whole and renamed, its four classes now Cloud's own sub-pages. This file routes; it draws nothing
 * of either page. The `when` is the ONE place Kotlin names the two page ids.
 *
 * [page] is a declared page id another screen asked for (Home's Volumes card, Data's "show in
 * Explorer"); it is applied once and [onPageConsumed] clears it.
 */
@Composable
fun ExplorerScreen(
    page: String?,
    onPageConsumed: () -> Unit,
    local: @Composable () -> Unit,
    cloud: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    openDrive: (String) -> Unit = {},
) {
    // #1376 every attached drive is a tab of its own after the declared ones, named by its volume: it appears on
    // attach and goes on detach (the media broadcasts bump StorageVolumes.tick), and it is the Local file manager
    // opened on the drive's root.
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val usbTick by com.diegonmarcos.clouddrive.usb.StorageVolumes.tick.collectAsState()
    val drives = androidx.compose.runtime.remember(usbTick) { com.diegonmarcos.clouddrive.usb.StorageVolumes.drives(ctx) }
    val pages = Declarations.navPages(Declarations.SECTION_EXPLORER) + drives.map { d ->
        com.diegonmarcos.superapp.bottomnav.NavPage(DRIVE_TAB + d.path, d.label, if (d.kind == com.diegonmarcos.clouddrive.usb.UsbVolumeModel.Kind.USB) "usb" else "sd_card")
    }
    var current by rememberSaveable { mutableStateOf(pages.firstOrNull()?.id ?: "") }
    LaunchedEffect(page) {
        if (page != null && pages.any { it.id == page }) current = page
        if (page != null) onPageConsumed()
    }
    // A drive tab whose drive left falls back to the first declared page.
    LaunchedEffect(pages.size) { if (pages.none { it.id == current }) current = pages.firstOrNull()?.id ?: "" }
    val drivePath = current.takeIf { it.startsWith(DRIVE_TAB) }?.removePrefix(DRIVE_TAB)
    LaunchedEffect(drivePath) { drivePath?.let(openDrive) }
    Column(modifier.fillMaxSize().testTag(DriveTags.EXPLORER)) {
        NavBarTopSecondRow(pages = pages, selectedId = current, onSelect = { current = it.id }, modifier = Modifier.fillMaxWidth().padding(horizontal = DriveMetrics.gap).testTag(DriveTags.EXPLORER_STRIP), underTopChrome = false)
        // The strip already cleared the status bar; the page's own top island must not clear it twice.
        Box(Modifier.weight(1f).consumeWindowInsets(topIslandInsets()).testTag(DriveTags.explorerPage(current))) {
            if (drivePath != null) local() else when (current) {
                "local" -> local()
                "cloud" -> cloud()
                else -> EmptyState(IconCatalog.vectorOrDefault(Declarations.iconDefault), stringResource(R.string.chrome_unknown_tab), "")
            }
        }
    }
}

/** #1376 the id prefix of a drive tab; the rest is the drive's root path. */
private const val DRIVE_TAB = "drive:"

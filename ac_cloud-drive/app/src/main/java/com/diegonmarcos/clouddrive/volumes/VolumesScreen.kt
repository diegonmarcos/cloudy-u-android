package com.diegonmarcos.clouddrive.volumes

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.diegonmarcos.clouddrive.Declarations
import com.diegonmarcos.clouddrive.DriveActions
import com.diegonmarcos.clouddrive.DrivePrefs
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.superapp.bottomnav.NavPage
import com.diegonmarcos.superapp.navbartop2.NavBarTopSecondRow
import com.diegonmarcos.clouddrive.files.Places
import com.diegonmarcos.clouddrive.sync.MountsSyncScreen
import com.diegonmarcos.clouddrive.sync.RcloneCoordinator
import com.diegonmarcos.clouddrive.ui.DriveCard
import com.diegonmarcos.clouddrive.ui.DriveMetrics
import com.diegonmarcos.clouddrive.ui.DriveTags
import com.diegonmarcos.clouddrive.ui.EmptyState
import com.diegonmarcos.clouddrive.ui.IconCatalog
import com.diegonmarcos.clouddrive.ui.Pill
import com.diegonmarcos.clouddrive.ui.PillRow
import com.diegonmarcos.clouddrive.ui.SectionHeader
import com.diegonmarcos.clouddrive.ui.StatusLight
import kotlinx.coroutines.launch

/**
 * #604/#613/#630 VOLUMES — FOUR DECLARED CLASSES, in TWO SECTIONS THAT LOOK LIKE TWO SECTIONS.
 * build.json::ui.volumes.classes names Cloud-Constellation (our same-signature fleet apps),
 * Cloud-Containers (the fleet's container mounts), Cloud-Machines (VMs, PCs, phones) and S3 (OCI
 * buckets and Google Drive); #613's ui.volumes.sections reparents those four class pills under
 * two headers — Fleet Volumes (the mesh/cloud side) and Personal Volumes (Machines, Containers,
 * S3, carrying a sign-in affordance) — and a class no section claims gets an 'Others' section of
 * its own rather than vanishing (the #292 rule). The `when` in [ClassBody] is still the ONE place
 * Kotlin names a class id, and test/test-drive-shell.sh diffs it against the declaration both ways.
 *
 * #630 WHY THIS FILE CHANGED SHAPE. #613 declared two sections and then stacked BOTH pill strips
 * at the top of ONE shared body with ONE shared selection, so on the device the two headers were
 * two captions over a single list — "SEPARATE TWO SECTIONS!! IS TWO SECTIONS!!". A section now
 * owns its own header, its own strip, its own selected class and its OWN ROWS beneath it, and the
 * bodies split the screen; test/test-drive-volumes-sections.sh fails the build on a shared body.
 *
 * The Personal Mounts sign-in is a LINK, not an auth surface: it deep-links to cloud-sa's
 * Profile ▸ Connect (libs:auth, the fleet's ONE sign-in that holds the SSH keys and the Authelia
 * web-auth flow) via [DriveActions.openAuthProfile]. build.json::ui.volumes.personal_auth
 * declares the target; nothing here reimplements auth.
 *
 * The device's own removable volumes stay a header ABOVE the strip: they are DISCOVERED
 * (Places.discovered), never declared, and #603's capability is not lost by the redesign.
 *
 * This file is a HOST: it lists and it routes. Cloud-Containers is the very same
 * [MountsSyncScreen] Configs ▸ Mounts draws, scoped to the container connections, because
 * libs:mounts (#567) already owns the connections, the credentials and the browse-through.
 */
@Composable
fun VolumesScreen(rclone: RcloneCoordinator, prefs: DrivePrefs, actions: DriveActions, onOpenPath: (String) -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val snap by prefs.snapshot.collectAsState()
    val hasAccess = remember { Places.hasAllFilesAccess(ctx) }
    // #1375 drives mounting and leaving re-read the list; their last broadcast is the card's state.
    val usbTick by com.diegonmarcos.superapp.usbdrive.StorageVolumes.tick.collectAsState()
    val usbStates by com.diegonmarcos.superapp.usbdrive.StorageVolumes.states.collectAsState()
    val volumes = remember(snap, hasAccess, usbTick) { Places.discovered(ctx, snap, hasAccess).filter { it.kind == Places.Kind.PATH } }
    val rawUsb = remember(usbTick) { com.diegonmarcos.superapp.perms.PermsUsb.drives(ctx).isNotEmpty() && com.diegonmarcos.superapp.usbdrive.StorageVolumes.drives(ctx).none { it.kind == com.diegonmarcos.superapp.usbdrive.UsbVolumeModel.Kind.USB } }
    val classes = Declarations.volumes.classes
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun say(msg: String) { scope.launch { snackbar.showSnackbar(msg) } }

    Column(modifier.fillMaxSize()) {
        // #630 no shared "current class" caption any more: each section names its own selection.
        // #1356 no title row (the terminal shortcut is the Termux tile of Home ▸ Apps): the content starts under the top bar.
        SectionHeader(stringResource(R.string.volumes_device_section), count = volumes.size)
        if (volumes.isEmpty()) {
            DriveCard(stringResource(R.string.volumes_device_none), light = StatusLight.State.UNKNOWN, summary = stringResource(R.string.volumes_device_none_hint), tag = DriveTags.VOLUMES_CARD) {
                PillRow { Pill(stringResource(R.string.configs_grant_tree), { actions.requestTreeGrant() }) }
            }
        }
        volumes.forEach { v ->
            val st = v.location?.path?.let { usbStates[it] }
            val safe = st == com.diegonmarcos.superapp.usbdrive.UsbVolumeModel.DriveState.SAFE_TO_REMOVE
            val summary = listOfNotNull(v.location?.path, v.hint.ifBlank { null }, if (safe) stringResource(R.string.usb_safe_to_remove) else null).joinToString("  ·  ")
            DriveCard(v.label, light = StatusLight.of(!safe), summary = summary, summaryMonospace = true, tag = DriveTags.VOLUMES_CARD) {
                PillRow {
                    Pill(stringResource(R.string.volumes_open), { v.location?.let { onOpenPath(it.path) } }, filled = true)
                    // Only the system can unmount a volume: Eject opens its Storage page, and the media broadcasts turn this card to "Safe to remove".
                    if (v.icon == "usb" && !safe) Pill(stringResource(R.string.usb_eject_settings), { com.diegonmarcos.superapp.usbdrive.StorageVolumes.openEjectSettings(ctx) })
                }
            }
        }
        // A drive Android let go of is no longer a volume, so its "Safe to remove" stays as a card of its own.
        usbStates.filter { (k, st) -> st == com.diegonmarcos.superapp.usbdrive.UsbVolumeModel.DriveState.SAFE_TO_REMOVE && k.startsWith("/") && volumes.none { it.location?.path == k } }.keys.forEach { k ->
            DriveCard(stringResource(R.string.usb_safe_to_remove), light = StatusLight.State.OFF, summary = k, summaryMonospace = true, tag = DriveTags.VOLUMES_CARD) {}
        }
        if (rawUsb) {
            DriveCard(stringResource(R.string.usb_raw_card), light = StatusLight.State.UNKNOWN, summary = stringResource(R.string.usb_raw_card_hint), tag = DriveTags.VOLUMES_CARD) {
                PillRow { Pill(stringResource(R.string.usb_open), { com.diegonmarcos.clouddrive.usb.UsbDriveActivity.open(ctx) }, filled = true) }
            }
        }
        val auth = Declarations.volumes.personalAuth
        // ONE SECTION = its own header, its own pill strip, its own selected class and its OWN
        // ROWS. The sections split the screen (each body takes an equal weight) and share no
        // state, which is the whole difference between "two declared sections" and "two sections
        // the user can see". #613 had both strips over one body and it read as one list.
        Declarations.volumes.sections.forEach { sec ->
            VolumeSection(
                label = sec.label,
                secClasses = sec.classIds.mapNotNull { id -> classes.firstOrNull { c -> c.id == id } },
                auth = if (sec.auth) auth else null,
                rclone = rclone, prefs = prefs, actions = actions, onOpenPath = onOpenPath, say = ::say,
            )
        }
        // A class no section claims keeps a section of its own rather than vanishing (#292).
        val others = Declarations.volumes.unsectioned()
        if (others.isNotEmpty()) {
            VolumeSection(
                label = stringResource(R.string.volumes_others), secClasses = others, auth = null,
                rclone = rclone, prefs = prefs, actions = actions, onOpenPath = onOpenPath, say = ::say,
            )
        }
        SnackbarHost(snackbar)
    }
}

/**
 * #630 ONE DECLARED SECTION, drawn as one: header, its own class pills, and its own rows beneath
 * them. The selection is the SECTION'S ([pick] is remembered per section), so picking a class in
 * Personal Volumes cannot change what Fleet Volumes is showing — the defect #613 shipped.
 */
@Composable
private fun ColumnScope.VolumeSection(
    label: String,
    secClasses: List<Declarations.VolumeClassDecl>,
    auth: Declarations.PersonalAuthDecl?,
    rclone: RcloneCoordinator,
    prefs: DrivePrefs,
    actions: DriveActions,
    onOpenPath: (String) -> Unit,
    say: (String) -> Unit,
) {
    val ctx = LocalContext.current
    key(label) {
        var pick by rememberSaveable { mutableStateOf(secClasses.firstOrNull()?.id ?: "") }
        Column(Modifier.fillMaxWidth().weight(1f).testTag(DriveTags.VOLUMES_SECTION)) {
            ClassStrip(label, secClasses, pick, { pick = it }) {
                if (auth != null) Pill(
                    stringResource(R.string.volumes_personal_auth),
                    { if (!actions.openAuthProfile(auth.pkg, auth.target, auth.extra)) say(ctx.getString(R.string.volumes_personal_auth_unavailable)) },
                    icon = IconCatalog.vectorOrDefault(auth.icon),
                )
            }
            AnimatedContent(targetState = pick, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "volumes_class", modifier = Modifier.weight(1f)) { id ->
                ClassBody(id, rclone, prefs, actions, onOpenPath, say)
            }
        }
    }
}

/**
 * #604 THE ONE PLACE KOTLIN NAMES A VOLUME CLASS ID. Lifted out of [VolumesScreen] by #630 so
 * every section can draw its OWN body from the same dispatch rather than sharing one; the `when`
 * itself is unchanged and test/test-drive-shell.sh D12 still diffs it against ui.volumes.classes
 * in both directions.
 */
@Composable
private fun ClassBody(
    id: String,
    rclone: RcloneCoordinator,
    prefs: DrivePrefs,
    actions: DriveActions,
    onOpenPath: (String) -> Unit,
    say: (String) -> Unit,
) {
    when (id) {
        "constellation" -> ConstellationVolumes(actions, onOpenPath, say)
        "containers" -> MountsSyncScreen(rclone, prefs, actions, say, connections = Declarations.connections.filter { it.machine == Declarations.MACHINE_CONTAINER })
        "machines" -> MachineVolumes()
        "s3" -> S3Volumes(rclone)
        else -> EmptyState(IconCatalog.vectorOrDefault(Declarations.iconDefault), stringResource(R.string.chrome_unknown_tab), "")
    }
}

/**
 * #613 one section's header and the horizontally-scrolling row of its class pills, plus any
 * [trailing] affordance (the Personal Volumes sign-in). The pill idiom is unchanged from #604.
 */
@Composable
private fun ClassStrip(
    label: String,
    classes: List<Declarations.VolumeClassDecl>,
    current: String,
    onPick: (String) -> Unit,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    SectionHeader(label, count = classes.size)
    // #868 the class pills are libs:bottomnav's PageTabs (the class ids are ui.sections[volumes].pages).
    Row(Modifier.fillMaxWidth().padding(end = DriveMetrics.padWide), verticalAlignment = Alignment.CenterVertically) {
        NavBarTopSecondRow(
            pages = classes.map { NavPage(it.id, it.label, it.icon) },
            selectedId = current,
            onSelect = { onPick(it.id) },
            modifier = Modifier.weight(1f).testTag(DriveTags.VOLUMES_STRIP),
            underTopChrome = false,
        )
        trailing()
    }
}

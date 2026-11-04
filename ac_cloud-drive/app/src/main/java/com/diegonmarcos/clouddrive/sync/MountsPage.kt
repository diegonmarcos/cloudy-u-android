package com.diegonmarcos.clouddrive.sync

import com.diegonmarcos.cloudlib.gitrepos.*

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.diegonmarcos.clouddrive.Declarations
import com.diegonmarcos.clouddrive.storage.MountProviders
import com.diegonmarcos.clouddrive.DriveActions
import com.diegonmarcos.clouddrive.DrivePrefs
import com.diegonmarcos.clouddrive.EngineActivity
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.clouddrive.storage.FuseWire
import com.diegonmarcos.clouddrive.storage.StorageCoordinator
import com.diegonmarcos.clouddrive.ui.DriveCard
import com.diegonmarcos.clouddrive.ui.DriveMetrics
import com.diegonmarcos.clouddrive.ui.DriveTags
import com.diegonmarcos.clouddrive.ui.EmptyState
import com.diegonmarcos.clouddrive.ui.Pill
import com.diegonmarcos.clouddrive.ui.PillRow
import com.diegonmarcos.clouddrive.ui.SectionHeader
import com.diegonmarcos.clouddrive.ui.StatusLight
import com.diegonmarcos.clouddrive.ui.StatusLightRow
import com.diegonmarcos.superapp.navbartop2.NavBarTopSecondRow
import kotlinx.coroutines.launch

/**
 * #1173 MOUNTS, two sub-pages on the second row (icons declared in build.json ui.sections[mounts].pages): Rclone (the cloud backends
 * through rclone's own config flow, listed, tested, edited, deleted, served) and FUSE (real FUSE mounts inside the Termux guest,
 * with the runtime verdict on whether FUSE can work on this device at all). Real state only: the remotes are what rclone.conf
 * holds, the mounts are what the user defined and what the guest's mount table says.
 */
@Composable
fun MountsPageScreen(
    storage: StorageCoordinator,
    rclone: RcloneCoordinator,
    prefs: DrivePrefs,
    actions: DriveActions,
    page: String? = null,
    onPageConsumed: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val pages = Declarations.navPages(Declarations.SECTION_MOUNTS)
    var current by rememberSaveable { mutableStateOf(pages.firstOrNull()?.id ?: "") }
    LaunchedEffect(page) {
        if (page != null && pages.any { it.id == page }) current = page
        if (page != null) onPageConsumed()
    }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun say(msg: String) { scope.launch { snackbar.showSnackbar(msg) } }
    Column(modifier.fillMaxSize()) {
        // #1356 no title row (the terminal shortcut is the Termux tile of Home ▸ Apps): the content starts under the top bar.
        NavBarTopSecondRow(pages = pages, selectedId = current, onSelect = { current = it.id }, modifier = Modifier.padding(horizontal = DriveMetrics.gap).testTag(DriveTags.SYNC_STRIP), underTopChrome = false)
        AnimatedContent(targetState = current, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "mounts_page", modifier = Modifier.weight(1f)) { id ->
            when (id) {
                "rclone" -> RcloneRemotesScreen(storage, rclone, prefs, actions, ::say)
                "fuse" -> FuseMountsScreen(storage, rclone, actions, prefs, ::say)
                else -> EmptyState(Icons.Filled.Lan, stringResource(R.string.chrome_unknown_tab), "")
            }
        }
        SnackbarHost(snackbar)
    }
}

@Composable
fun RcloneRemotesScreen(storage: StorageCoordinator, rclone: RcloneCoordinator, prefs: DrivePrefs, actions: DriveActions, onMessage: (String) -> Unit, modifier: Modifier = Modifier) {
    @Suppress("UNUSED_VARIABLE") val prefsTick by prefs.snapshot.collectAsState()
    val version by rclone.version.collectAsState()
    val remotes by rclone.remotes.collectAsState()
    val notes by storage.remoteNotes.collectAsState()
    val busy by storage.remoteBusy.collectAsState()
    val serving by storage.serving.collectAsState()
    // #1195 stored lists render at once; running rclone (version, remotes) and the account read happen only when the Settings ask or Refresh is tapped.
    AutoRefreshEffects(AutoChecks.Page.MOUNTS, prefs, { rclone.loadStored(); storage.refreshAccount(deep = false) }, { rclone.refresh(); storage.refreshAccount() })
    var open by rememberSaveable { mutableStateOf("") }
    val available = rclone.runner.isAvailable
    var wizard by remember { mutableStateOf<Pair<Boolean, String?>?>(null) }   // (open, editing name)
    var deleting by remember { mutableStateOf<String?>(null) }
    LazyColumn(modifier.fillMaxSize()) {
        item {
            PageActionsBar(
                AutoChecks.Page.MOUNTS, prefs,
                listOf(
                    BarAction(stringResource(R.string.remote_add), { wizard = true to null }, icon = Icons.Filled.CloudSync),
                    BarAction(stringResource(R.string.mounts_refresh), { rclone.refresh(); storage.refreshAccount() }, icon = Icons.Filled.Sync),
                ),
            )
        }
        item {
            Row(Modifier.fillMaxWidth().testTag(DriveTags.SYNC_HERO).padding(horizontal = DriveMetrics.sectionInset, vertical = DriveMetrics.pad), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (available) stringResource(R.string.rclone_binary_present, version ?: "") else stringResource(R.string.rclone_binary_missing), style = MaterialTheme.typography.labelMedium)
                    if (!available) Text(rclone.runner.unavailableReason ?: stringResource(R.string.remote_engine_missing), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                StatusLightRow(StatusLight.of(available), stringResource(R.string.rclone_remotes_section))
                Spacer(Modifier.width(DriveMetrics.pad))
                Pill(stringResource(R.string.remote_engine_open), { actions.openEngine(EngineActivity.ENGINE_RCLONE, "jobs") })
            }
        }
        item { AccountLine(storage, actions) }
        item { SectionHeader(stringResource(R.string.rclone_remotes_section), count = remotes.size) }
        if (remotes.isEmpty()) item { EmptyState(Icons.Filled.CloudSync, stringResource(R.string.remote_empty), stringResource(R.string.remote_empty_hint), actionLabel = stringResource(R.string.remote_add)) { wizard = true to null } }
        items(remotes, key = { "remote-" + it.name }) { r ->
            val last = prefs.lastTest("remote:" + r.name)
            val options = r.options.filterKeys { k -> !com.diegonmarcos.clouddrive.storage.RcloneProviders.masked(k) }.entries.joinToString("  ") { "${it.key}=${it.value}" }
            val serve = serving[r.name]
            TapCard("${r.name}:", expanded = open == "r-" + r.name, onToggle = { open = TapPanel.toggle(open, "r-" + r.name) }, light = StatusLight.of(last), summary = "${r.type}  $options", summaryMonospace = true, tag = DriveTags.SYNC_REMOTE_CARD) {
                val note = notes[r.name] ?: if (last == null) stringResource(R.string.rclone_untested) else null
                if (!note.isNullOrBlank()) Text(note, Modifier.padding(top = DriveMetrics.gap), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (serve != null) Text(stringResource(R.string.remote_serving, serve.port), Modifier.padding(top = DriveMetrics.gap), style = MaterialTheme.typography.labelSmall)
                if (r.name in busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = DriveMetrics.gap))
                PillRow {
                    Pill(stringResource(R.string.rclone_test), { storage.testRemote(r.name) }, filled = true, enabled = available && r.name !in busy)
                    Pill(stringResource(R.string.remote_usage), { storage.aboutRemote(r.name) }, enabled = available && r.name !in busy)
                    Pill(stringResource(R.string.rclone_browse), { actions.openEngine(EngineActivity.ENGINE_RCLONE, "browse") }, enabled = available)
                    Pill(stringResource(R.string.rclone_edit), { wizard = true to r.name }, enabled = available)
                }
                PillRow {
                    if (serve == null) Pill(stringResource(R.string.remote_serve), { storage.serve(r.name) }, enabled = available)
                    else Pill(stringResource(R.string.remote_stop), { storage.stopServe(r.name) })
                    Pill(stringResource(R.string.storage_delete), { deleting = r.name }, enabled = available, danger = true)
                }
            }
        }
    }
    wizard?.let { (_, editing) -> RemoteWizard(storage, editing) { wizard = null; rclone.refresh() } }
    deleting?.let { name ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.remote_delete_title, name)) },
            text = { Text(stringResource(R.string.remote_delete_body)) },
            confirmButton = { TextButton(onClick = { storage.deleteRemote(name) { why -> if (why != null) onMessage(why) }; deleting = null }) { Text(stringResource(R.string.storage_delete)) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.storage_cancel)) } },
        )
    }
}

@Composable
fun FuseMountsScreen(storage: StorageCoordinator, rclone: RcloneCoordinator, actions: DriveActions, prefs: DrivePrefs, onMessage: (String) -> Unit, modifier: Modifier = Modifier) {
    val specs by storage.fuseSpecs.collectAsState()
    val verdict by storage.fuseVerdict.collectAsState()
    val caps by storage.fuseCaps.collectAsState()
    val live by storage.fuseLive.collectAsState()
    val serving by storage.guestServing.collectAsState()
    val busy by storage.fuseBusy.collectAsState()
    val notes by storage.fuseNotes.collectAsState()
    AutoRefreshEffects(AutoChecks.Page.MOUNTS, prefs, { storage.refreshAccount(deep = false); storage.refreshFuse(deep = false) }, { storage.refreshAccount(); storage.refreshFuse() })
    var open by rememberSaveable { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    val usable = verdict?.usable == true
    val remotes by rclone.remotes.collectAsState()
    val providerIds = Declarations.mountProviders.map { it.id }.toSet()
    val ownSpecs = specs.filter { it.id !in providerIds }
    LazyColumn(modifier.fillMaxSize()) {
        item {
            PageActionsBar(
                AutoChecks.Page.MOUNTS, prefs,
                listOf(
                    BarAction(stringResource(R.string.fuse_new), { adding = true }, icon = Icons.Filled.Lan),
                    BarAction(stringResource(R.string.fuse_check), { rclone.refresh(); storage.refreshAccount(); storage.refreshFuse() }, icon = Icons.Filled.Sync),
                ),
            )
        }
        item {
            Row(Modifier.fillMaxWidth().testTag(DriveTags.SYNC_HERO).padding(horizontal = DriveMetrics.sectionInset, vertical = DriveMetrics.pad), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.fuse_status), style = MaterialTheme.typography.labelMedium)
                    Text(verdict?.reason ?: stringResource(R.string.fuse_checking), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 5, overflow = TextOverflow.Ellipsis)
                    caps?.let { c -> Text(FuseWire.PROBE_BINS.joinToString("  ") { (if (c.has(it)) "✓ " else "✗ ") + it }, Modifier.padding(top = DriveMetrics.gap), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2) }
                }
                StatusLightRow(StatusLight.of(verdict?.usable), stringResource(R.string.fuse_status))
            }
        }
        item { AccountLine(storage, actions) }
        if (verdict != null && !usable) item { NoteText(stringResource(R.string.fuse_fallback_hint), Modifier.padding(horizontal = DriveMetrics.sectionInset)) }
        // #1216 one line per declared provider (ui.sync.mounts.providers): the same FuseWire mount, its spec derived from the line
        val lines = Declarations.mountProviders.filter { !it.others }
        val remoteTypes = remotes.associate { it.name to it.type }
        if (lines.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.fuse_providers_section), count = lines.size) }
            items(lines, key = { "prov-" + it.id }) { d ->
                val spec = MountProviders.specFor(d)!!
                val st = MountProviders.state(d, remoteTypes, live)
                val isServing = d.id in serving
                val working = d.id in busy
                TapCard(d.label, expanded = open == "p-" + d.id, onToggle = { open = TapPanel.toggle(open, "p-" + d.id) }, badge = d.rcloneType, light = when (st) { MountProviders.State.MOUNTED -> StatusLight.State.ON; MountProviders.State.READY -> StatusLight.State.OFF; else -> StatusLight.State.UNKNOWN }, summary = "${spec.source}  →  ${spec.mountpoint}", summaryMonospace = true, tag = DriveTags.SYNC_MOUNT_CARD) {
                    Text(
                        notes[d.id] ?: when (st) {
                            MountProviders.State.MISSING -> stringResource(R.string.fuse_provider_missing, d.remote)
                            MountProviders.State.WRONG_TYPE -> stringResource(R.string.fuse_provider_wrong_type, d.remote, d.rcloneType)
                            MountProviders.State.MOUNTED -> stringResource(R.string.fuse_mounted)
                            MountProviders.State.READY -> stringResource(R.string.fuse_not_mounted) + if (d.readOnly) " · ro" else ""
                        },
                        Modifier.padding(top = DriveMetrics.gap), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 4, overflow = TextOverflow.Ellipsis,
                    )
                    if (working) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = DriveMetrics.gap))
                    PillRow {
                        if (st == MountProviders.State.MOUNTED) Pill(stringResource(R.string.fuse_unmount), { storage.unmount(specs.firstOrNull { it.id == d.id } ?: spec) }, enabled = !working)
                        else Pill(stringResource(R.string.fuse_mount), {
                            val known = specs.firstOrNull { it.id == d.id }
                            if (known != null) storage.mount(known)
                            else storage.addFuse(spec) { why -> if (why == null) storage.mount(spec) else onMessage(why) }
                        }, filled = true, enabled = st == MountProviders.State.READY && usable && !working && !isServing)
                        if (specs.any { it.id == d.id }) Pill(stringResource(R.string.storage_delete), { storage.removeFuse(spec) }, enabled = !working && st != MountProviders.State.MOUNTED && !isServing, danger = true)
                    }
                }
            }
        }
        item { SectionHeader(stringResource(R.string.fuse_section), count = ownSpecs.size) }
        if (ownSpecs.isEmpty()) item { EmptyState(Icons.Filled.Lan, stringResource(R.string.fuse_empty), stringResource(R.string.fuse_empty_hint), actionLabel = stringResource(R.string.fuse_new)) { adding = true } }
        items(ownSpecs, key = { "fuse-" + it.id }) { s ->
            val mounted = FuseWire.isMounted(live, s)
            val isServing = s.id in serving
            val working = s.id in busy
            TapCard(s.id, expanded = open == "f-" + s.id, onToggle = { open = TapPanel.toggle(open, "f-" + s.id) }, badge = s.kind.label, light = if (mounted || isServing) StatusLight.State.ON else StatusLight.State.OFF, summary = "${s.source}  →  ${s.mountpoint}", summaryMonospace = true, tag = DriveTags.SYNC_MOUNT_CARD) {
                Text(
                    notes[s.id] ?: if (isServing) stringResource(R.string.fuse_serving, FuseWire.servePort(s.id)) else stringResource(if (mounted) R.string.fuse_mounted else R.string.fuse_not_mounted) + " · cache ${s.cacheMode}" + if (s.readOnly) " · ro" else "",
                    Modifier.padding(top = DriveMetrics.gap), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 4, overflow = TextOverflow.Ellipsis,
                )
                if (working) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = DriveMetrics.gap))
                PillRow {
                    if (mounted) Pill(stringResource(R.string.fuse_unmount), { storage.unmount(s) }, enabled = !working)
                    else Pill(stringResource(R.string.fuse_mount), { storage.mount(s) }, filled = true, enabled = usable && !working && !isServing)
                    if (!usable && s.kind == FuseWire.Kind.RCLONE) {
                        if (isServing) Pill(stringResource(R.string.fuse_stop_serve), { storage.stopServeInGuest(s) }, enabled = !working)
                        else Pill(stringResource(R.string.fuse_serve), { storage.serveInGuest(s) }, enabled = !working && verdict != null)
                    }
                    Pill(stringResource(R.string.storage_delete), { storage.removeFuse(s) }, enabled = !working && !mounted && !isServing, danger = true)
                }
            }
        }
    }
    if (adding) NewFuseDialog(rclone.remotes.collectAsState().value.map { it.name }, onDismiss = { adding = false }, onCreate = { spec -> storage.addFuse(spec) { why -> if (why == null) adding = false else onMessage(why) } })
}

@Composable
private fun NewFuseDialog(remoteNames: List<String>, onDismiss: () -> Unit, onCreate: (FuseWire.Spec) -> Unit) {
    var kind by remember { mutableStateOf(FuseWire.Kind.RCLONE) }
    var id by remember { mutableStateOf("") }
    var source by remember { mutableStateOf("") }
    var mountpoint by remember { mutableStateOf("") }
    var cache by remember { mutableStateOf(FuseWire.DEFAULT_CACHE_MODE) }
    var readOnly by remember { mutableStateOf(false) }
    val spec = FuseWire.Spec(id.trim(), kind, source.trim(), mountpoint.trim().ifBlank { "/root/mnt/${id.trim()}" }, cache, readOnly)
    val problem = if (id.isBlank() && source.isBlank()) null else FuseWire.refusal(spec)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.fuse_dialog_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ChoiceRow(listOf("RCLONE" to stringResource(R.string.fuse_f_kind_rclone), "SSHFS" to stringResource(R.string.fuse_f_kind_ssh)), kind.name, { kind = FuseWire.Kind.valueOf(it) })
                FormField(stringResource(R.string.fuse_f_id), id, { id = it })
                if (kind == FuseWire.Kind.RCLONE && remoteNames.isNotEmpty()) ChoiceRow(remoteNames.map { "$it:" to "$it:" }, source.substringBefore(':', "") + ":", { source = it })
                FormField(stringResource(if (kind == FuseWire.Kind.RCLONE) R.string.fuse_f_source_rclone else R.string.fuse_f_source_ssh), source, { source = it })
                FormField(stringResource(R.string.fuse_f_mountpoint), mountpoint, { mountpoint = it }, supporting = "/root/mnt/${id.trim().ifBlank { "…" }}")
                NoteText(stringResource(R.string.fuse_f_cache))
                ChoiceRow(FuseWire.CACHE_MODES.map { it to it }, cache, { cache = it })
                SwitchRow(stringResource(R.string.fuse_f_readonly), readOnly, { readOnly = it })
                if (problem != null) NoteText(problem, error = true)
            }
        },
        confirmButton = { TextButton(onClick = { onCreate(spec) }, enabled = FuseWire.refusal(spec) == null) { Text(stringResource(R.string.storage_add)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.storage_cancel)) } },
    )
}

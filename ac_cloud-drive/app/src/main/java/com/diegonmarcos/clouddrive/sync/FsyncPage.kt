package com.diegonmarcos.clouddrive.sync

import com.diegonmarcos.cloudlib.gitrepos.*

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.PlayArrow
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
import com.diegonmarcos.clouddrive.DrivePrefs
import com.diegonmarcos.clouddrive.DriveActions
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.clouddrive.storage.BackupJobs
import com.diegonmarcos.clouddrive.storage.StorageCoordinator
import com.diegonmarcos.clouddrive.storage.UnisonPairs
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
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

/**
 * #1216 FSYNC: the folder sync rules (Unison, two ways, conflicts listed) as the section's main page; the former Backup sub-page is
 * gone and build.json ui.sections[fsync] declares no sub-pages, so there is no strip. Everything drawn is real state: the pairs the
 * user made and what their last runs said. The body owns its LazyColumn, which rides the shell's one chrome-collapse connection.
 */
@Composable
fun FsyncPageScreen(
    storage: StorageCoordinator,
    rclone: RcloneCoordinator,
    actions: DriveActions,
    prefs: DrivePrefs,
    page: String? = null,
    onPageConsumed: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val pages = Declarations.navPages(Declarations.SECTION_FSYNC)
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
        // #1216 the page is the folder sync rules; a section that declares sub-pages would still get its strip.
        if (pages.isEmpty()) Box(Modifier.weight(1f)) { FolderSyncScreen(rclone, storage, actions, prefs, ::say) }
        else {
            NavBarTopSecondRow(pages = pages, selectedId = current, onSelect = { current = it.id }, modifier = Modifier.padding(horizontal = DriveMetrics.gap).testTag(DriveTags.SYNC_STRIP), underTopChrome = false)
            AnimatedContent(targetState = current, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "fsync_page", modifier = Modifier.weight(1f)) { id ->
                when (id) {
                    "folders" -> FolderSyncScreen(rclone, storage, actions, prefs, ::say)
                    "agents" -> com.diegonmarcos.clouddrive.agents.AgentsScreen()
                    else -> EmptyState(Icons.Filled.Sync, stringResource(R.string.chrome_unknown_tab), "")
                }
            }
        }
        SnackbarHost(snackbar)
    }
}

/** What Account says about itself, one line: held counts, or why it is not answering. */
@Composable
internal fun AccountLine(storage: StorageCoordinator, actions: DriveActions) {
    val snap by storage.accountSnapshot.collectAsState()
    val note by storage.accountNote.collectAsState()
    val s = snap
    Row(Modifier.fillMaxWidth().padding(horizontal = DriveMetrics.sectionInset, vertical = DriveMetrics.gap), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(if (s == null) stringResource(R.string.storage_account_missing) else stringResource(R.string.storage_account_ok, s.remotes.size, s.mounts.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!note.isNullOrBlank()) Text(stringResource(R.string.storage_account_note, note.orEmpty()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(DriveMetrics.pad))
        Pill(stringResource(R.string.storage_account_open), { actions.launchApp(com.diegonmarcos.clouddrive.storage.Wire.ACCOUNT_PACKAGE, "") })
    }
}


@Composable
fun FolderSyncScreen(rclone: RcloneCoordinator, storage: StorageCoordinator, actions: DriveActions, prefs: DrivePrefs, onMessage: (String) -> Unit, modifier: Modifier = Modifier) {
    val unison = rclone.unison
    val declared by unison.declaredState.collectAsState()
    val states by unison.states.collectAsState()
    val live by unison.live.collectAsState()
    val unavailable by unison.unavailable.collectAsState()
    AutoRefreshEffects(AutoChecks.Page.FSYNC, prefs, { storage.refreshAccount(deep = false) }, { storage.refreshAccount(); unison.refresh() })
    var open by rememberSaveable { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<com.diegonmarcos.cloudlib.rclone.unison.UnisonConfig.Pair?>(null) }
    LazyColumn(modifier.fillMaxSize()) {
        item {
            PageActionsBar(
                AutoChecks.Page.FSYNC, prefs,
                listOf(
                    BarAction(stringResource(R.string.unison_add), { adding = true }, icon = Icons.Filled.Sync),
                    BarAction(stringResource(R.string.fsync_refresh), { storage.refreshAccount(); unison.refresh() }, icon = Icons.Filled.Sync),
                    BarAction(stringResource(R.string.unison_sync_now), { declared.pairs.filter { unison.enabled(it) && it.id !in live }.forEach { unison.syncNow(it) } }, enabled = unavailable == null && declared.pairs.isNotEmpty(), icon = Icons.Filled.PlayArrow),
                ),
            )
        }
        item { AccountLine(storage, actions) }
        unisonSection(unison, states, live, unavailable, onMessage, declared, onAdd = { adding = true }, onDelete = { deleting = it }, open = open, onToggle = { open = TapPanel.toggle(open, it) })
    }
    if (adding) NewPairDialog(onDismiss = { adding = false }, onCreate = { draft -> unison.addPair(draft) { why -> if (why == null) adding = false else onMessage(why) } })
    deleting?.let { pair ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.pair_delete_title)) },
            text = { Text(stringResource(R.string.pair_delete_body)) },
            confirmButton = { TextButton(onClick = { unison.removePair(pair); deleting = null }) { Text(stringResource(R.string.storage_delete)) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.storage_cancel)) } },
        )
    }
}

@Composable
private fun NewPairDialog(onDismiss: () -> Unit, onCreate: (UnisonPairs.Draft) -> Unit) {
    var name by remember { mutableStateOf("") }
    var rootA by remember { mutableStateOf("") }
    var ssh by remember { mutableStateOf(true) }
    var host by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("22") }
    var remote by remember { mutableStateOf("") }
    var local by remember { mutableStateOf("") }
    var minutes by remember { mutableStateOf("") }
    var policy by remember { mutableStateOf("") }
    val draft = UnisonPairs.Draft(
        name = name.trim(), rootA = rootA, host = if (ssh) host else "", user = user, port = port.toIntOrNull() ?: 22, remotePath = remote, localPeer = if (ssh) "" else local,
        scheduleMinutes = minutes.trim().toIntOrNull(), prefer = policy.ifBlank { null },
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pair_dialog_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                FormField(stringResource(R.string.pair_f_name), name, { name = it })
                FormField(stringResource(R.string.pair_f_root_a), rootA, { rootA = it })
                SwitchRow(stringResource(R.string.pair_f_ssh), ssh, { ssh = it })
                if (ssh) {
                    FormField(stringResource(R.string.pair_f_host), host, { host = it })
                    FormField(stringResource(R.string.pair_f_user), user, { user = it })
                    FormField(stringResource(R.string.pair_f_port), port, { port = it }, number = true)
                    FormField(stringResource(R.string.pair_f_remote), remote, { remote = it })
                } else FormField(stringResource(R.string.pair_f_local), local, { local = it })
                FormField(stringResource(R.string.pair_f_schedule), minutes, { minutes = it }, number = true)
                NoteText(stringResource(R.string.pair_f_policy))
                ChoiceRow(listOf("" to stringResource(R.string.pair_policy_ask), "a" to stringResource(R.string.pair_policy_a), "b" to stringResource(R.string.pair_policy_b), "newer" to stringResource(R.string.pair_policy_newer)), policy, { policy = it })
            }
        },
        confirmButton = { TextButton(onClick = { onCreate(draft) }, enabled = name.isNotBlank() && rootA.isNotBlank()) { Text(stringResource(R.string.storage_add)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.storage_cancel)) } },
    )
}

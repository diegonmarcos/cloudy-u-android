package com.diegonmarcos.clouddrive.mounts

import com.diegonmarcos.superapp.perms.PermsLink
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import com.diegonmarcos.clouddrive.ui.DriveMetrics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.cloudlib.mounts.MountSessions

/**
 * Sync ▸ Mounts: is the keeper holding mounts, and what the system still refuses it. Each missing
 * permission is one row with a one-tap fix; the OEM (Samsung) row shows the declared guide and a
 * "Done" the user taps once followed. Facts are re-read on every resume, so returning from Settings
 * updates the card. The UI only observes the registry: it never opens or closes a mount here.
 */
@Composable
fun MountKeeperCard() {
    val ctx = LocalContext.current
    val policy = MountKeeper.policy
    if (!policy.enabled) return
    val reg = remember { MountKeeper.install(ctx) }
    val status by reg.status.collectAsState()
    var facts by remember { mutableStateOf(MountPermissions.facts(ctx)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { facts = MountPermissions.facts(ctx) } }
    val missing = MountPermissions.missing(facts, policy)
    val live = status.count { it.state == MountSessions.State.CONNECTED }

    Card(Modifier.fillMaxWidth().padding(horizontal = DriveMetrics.padWide, vertical = DriveMetrics.gapWide)) {
        Column(Modifier.padding(DriveMetrics.padWide), verticalArrangement = Arrangement.spacedBy(DriveMetrics.gap)) {
            Text(stringResource(R.string.mount_keeper_section), style = MaterialTheme.typography.titleSmall)
            Text(
                if (status.isNotEmpty() || MountKeeper.keptIds(ctx).isNotEmpty()) stringResource(R.string.mount_keeper_running, live) else stringResource(R.string.mount_keeper_stopped),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (missing.isEmpty()) Text(stringResource(R.string.mount_keeper_all_ok), style = MaterialTheme.typography.bodySmall)
            missing.forEach { need ->
                val guide = policy.guideFor(facts.manufacturer)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            when (need) {
                                MountPermissions.Need.NOTIFICATIONS -> stringResource(R.string.mount_keeper_need_notifications)
                                MountPermissions.Need.BATTERY_OPTIMIZATION -> stringResource(R.string.mount_keeper_need_battery)
                                MountPermissions.Need.OEM_BATTERY_GUIDE -> guide?.title ?: ""
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (need == MountPermissions.Need.OEM_BATTERY_GUIDE && guide != null) Text(guide.steps, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = {
                        // #1515 notifications and the battery exemption are granted in Configs > Perms: open it on that row.
                        if (need == MountPermissions.Need.OEM_BATTERY_GUIDE) runCatching { ctx.startActivity(MountPermissions.oemGuideIntent(ctx, policy)) }
                        else PermsLink.open(ctx, "mounts", MountPermissions.permsIds(need))
                    }) { Text(stringResource(R.string.mount_keeper_fix)) }
                    if (need == MountPermissions.Need.OEM_BATTERY_GUIDE) TextButton(onClick = { MountPermissions.confirmOemGuide(ctx); facts = MountPermissions.facts(ctx) }) { Text(stringResource(R.string.mount_keeper_guide_done)) }
                }
            }
            if (status.isNotEmpty()) TextButton(onClick = { MountKeeper.stop(ctx) }) { Text(stringResource(R.string.mount_keeper_stop)) }
        }
    }
}

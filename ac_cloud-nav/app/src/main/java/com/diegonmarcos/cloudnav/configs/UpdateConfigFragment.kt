package com.diegonmarcos.cloudnav.configs

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.diegonmarcos.cloudnav.tracker.TrackerInk
import com.diegonmarcos.cloudnav.tracker.TrackerPage
import com.diegonmarcos.superapp.uikit.KitCard
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import com.diegonmarcos.superapp.uikit.KitPalette
import com.diegonmarcos.superapp.uikit.KitSectionHeader
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import com.diegonmarcos.superapp.updater.AbiUpdateTag
import com.diegonmarcos.superapp.updater.BuildConfig as UpdBuildConfig
import com.diegonmarcos.superapp.updater.UpdateProgress
import com.diegonmarcos.superapp.updater.Updater

/**
 * Configs → Update tab. The in-app self-updater surface:
 *   • current installed version + the GHCR image:tag this device pulls
 *     (ABI-aware — arm64 → latest, x86_64 → latest-x86_64),
 *   • a live status line driven by [UpdateProgress] (checking → downloading
 *     % → installing → done / failed),
 *   • "Check for updates now" → [Updater.checkNow],
 *   • "Allow app installs" → the per-app unknown-sources settings screen
 *     (Android requires the user to permit install-from-this-app once).
 *
 * All config is data-driven from build.json::release.{ghcr,auto_update} baked
 * into the updater BuildConfig — nothing hardcoded here. Compose (KitComposeFragment).
 */
class UpdateConfigFragment : KitComposeFragment() {

    private var status by mutableStateOf("Idle")

    override fun palette(): KitPalette = TrackerInk.palette()

    @Composable
    override fun Content() {
        val ctx = LocalContext.current
        val p = LocalKitPalette.current
        val version = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"
        TrackerPage {
            KitSectionHeader("App update", "")
            KitCard {
                for ((k, v) in listOf(
                    "Installed version" to version,
                    "Package" to ctx.packageName,
                    "Channel" to AbiUpdateTag.current(),
                    "Source" to "${UpdBuildConfig.GHCR_REGISTRY}/${UpdBuildConfig.GHCR_NAMESPACE}/${UpdBuildConfig.GHCR_IMAGE}",
                    "Auto-check" to if (UpdBuildConfig.AUTO_UPDATE_ENABLED) "every ${UpdBuildConfig.AUTO_UPDATE_INTERVAL_HOURS}h" else "off",
                )) Text("$k:  $v", color = p.textSecondary, style = MaterialTheme.typography.bodyMedium)
            }
            Text(status, Modifier.testTag(UpdateTags.STATUS), color = p.textPrimary, style = MaterialTheme.typography.bodyLarge)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { Updater.checkNow(ctx); status = "Checking…" },
                    modifier = Modifier.fillMaxWidth().testTag(UpdateTags.CHECK)) { Text("Check for updates now") }
                OutlinedButton(onClick = { openUnknownSourcesSettings() },
                    modifier = Modifier.fillMaxWidth().testTag(UpdateTags.ALLOW)) { Text("Allow app installs") }
            }
            Text("Updates are signed with the shared Cloud-constellation key, so SuperApp / Nav / Comms / IDE update each other without a signature conflict. The system shows an install prompt — Android can't bypass it without root.",
                color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
        }
    }

    override fun onResume() {
        super.onResume()
        UpdateProgress.setListener { state -> status = UpdateStatus.render(state) }
    }

    override fun onPause() {
        UpdateProgress.setListener(null)
        super.onPause()
    }

    private fun openUnknownSourcesSettings() {
        // #1515 the install grant lives in Configs > Perms: open it on the install_unknown row.
        val ctx = context ?: return
        runCatching { com.diegonmarcos.superapp.perms.PermsLink.open(ctx, "Auto-update", listOf("install_unknown")) }
    }
}

object UpdateTags {
    const val STATUS = "update:status"
    const val CHECK = "update:check"
    const val ALLOW = "update:allow"
}

object UpdateStatus {
    fun render(state: UpdateProgress.State): String = when (state) {
        is UpdateProgress.State.Idle             -> "Idle"
        is UpdateProgress.State.CheckingManifest -> "Checking GHCR for a newer build…"
        is UpdateProgress.State.Downloading      -> "Downloading… ${state.percent}%"
        is UpdateProgress.State.Installing       -> "Installing — confirm the system prompt…"
        is UpdateProgress.State.Done             -> "Up to date / installed ✓"
        is UpdateProgress.State.Failed           -> "Failed: ${state.message}"
        is UpdateProgress.State.UpdateAvailable  -> "Update ready — held back on a metered network"
        is UpdateProgress.State.Cancelled        -> "Cancelled"
        // A pass held by a policy constraint, not by a failing network: the reason is the point of
        // the state. No `else` on purpose: a new state must be a compile error, not a blank line.
        is UpdateProgress.State.Waiting          -> "Waiting — ${state.reason}"
    }
}

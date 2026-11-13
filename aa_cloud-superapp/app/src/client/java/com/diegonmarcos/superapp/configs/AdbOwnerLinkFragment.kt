package com.diegonmarcos.superapp.configs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.shellclient.ShellClient
import com.diegonmarcos.superapp.system.AdbReadiness
import com.diegonmarcos.superapp.ui.LauncherPalette
import com.diegonmarcos.superapp.uikit.KitComposeFragment

/**
 * #1682 P3 step 5 (CLIENT build): Configs > Network > ADB Shell is a link. The ADB Shell (pairing, Connect, modes, Tester, Perms chain,
 * terminal, logs) lives in the ADB owner app; this page shows the owner's live readiness (libs:adb-readiness) and opens its page.
 * Never crashes: a missing owner is the reason line ShellClient.openArmPage toasts.
 */
class AdbOwnerLinkFragment : KitComposeFragment() {
    override fun palette() = LauncherPalette.kit(requireContext())

    @Composable
    override fun Content() {
        val ctx = LocalContext.current
        val ready by AdbReadiness.state.collectAsState()
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("ADB Shell lives in the ADB owner app", style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("adb-owner-title"))
            Text(ready.linkText(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("adb-owner-status"))
            Text("Open ADB Shell (pair, Connect, modes, tester, permissions, terminal)", color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("adb-owner-open").clickable { ShellClient.openArmPage(ctx) })
        }
    }

    companion object {
        fun newInstance() = AdbOwnerLinkFragment()
    }
}

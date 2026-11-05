package com.diegonmarcos.superapp.configs

import androidx.compose.runtime.Composable
import com.diegonmarcos.superapp.ui.LauncherPalette
import com.diegonmarcos.superapp.uikit.KitComposeFragment

/**
 * Configs > Setup > Network > ADB Shell. Hosts [AdbShellBrokerPage] (#1522): the broker's live status, the actions, the keeper,
 * the callers and the two collapsed logs, as dense KitStoreRow rows from [AdbShellModel]. The page's label is `build.json`
 * config page `adb-shell`. A KitComposeFragment, so it sits under the dense CloudKitTheme like every other SuperApp page (#773).
 * Where the page starts is the shell's business (ShellTopInset), not this page's.
 */
class AdbShellFragment : KitComposeFragment() {
    override fun palette() = LauncherPalette.kit(requireContext())

    @Composable
    override fun Content() { AdbShellBrokerPage() }

    companion object { fun newInstance() = AdbShellFragment() }
}

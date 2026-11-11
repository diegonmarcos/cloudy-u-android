package com.diegonmarcos.superapp.configs

import android.os.Bundle
import androidx.compose.runtime.Composable
import com.diegonmarcos.superapp.ui.LauncherPalette
import com.diegonmarcos.superapp.uikit.KitComposeFragment

/**
 * Configs > Setup > Network > ADB Shell. The page declares four tabs in `build.json` (`adb-shell.tabs`, one shared strip like WiFi):
 * Status ([AdbStatusTab]: the control card, where Connect walks the whole chain, the step cards, the log tail), Shell ([AdbTermTab]: terminal + command
 * bookmarks), Perms ([AdbPermsTab]: the permission checker) and All ([AdbShellBrokerPage] (#1522), today's whole page, unchanged:
 * the broker's live status, the actions, the keeper, the callers and the two collapsed logs, as dense KitStoreRow rows from [AdbShellModel]).
 * A KitComposeFragment, so it sits under the dense CloudKitTheme like every other SuperApp page (#773).
 * Where the page starts is the shell's business (ShellTopInset), not this page's.
 */
class AdbShellFragment : KitComposeFragment() {
    override fun palette() = LauncherPalette.kit(requireContext())

    private val tab: AdbCockpitModel.Tab get() = AdbCockpitModel.Tab.of(arguments?.getString(ARG_TAB))

    @Composable
    override fun Content() {
        when (tab) {
            AdbCockpitModel.Tab.STATUS -> AdbStatusTab()
            AdbCockpitModel.Tab.SHELL -> AdbTermTab()
            AdbCockpitModel.Tab.PERMS -> AdbPermsTab()
            AdbCockpitModel.Tab.ALL -> AdbShellBrokerPage()
        }
    }

    companion object {
        private const val ARG_TAB = "tab"
        /** No tab named: the combined page, as before the tabs. */
        fun newInstance() = newInstance(AdbCockpitModel.Tab.ALL)
        internal fun newInstance(tab: AdbCockpitModel.Tab) = AdbShellFragment().apply { arguments = Bundle().apply { putString(ARG_TAB, tab.pageId) } }
    }
}

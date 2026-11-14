package com.diegonmarcos.superapp.network

import android.os.Bundle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentContainerView
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.bottomnav.BottomNavIslandView
import com.diegonmarcos.superapp.bottomnav.BottomNavViewItem
import com.diegonmarcos.superapp.firewall.FirewallDialog
import com.diegonmarcos.superapp.launcher.BackHandler
import com.diegonmarcos.superapp.system.ShellOverride
import com.diegonmarcos.superapp.ui.LauncherPalette
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/**
 * #1719 Firewall, taking the screen over below the shell's top row. It is a [ShellOverride] that owns the bottom nav: the
 * SuperApp's own bottom nav is replaced by the firewall's own ([NgFirewallNav]: Status | Apps | Rules, libs:bottomnav's
 * island) and the shell's one Back arrow (routed here through [BackHandler]) closes it, the shell's back-stack listener then restores the SuperApp nav.
 * Every view is the engine's own screen ([FirewallDialog], one per section), so nothing the firewall page had is lost.
 */
class NgFirewallFragment : KitComposeFragment(), ShellOverride, BackHandler {

    private var selected by mutableStateOf(NgFirewallNav.DEFAULT)

    val selectedTab: String get() = selected

    // The bottom nav only: the shell keeps its top row (PageTopInsetTest T2 / ShellTopInsetStaticGuardTest S5: no page hides the top row).
    override fun ownsBottomNav(): Boolean = true

    override fun palette() = LauncherPalette.kit(requireContext())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val saved = savedInstanceState?.getString(STATE_TAB)
        selected = NgFirewallNav.TABS.firstOrNull { it.first == saved }?.first ?: NgFirewallNav.DEFAULT
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_TAB, selected)
    }

    /** The shell's top-row Back arrow (and the system Back) close Firewall: the one Back control of this page. */
    override fun tryHandleBack(): Boolean { close(); return true }

    /** Close Firewall: pop it; the shell re-applies its chrome on the back-stack change. With nothing to pop, go to Configs. */
    internal fun close() {
        val fm = parentFragmentManager
        if (fm.backStackEntryCount > 0) fm.popBackStack()
        else (activity as? com.diegonmarcos.superapp.launcher.TileGridFragment.TileClickListener)?.onTileClicked("section:config")
    }

    @Composable
    override fun Content() {
        val ctx = LocalContext.current
        val p = LocalKitPalette.current
        val items = remember {
            NgFirewallNav.TABS.map { (id, label) ->
                BottomNavViewItem(id, label, NgFirewallHubFragment.drawable(ctx, NgFirewallNav.ICONS.getValue(id)))
            }
        }
        var shown by remember { mutableStateOf<String?>(null) }
        Column(Modifier.fillMaxSize().testTag(TAG_ROOT)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Firewall", color = p.textPrimary, fontSize = 18.sp)
            }
            AndroidView(
                modifier = Modifier.weight(1f).fillMaxWidth().testTag(TAG_PANE),
                factory = { c -> FragmentContainerView(c).apply { id = R.id.ngfw_pane } },
                update = { host ->
                    val tab = selected
                    if (shown != tab) {
                        shown = tab
                        host.post {
                            if (isAdded && selected == tab) {
                                val page = FirewallDialog.newInstance(tab).apply { showsDialog = false }
                                childFragmentManager.beginTransaction().replace(R.id.ngfw_pane, page).commitAllowingStateLoss()
                            }
                        }
                    }
                },
            )
            AndroidView(
                modifier = Modifier.fillMaxWidth(),
                factory = { c -> BottomNavIslandView(c).apply { this.items = items } },
                update = { v ->
                    v.selectedId = selected
                    v.onSelect = { id -> selected = id }
                },
            )
        }
    }

    /** Drop the pane child before the child manager rebuilds views (same hazard as SectionTabsFragment, #1544). */
    override fun onViewCreated(view: android.view.View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val stale = childFragmentManager.fragments.filter { it.id == R.id.ngfw_pane }
        if (stale.isNotEmpty()) {
            childFragmentManager.beginTransaction().apply { stale.forEach { remove(it) } }.commitNowAllowingStateLoss()
        }
    }

    companion object {
        const val TAG_ROOT = "ngfw:root"
        const val TAG_PANE = "ngfw:pane"
        private const val STATE_TAB = "tab"
        fun newInstance(): NgFirewallFragment = NgFirewallFragment()
    }
}

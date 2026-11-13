package com.diegonmarcos.superapp.network

import android.os.Bundle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentContainerView
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.bottomnav.BottomNavIslandView
import com.diegonmarcos.superapp.bottomnav.BottomNavViewItem
import com.diegonmarcos.superapp.launcher.BackHandler
import com.diegonmarcos.superapp.launcher.GroupTitle
import com.diegonmarcos.superapp.launcher.Sections
import com.diegonmarcos.superapp.launcher.TILE_PAGE_PADDING
import com.diegonmarcos.superapp.launcher.TileGridFragment
import com.diegonmarcos.superapp.launcher.TileStrip
import com.diegonmarcos.superapp.system.ShellOverride
import com.diegonmarcos.superapp.ui.LauncherPalette
import com.diegonmarcos.superapp.uikit.KitComposeFragment

/**
 * #1719 Configs > Network as a page with its own bottom nav ([NetworkNav]): Wireless | DNS | VPN | Home | NG Firewall | OS
 * Perms | Webserver, drawn by libs:bottomnav's island (the fleet's one bottom nav). It owns the bottom of the screen
 * ([ShellOverride.ownsBottomNav]) so the shell's section nav is replaced, not stacked; the shell's top row and its Back arrow
 * stay (Back from a tab other than Home returns to Home first, then leaves). Home is the default tab: an icon grid of every
 * Network item. DNS and VPN host the existing pages unchanged; the three link items open NG Firewall (full screen), Cloud
 * Store > Access and Cloud C3 Webserver through the shell's one dispatcher.
 */
class NetworkNavFragment : KitComposeFragment(), ShellOverride, BackHandler {

    private var selected by mutableStateOf(NetworkNav.DEFAULT)

    /** The tab shown now (read by tests and the emulator walk). */
    val selectedTab: String get() = selected

    override fun ownsBottomNav(): Boolean = true

    override fun palette() = LauncherPalette.kit(requireContext())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val saved = savedInstanceState?.getString(STATE_TAB) ?: arguments?.getString(ARG_TAB)
        selected = saved?.takeIf { it in NetworkNav.PAGE_TABS } ?: NetworkNav.DEFAULT
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_TAB, selected)
    }

    /** Back from any tab but Home goes to Home; Home hands Back to the shell. */
    override fun tryHandleBack(): Boolean {
        if (selected == NetworkNav.DEFAULT) return false
        selected = NetworkNav.DEFAULT
        return true
    }

    private fun dispatch(target: String) {
        if (target.startsWith(NetworkNav.TAB_PREFIX)) {
            target.removePrefix(NetworkNav.TAB_PREFIX).takeIf { it in NetworkNav.PAGE_TABS }?.let { selected = it }
        } else {
            (activity as? TileGridFragment.TileClickListener)?.onTileClicked(target)
        }
    }

    @Composable
    override fun Content() {
        val ctx = LocalContext.current
        val items = remember {
            NetworkNav.TABS.map { (id, label) -> BottomNavViewItem(id, label, drawable(ctx, NetworkNav.TAB_ICONS.getValue(id))) }
        }
        Column(Modifier.fillMaxSize().testTag(TAG_ROOT)) {
            Column(Modifier.weight(1f).fillMaxWidth()) {
                when (selected) {
                    NetworkNav.DNS -> Pane(selected) { DnsFragment.newInstance() }
                    NetworkNav.VPN -> Pane(selected) { WireGuardFragment.newInstance() }
                    NetworkNav.WIRELESS -> IconGrid("Wireless", NetworkNav.WIRELESS_ITEMS, TAG_WIRELESS)
                    else -> IconGrid("Network", NetworkNav.HOME_ITEMS, TAG_HOME)
                }
            }
            AndroidView(
                modifier = Modifier.fillMaxWidth(),
                factory = { c -> BottomNavIslandView(c).apply { this.items = items } },
                update = { v ->
                    v.selectedId = selected
                    v.onSelect = { id ->
                        val link = NetworkNav.linkTarget(id)
                        if (link != null) dispatch(link) else selected = id
                    }
                    v.onReselect = { id -> if (NetworkNav.linkTarget(id) == null) selected = id }
                },
            )
        }
    }

    @Composable
    private fun IconGrid(title: String, grid: List<NetworkNav.Item>, tag: String) {
        val ctx = LocalContext.current
        val tiles = remember(grid) {
            grid.map { Sections.AggTile(it.id, it.label, "", it.target, iconRes = drawable(ctx, it.icon)) }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(TILE_PAGE_PADDING).testTag(tag)) {
            GroupTitle(title)
            TileStrip(tiles, { dispatch(it) }, openFolder = {}, wrap = true)
        }
    }

    /** One pane hosting [make]'s page; re-committed only when the tab changes. */
    @Composable
    private fun Pane(tab: String, make: () -> Fragment) {
        var shown by remember { mutableStateOf<String?>(null) }
        AndroidView(
            modifier = Modifier.fillMaxSize().testTag(TAG_PANE),
            factory = { c -> FragmentContainerView(c).apply { id = R.id.network_pane } },
            update = { host ->
                if (shown != tab) {
                    shown = tab
                    host.post {
                        if (isAdded && selected == tab) {
                            childFragmentManager.beginTransaction().replace(R.id.network_pane, make()).commitAllowingStateLoss()
                        }
                    }
                }
            },
        )
    }

    /** The pane's child is dropped before the child manager rebuilds views (same hazard as SectionTabsFragment, #1544). */
    override fun onViewCreated(view: android.view.View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val stale = childFragmentManager.fragments.filter { it.id == R.id.network_pane }
        if (stale.isNotEmpty()) {
            childFragmentManager.beginTransaction().apply { stale.forEach { remove(it) } }.commitNowAllowingStateLoss()
        }
    }

    companion object {
        const val TAG_ROOT = "network:root"
        const val TAG_HOME = "network:home"
        const val TAG_WIRELESS = "network:wireless"
        const val TAG_PANE = "network:pane"
        private const val ARG_TAB = "tab"
        private const val STATE_TAB = "tab"

        /** The drawable [name] of this app, or the settings glyph when it does not resolve (never a crash). */
        internal fun drawable(ctx: android.content.Context, name: String): Int =
            ctx.resources.getIdentifier(name, "drawable", ctx.packageName).takeIf { it != 0 } ?: R.drawable.ic_settings

        fun newInstance(tab: String = NetworkNav.DEFAULT): NetworkNavFragment = NetworkNavFragment().apply {
            arguments = Bundle().apply { putString(ARG_TAB, tab) }
        }
    }
}

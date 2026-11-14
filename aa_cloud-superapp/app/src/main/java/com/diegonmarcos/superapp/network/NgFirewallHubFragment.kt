package com.diegonmarcos.superapp.network

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.launcher.GroupTitle
import com.diegonmarcos.superapp.launcher.Sections
import com.diegonmarcos.superapp.launcher.TILE_PAGE_PADDING
import com.diegonmarcos.superapp.launcher.TileGridFragment
import com.diegonmarcos.superapp.launcher.TileStrip
import com.diegonmarcos.superapp.ui.LauncherPalette
import com.diegonmarcos.superapp.uikit.KitComposeFragment

/**
 * #1737 The NG Firewall hub page: the network items as icons under the headings Connections (Wi-Fi, DNS, VPN), Firewalls
 * (Firewall, OS Perms) and Servers (Webserver, Proxy), per [NgFirewallHub.SECTIONS]. A plain page (the shell keeps its nav
 * and Back arrow); a tap goes through the shell's one dispatcher, exactly as the same tile on the Configs page does.
 */
class NgFirewallHubFragment : KitComposeFragment() {

    override fun palette() = LauncherPalette.kit(requireContext())

    @Composable
    override fun Content() {
        val ctx = LocalContext.current
        val sections = remember {
            NgFirewallHub.SECTIONS.map { sec ->
                sec.title to sec.items.map { Sections.AggTile(it.id, it.label, "", it.target, iconRes = drawable(ctx, it.icon)) }
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(TILE_PAGE_PADDING).testTag(TAG_ROOT)) {
            for ((title, tiles) in sections) {
                GroupTitle(title)
                TileStrip(tiles, { t -> (activity as? TileGridFragment.TileClickListener)?.onTileClicked(t) }, openFolder = {}, wrap = true)
            }
        }
    }

    companion object {
        const val TAG_ROOT = "ngfw-hub:root"

        /** The drawable [name] of this app, or the settings glyph when it does not resolve (never a crash). */
        internal fun drawable(ctx: android.content.Context, name: String): Int =
            ctx.resources.getIdentifier(name, "drawable", ctx.packageName).takeIf { it != 0 } ?: R.drawable.ic_settings

        fun newInstance(): NgFirewallHubFragment = NgFirewallHubFragment()
    }
}

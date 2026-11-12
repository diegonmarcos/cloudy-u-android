package com.diegonmarcos.cloudkeyboard

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.navbartop1.HierarchyEntry
import com.diegonmarcos.superapp.bottomnav.navEntry
import com.diegonmarcos.superapp.fleetconfigs.FleetConfigsEntries
import com.diegonmarcos.superapp.fleetconfigs.FleetConfigsPage
import com.diegonmarcos.superapp.navbarbottom.NavBarBottom
import com.diegonmarcos.superapp.navbartop1.NavBarTopFirstRowScaffold
import helium314.keyboard.settings.KbTab
import helium314.keyboard.settings.NavPageDecl
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.keyboardConfigsRows
import helium314.keyboard.latin.utils.AndroidKeyboardSettings

/**
 * #1651 the keyboard's settings chrome: the fleet DEFAULT top bar (NavBarTopFirstRowScaffold) and the fleet default bottom bar
 * (NavBarBottom) from libs:ui-nav-bars, around the existing settings pages. Registered in App.kt as KeyboardSettingsExtras.chrome.
 * #1653 the four bottom tabs are [KbTab] (Keyboard, Writer, Mediaboards, Textboards), their sub-pages the top bar dropdown; the top bar's Configs icon opens the standard Configs page (default tail + the
 * keyboard's own tab, see KeyboardConfigsTab), which reaches the grouped All settings (#1650).
 */
object KeyboardChrome {
    /**
     * #1653 the top bar's dropdown for [tab]: the tab (depth 0) then its sub-pages depth-first, [selectedId] on the path. Pure, so the
     * structure is testable without drawing.
     */
    fun hierarchy(tab: KbTab, selectedId: String?): List<HierarchyEntry> {
        val out = ArrayList<HierarchyEntry>()
        out += HierarchyEntry(tab.id, tab.label, 0, tab.id, selected = selectedId == null)
        fun walk(pages: List<NavPageDecl>, depth: Int) {
            for (p in pages) {
                out += HierarchyEntry(p.id, p.label, depth, tab.id, selected = p.flatten().any { it.id == selectedId })
                walk(p.children, depth + 1)
            }
        }
        walk(tab.pages, 1)
        return out
    }

    @Composable
    fun Draw(route: String?, open: (String) -> Unit, content: @Composable () -> Unit) {
        val ctx = LocalContext.current
        val tab = KbTab.forRoute(route)
        // the page the top bar shows: the one last picked while it still points at this route, else the first page of the route
        var picked by remember { mutableStateOf<String?>(null) }
        val pageId = tab?.allPages?.let { all -> all.firstOrNull { it.id == picked && it.route == route } ?: all.firstOrNull { it.route == route } }?.id
        NavBarTopFirstRowScaffold(
            title = tab?.label ?: "Cloud Keyboard",
            hierarchy = tab?.let { hierarchy(it, pageId) }.orEmpty(),
            onNavigate = { e ->
                val t = KbTab.byId(e.sectionId)
                if (e.depth == 0) t?.let { picked = null; open(it.route) }
                else t?.allPages?.firstOrNull { it.id == e.id }?.let { picked = it.id; open(it.route) }
            },
            onAccount = { },
            onConfigs = { openConfigs(ctx) },
            // #1652 the settings window is edge-to-edge: the status bar is cleared HERE, once (statusBarsPadding also consumes the
            // inset, so no page below pads for it again). The scaffold paints its underlay behind the band itself.
            modifier = Modifier.statusBarsPadding(),
        ) {
            Column {
                // the bottom bar owns the navigation-bar inset, so the pages above it do not pad for it a second time
                Box(Modifier.weight(1f).fillMaxWidth().consumeWindowInsets(WindowInsets.systemBars.only(WindowInsetsSides.Bottom))) { content() }
                NavBarBottom(
                    entries = KbTab.entries.map { navEntry(it.id, it.label, it.icon) },
                    selectedId = tab?.id,
                    onSelect = { e -> KbTab.byId(e.id)?.let { picked = null; open(it.route) } },
                    dark = true,
                )
            }
        }
    }

    private fun openConfigs(ctx: Context) {
        runCatching { FleetConfigsEntries.open(ctx, FleetConfigsPage.General) }
    }
}

/**
 * #1651 the keyboard's own tab of the standard Configs strip (before the `|`). #1665 it lists the former All settings pages whose
 * home is Configs ([keyboardConfigsRows]: Android keyboard settings, Update, About, this Configs page itself) and then the
 * grouped All settings, so every Configs-homed page is one tap away and nothing is reached only through the old list.
 */
@Composable
fun KeyboardConfigsTabBody(@Suppress("UNUSED_PARAMETER") dark: Boolean) {
    val ctx = LocalContext.current
    fun openAt(name: String) = runCatching {
        ctx.startActivity(Intent(ctx, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_OPEN_AT, name))
    }
    Column {
        keyboardConfigsRows.forEach { row ->
            val (label, action) = when (row) {
                "android_settings" -> "Android keyboard settings" to { runCatching { AndroidKeyboardSettings.launch(ctx) }; Unit }
                "update" -> "Update" to { openAt("update"); Unit }
                "about" -> "About Cloud Keyboard" to { openAt("about"); Unit }
                "host_extras" -> "General and About: the tabs of this Configs page" to { }
                else -> "All settings (Keyboard / Text / Configs)" to { openAt("all_settings"); Unit }
            }
            Text(label, Modifier.fillMaxWidth().clickable { action() }.padding(16.dp))
        }
    }
}

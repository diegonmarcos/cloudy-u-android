package com.diegonmarcos.cloudkeyboard

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.navbartop1.HierarchyEntry
import com.diegonmarcos.superapp.bottomnav.navEntry
import com.diegonmarcos.superapp.fleetconfigs.FleetConfigsEntries
import com.diegonmarcos.superapp.fleetconfigs.FleetConfigsPage
import com.diegonmarcos.superapp.navbarbottom.NavBarBottom
import com.diegonmarcos.superapp.navbartop1.NavBarTopFirstRowScaffold
import helium314.keyboard.settings.BottomNavTab
import helium314.keyboard.settings.SettingsActivity

/**
 * #1651 the keyboard's settings chrome: the fleet DEFAULT top bar (NavBarTopFirstRowScaffold) and the fleet default bottom bar
 * (NavBarBottom) from libs:ui-nav-bars, around the existing settings pages. Registered in App.kt as KeyboardSettingsExtras.chrome.
 * The bottom tabs are [BottomNavTab], in order; the top bar's Configs icon opens the standard Configs page (default tail + the
 * keyboard's own tab, see KeyboardConfigsTab), which reaches the grouped All settings (#1650).
 */
object KeyboardChrome {
    @Composable
    fun Draw(route: String?, open: (String) -> Unit, content: @Composable () -> Unit) {
        val ctx = LocalContext.current
        val tab = BottomNavTab.forRoute(route)
        NavBarTopFirstRowScaffold(
            title = tab?.label ?: "Cloud Keyboard",
            hierarchy = emptyList<HierarchyEntry>(),
            onNavigate = { },
            onAccount = { },
            onConfigs = { openConfigs(ctx) },
        ) {
            Column {
                Box(Modifier.weight(1f).fillMaxWidth()) { content() }
                NavBarBottom(
                    entries = BottomNavTab.entries.map { navEntry(it.id, it.label, it.icon) },
                    selectedId = tab?.id,
                    onSelect = { e -> BottomNavTab.entries.firstOrNull { it.id == e.id }?.let { open(it.route) } },
                    dark = true,
                )
            }
        }
    }

    private fun openConfigs(ctx: Context) {
        runCatching { FleetConfigsEntries.open(ctx, FleetConfigsPage.General) }
    }
}

/** #1651 the keyboard's own tab of the standard Configs strip (before the `|`): a door to the grouped All settings. */
@Composable
fun KeyboardConfigsTabBody(@Suppress("UNUSED_PARAMETER") dark: Boolean) {
    val ctx = LocalContext.current
    Column {
        Text(
            "All settings (Keyboard / Text / Configs)",
            Modifier.fillMaxWidth().clickable {
                ctx.startActivity(Intent(ctx, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_OPEN_AT, "all_settings"))
            }.padding(16.dp),
        )
    }
}

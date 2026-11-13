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
import com.diegonmarcos.superapp.navbartop2.NavBarTopSecondRow
import com.diegonmarcos.superapp.bottomnav.NavPage
import com.diegonmarcos.superapp.bottomnav.NavDecl
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

    /** #1698 THE nav declaration: build.json::ui baked by app/build.gradle, parsed by libs:bottomnav like every fleet app. */
    val decl: NavDecl by lazy {
        NavDecl.fromBuildConfig(BuildConfig.UI_SECTIONS_B64, BuildConfig.UI_BOTTOM_NAV, BuildConfig.UI_DEFAULT_SECTION)
    }

    /**
     * #1698 the fleet second top row(s) for section [tabId] with page [pageId] open, from [decl]: row 1 = the section's pages,
     * row 2 = the open page's own sub-pages (its overview first, under the page's id), and one more row per level below.
     */
    fun stripRows(decl: NavDecl, tabId: String, pageId: String?): List<Pair<List<NavPage>, String?>> {
        val pages = decl.section(tabId)?.pages.orEmpty()
        if (pages.isEmpty()) return emptyList()
        var top = pages.firstOrNull { p -> p.id == pageId || p.allIds().contains(pageId) } ?: pages.first()
        val rows = mutableListOf(pages.map { it.copy(pages = emptyList()) } to (top.id as String?))
        // one more row for every level the open page sits under (Writer > Configs Writer > Text enhancement > Draft Answer = 3 rows)
        while (top.pages.isNotEmpty()) {
            val sub = top.pages.firstOrNull { c -> c.id == pageId || c.allIds().contains(pageId) }
            rows += (listOf(top.copy(pages = emptyList())) + top.pages.map { it.copy(pages = emptyList()) }) to (sub?.id ?: top.id)
            top = sub ?: break
        }
        return rows
    }

    private fun NavPage.allIds(): List<String> = pages.flatMap { listOf(it.id) + it.allIds() }

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
            // #1483 every declared section (bar and off-bar) is a peer in the title dropdown: a user reaches every page from it
            sections = decl.sections,
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
                // #1698 the sub-pages are the fleet's own second top row (libs:ui-nav-bars NavBarTopSecondRow), the same strip
                // and look as every other app: one row for the tab's pages, one more for the open page's sub-pages.
                if (tab != null) stripRows(decl, tab.id, pageId).forEach { (pages, sel) ->
                    NavBarTopSecondRow(
                        pages = pages,
                        selectedId = sel,
                        onSelect = { np ->
                            tab.allPages.firstOrNull { it.id == np.id }?.let { picked = it.id; open(it.route) }
                        },
                        dark = true,
                        underTopChrome = false,
                    )
                }
                // the bottom bar owns the navigation-bar inset, so the pages above it do not pad for it a second time
                Box(Modifier.weight(1f).fillMaxWidth().consumeWindowInsets(WindowInsets.systemBars.only(WindowInsetsSides.Bottom))) { content() }
                NavBarBottom(
                    // an unreadable declaration (never expected) falls back to the id->route map rather than an empty bar
                    entries = decl.bottomSections().map { navEntry(it.id, it.label, it.icon) }
                        .ifEmpty { KbTab.values().map { navEntry(it.id, it.label, it.icon) } },
                    selectedId = tab?.id,
                    onSelect = { e -> KbTab.byId(e.id)?.let { picked = null; open(it.route) } }, // id -> route: libs:keyboard KbTab
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

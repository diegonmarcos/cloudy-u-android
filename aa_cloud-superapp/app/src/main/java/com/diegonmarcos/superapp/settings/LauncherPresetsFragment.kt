package com.diegonmarcos.superapp.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.ShellActivity
import com.diegonmarcos.superapp.ui.Haptics
import com.diegonmarcos.superapp.ui.LauncherPalette
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import com.diegonmarcos.superapp.uikit.KitSectionHeader
import com.diegonmarcos.superapp.uikit.KitSelectableTile
import com.diegonmarcos.superapp.uikit.KitTags

/**
 * Configs → Launcher → Presets (#574; the tab was Profiles).
 *
 * Things you PICK, in one tap, in the groups `build.json::ui.launcher_presets`
 * declares — this class names none of them:
 *
 *   profile → Sandboxes  (ui.launcher_profiles: Work · Personal · Guest)
 *   theme   → Themes     (ui.launcher_themes:   Cloud Purple · Cloud Minimalistic)
 *   mode    → Modes      (ui.launcher_modes:    Power · Focus · Saving Energy)
 *
 * The theme picker is the one that used to open the old "Modes" tab, moved
 * here whole: it calls [LauncherThemes.apply], not a copy of it, and keeps the
 * one behaviour that is easy to lose in a move — a theme legitimately recreates
 * the Activity (Android resolves a Material3 style once, at inflate time), so it
 * tells the shell; a sandbox or a mode names no style and does not.
 *
 * Every group is re-read from the store when a pick lands, so a tap repaints
 * its own tiles in place and the scroll position stays put (#349). Compose
 * since #773: the page is [page], a model rebuilt by [repaintAll]; the tiles
 * are libs:ui-kit's, coloured by the launcher palette.
 *
 * Nothing persisted moved: LauncherProfilePrefs / LauncherThemePrefs /
 * LauncherModePrefs key off fixed store names, never off the page id.
 */
class LauncherPresetsFragment : KitComposeFragment() {

    /** One pickable row: what the tile says, and what a tap on it does. */
    internal class Tile(val id: String, val label: String, val subtitle: String,
                        val selected: Boolean, val pick: () -> Unit)

    /** The page: each declared group with its tiles, as last read from the store. */
    internal var page by mutableStateOf<List<Pair<LauncherPresets.Group, List<Tile>>>>(emptyList())
        private set

    override fun palette() = LauncherPalette.kit(requireContext())

    /** #1506 The arm prompt Super Saving raises from its worker thread. */
    private val dialogs = com.diegonmarcos.superapp.bgpolicy.BgDialogs()

    override fun onCreateView(inf: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        repaintAll()
        return super.onCreateView(inf, c, s)
    }

    @Composable
    override fun Content() {
        val view = LocalView.current
        com.diegonmarcos.superapp.bgpolicy.BgDialogHost(dialogs)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            page.forEachIndexed { i, (group, tiles) ->
                if (i > 0) Spacer(Modifier.height(24.dp))
                KitSectionHeader(group.label, group.subtitle, Modifier.testTag(KitTags.header(group.id)))
                for (t in tiles) {
                    KitSelectableTile(
                        t.label, t.subtitle, t.selected,
                        onClick = { Haptics.tap(view); t.pick() },
                        modifier = Modifier.testTag(KitTags.tile("${group.id}:${t.id}")),
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }

    /** Re-read every group from the store; the composition repaints what changed. */
    internal fun repaintAll() {
        page = LauncherPresets.groups().map { group ->
            group to when (group.kind) {
                LauncherPresets.KIND_PROFILE -> fillSandboxes()
                LauncherPresets.KIND_THEME   -> fillThemes()
                LauncherPresets.KIND_MODE    -> fillModes()
                else -> emptyList()
            }
        }
    }

    private fun fillSandboxes(): List<Tile> {
        val ctx = requireContext()
        val current = LauncherProfilePrefs(ctx).profile
        return LauncherProfiles.loadFromBuildConfig().map { row ->
            Tile(row.id, row.label, row.subtitle, row.id == current.id) {
                LauncherProfilePrefs(ctx).profile = LauncherProfile.fromId(row.id)
                repaintAll()
            }
        }
    }

    private fun fillThemes(): List<Tile> {
        val ctx = requireContext()
        val current = LauncherThemePrefs(ctx).theme
        // Re-read, never captured: "· modified" is derived from the switches, so
        // a hand-flip on the Controls tab has to show here on the next fill.
        val modified = LauncherThemes.isModified(ctx, current)
        return LauncherThemes.loadFromBuildConfig().map { row ->
            val isCurrent = row.id == current.id
            Tile(
                row.id,
                label = row.label + if (isCurrent && modified) "  ·  modified" else "",
                subtitle = if (isCurrent && modified)
                    "You changed a switch by hand, so this is no longer exactly " +
                        "${row.label}. Tap to re-apply it."
                else row.subtitle,
                selected = isCurrent,
            ) {
                // ONE action, both effects: chrome + every toggle the theme declares.
                LauncherThemes.apply(ctx, LauncherTheme.fromId(row.id))
                // The one pick here that legitimately recreates the Activity.
                (activity as? ShellActivity)?.notifyLauncherThemeChanged()
                com.diegonmarcos.superapp.appstore.ConstellationWorker.start(ctx)
            }
        }
    }

    private fun fillModes(): List<Tile> {
        val ctx = requireContext()
        val selected = LauncherModePrefs(ctx).selected
        return LauncherModes.load().map { mode ->
            if (mode.engine == com.diegonmarcos.superapp.bgpolicy.SuperSavingRuntime.ENGINE) return@map superSavingTile(mode)
            val isCurrent = mode.id == selected
            val modified = isCurrent && LauncherModes.isModified(ctx, mode)
            Tile(
                mode.id,
                label = mode.label + if (modified) "  ·  modified" else "",
                subtitle = if (modified)
                    "You changed a switch by hand, so this is no longer exactly " +
                        "${mode.label}. Tap to re-apply it."
                else mode.subtitle,
                selected = isCurrent,
            ) {
                LauncherModes.apply(ctx, mode)
                // The switches moved under the shell's chrome — same follow-up a
                // flip on the Controls tab does, minus the Activity recreate.
                (activity as? ShellActivity)?.applyLauncherChrome()
                applyShellLiveToggles(activity)
                com.diegonmarcos.superapp.appstore.ConstellationWorker.start(ctx)
                repaintAll()
            }
        }
    }

    /** #1506 The Super Saving tile: ● while the mode is on; a tap turns it on (snapshot, then every step) or, when on, off (restore the snapshot). */
    private fun superSavingTile(mode: LauncherModes.Mode): Tile {
        val ctx = requireContext().applicationContext
        val on = com.diegonmarcos.superapp.bgpolicy.SuperSavingRuntime.active(ctx)
        return Tile(mode.id, mode.label + if (on) "  ·  on, tap to turn off" else "", mode.subtitle, on) {
            val act = requireActivity()
            Thread({
                try {
                    val rt = com.diegonmarcos.superapp.bgpolicy.SuperSavingRuntime
                    val text = if (rt.active(ctx)) {
                        val sh = com.diegonmarcos.superapp.bgpolicy.BgUi.acquire(act, dialogs, withoutAdb = true)
                        if (!sh.proceed) "Not changed: Off needs the ADB shell for the steps that used it." else "Off: " + rt.summary(rt.turnOff(ctx, sh.shell))
                    } else {
                        val sh = com.diegonmarcos.superapp.bgpolicy.BgUi.acquire(act, dialogs, withoutAdb = true)
                        if (!sh.proceed) "Not turned on." else "On: " + rt.summary(rt.turnOn(ctx, sh.shell))
                    }
                    com.diegonmarcos.superapp.bgpolicy.BgUi.toast(act, text)
                } finally { act.runOnUiThread { repaintAll() } }
            }, "super-saving").start()
        }
    }

    companion object { fun newInstance() = LauncherPresetsFragment() }
}

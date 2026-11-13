package com.diegonmarcos.superapp.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
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
import com.diegonmarcos.superapp.shellclient.ShellStatus
import com.diegonmarcos.superapp.ui.Haptics
import com.diegonmarcos.superapp.ui.LauncherPalette
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import com.diegonmarcos.superapp.uikit.KitSectionHeader
import com.diegonmarcos.superapp.uikit.KitSelectableTile
import com.diegonmarcos.superapp.uikit.KitTags
import com.diegonmarcos.superapp.uikit.LocalKitPalette

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

    /** One pickable row: what the tile says, and what a tap on it does. [cmd] = the preset declares a shell command (#1591). */
    internal class Tile(val id: String, val label: String, val subtitle: String,
                        val selected: Boolean, val cmd: Boolean = false, val pick: () -> Unit)

    /**
     * #1591 The status checker of the presets' shell commands: the adb-shell broker's LIVE status ([ShellStatus.armed] is a real
     * `id -u` round trip, the same one the ADB Shell page shows), read on resume and after a preset ran its command. null = not
     * read yet. A command-bearing preset is "runnable" only while this says armed.
     */
    internal var shell by mutableStateOf<ShellStatus?>(null)
        private set

    /** Where the broker status is read; a test stands a fake here. */
    internal var readShell: (android.content.Context) -> ShellStatus = { com.diegonmarcos.superapp.shellclient.ShellClient.status(it) }

    /** Re-read the broker (off the UI thread) when a preset carries a command; nothing is read on a page with none. */
    internal fun checkShell() {
        if (!isAdded || LauncherModes.load().none { it.cmd.isNotBlank() }) return
        val app = requireContext().applicationContext
        val act = requireActivity()
        Thread({
            val st = runCatching { readShell(app) }.getOrNull() ?: return@Thread
            act.runOnUiThread { shell = st }
        }, "preset-cmd-check").apply { isDaemon = true }.start()
    }

    override fun onResume() {
        super.onResume()
        checkShell()
    }

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
                    if (t.cmd) PresetCmdLine(PresetCmdCheck.of(true, shell), Modifier.testTag("preset-cmd:${t.id}")) {
                        (activity as? ShellActivity)?.openSectionPage("config", "adb-shell")
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }

    /** Re-read every group from the store; the composition repaints what changed. */
    internal fun repaintAll() {
        // #1506 A worker (Super Saving, line 187) and other deferred callbacks post this back to the UI
        // thread; by the time it runs the fragment may be detached. The fillers below call requireContext(),
        // which then throws IllegalStateException "not attached to a context" and crashes the app. Bail when
        // the fragment is gone — there is nothing to repaint.
        if (!isAdded) return
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
        return LauncherModes.load().mapNotNull { mode ->
            if (mode.engine == com.diegonmarcos.superapp.bgpolicy.SuperSavingRuntime.ENGINE) {
                // Yields to the system's extreme saver: hidden unless it is on (so it can still be restored).
                val sys = com.diegonmarcos.superapp.bgpolicy.SystemSaver.extreme(ctx)
                return@mapNotNull if (sys && !com.diegonmarcos.superapp.bgpolicy.SuperSavingRuntime.active(ctx.applicationContext)) null else superSavingTile(mode)
            }
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
                cmd = mode.cmd.isNotBlank(),
            ) {
                LauncherModes.apply(ctx, mode)
                // The switches moved under the shell's chrome — same follow-up a
                // flip on the Controls tab does, minus the Activity recreate.
                (activity as? ShellActivity)?.applyLauncherChrome()
                applyShellLiveToggles(activity)
                // #1591 a preset can trigger a shell action: run its command through the adb-shell broker.
                if (mode.cmd.isNotBlank()) runPresetCmd(mode)
                com.diegonmarcos.superapp.appstore.ConstellationWorker.start(ctx)
                repaintAll()
            }
        }
    }

    /**
     * #1591 A preset that declares a `cmd` runs it through the adb-shell broker
     * (ShellClient.execOut — the same privileged channel X-Ray and Super Saving
     * use), off the UI thread, and toasts whether the shell carried it. No engine
     * of its own: the broker is the one door, a no-op when no channel is armed.
     */
    private fun runPresetCmd(mode: LauncherModes.Mode) {
        val app = requireContext().applicationContext
        val act = requireActivity()
        Thread({
            val out = (com.diegonmarcos.superapp.shellclient.ShellClient.execOut(app, mode.cmd, 60_000L) as? com.diegonmarcos.superapp.shellclient.ShellResult.Ok)?.stdout
            com.diegonmarcos.superapp.bgpolicy.BgUi.toast(
                act,
                if (out != null) "${mode.label}: ran its action" else "${mode.label}: shell not ready",
            )
            // The checker under the tile says what the broker says now, after this run.
            runCatching { com.diegonmarcos.superapp.shellclient.ShellClient.invalidate(app) }
            act.runOnUiThread { checkShell() }
        }, "preset-cmd").start()
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

/**
 * #1591 Whether a preset's shell command would be carried right now, from the adb-shell broker's live status and nothing else:
 * RUNNABLE only when a privileged channel answered its round trip ([ShellStatus.armed]); NOT_ARMED says why it would be a no-op.
 */
internal object PresetCmdCheck {
    enum class State { NONE, CHECKING, RUNNABLE, NOT_ARMED }
    data class Verdict(val state: State, val line: String)

    fun of(hasCmd: Boolean, status: ShellStatus?): Verdict = when {
        !hasCmd -> Verdict(State.NONE, "")
        status == null -> Verdict(State.CHECKING, "Shell action: checking the ADB shell...")
        status.armed -> Verdict(State.RUNNABLE, "Shell action: runnable · ADB shell up via ${status.channel}")
        else -> Verdict(State.NOT_ARMED, "Shell action: will not run · ADB shell down - " +
            status.reason.ifBlank { status.detail }.ifBlank { "unknown" } + " · tap to open ADB Shell")
    }
}

/** The checker's line under a command-bearing preset: a light and the verdict; while not armed, a tap opens the ADB Shell page. */
@Composable
private fun PresetCmdLine(v: PresetCmdCheck.Verdict, modifier: Modifier = Modifier, openAdbShell: () -> Unit) {
    val p = LocalKitPalette.current
    val ink = when (v.state) {
        PresetCmdCheck.State.RUNNABLE -> p.ok
        PresetCmdCheck.State.NOT_ARMED -> p.warn
        else -> p.textSecondary
    }
    Text("● " + v.line, color = ink, style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
        modifier = modifier.fillMaxWidth()
            .clickable(enabled = v.state == PresetCmdCheck.State.NOT_ARMED, onClick = openAdbShell)
            .padding(horizontal = 16.dp, vertical = 4.dp))
}

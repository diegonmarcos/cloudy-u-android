package com.diegonmarcos.superapp.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
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
import com.diegonmarcos.superapp.uikit.KitStoreQuick
import com.diegonmarcos.superapp.uikit.KitStoreRow
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
                        val selected: Boolean, val cmd: Boolean = false,
                        /** #1720 a mode tile that is the active one: it wears the Off / Re-apply row. */
                        val activeMode: Boolean = false, val pick: () -> Unit)

    /** #1720 The last mode operation (each step with its state and reason) and the restores still pending, as last read from the store. */
    internal var modeReport by mutableStateOf<ModeReport?>(null)
        private set
    internal var pendingModes by mutableStateOf<Map<String, List<String>>>(emptyMap())
        private set
    /** A mode operation is running on its worker: taps on mode tiles wait for it. */
    internal var busy by mutableStateOf(false)
        private set
    private var allSteps by mutableStateOf(false)
    private var unobserve: (() -> Unit)? = null

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
        // #1720 read for every page that draws a mode: the ADB status line at the top says what the modes can do right now
        if (!isAdded || LauncherModes.load().isEmpty()) return
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
        repaintAll() // a mode changed from the Notify badge or the notification while this page was in the back
    }

    override fun onStart() {
        super.onStart()
        // #1720 the single state source tells every surface: a change made elsewhere repaints these tiles in place
        unobserve = PresetState.observe { activity?.runOnUiThread { repaintAll() } }
    }

    override fun onStop() {
        unobserve?.invoke(); unobserve = null
        super.onStop()
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
            // #1720 the modes run through the ADB shell: its real state (a live round trip through the ADB owner) leads the page
            AdbStatusLine(shell, Modifier.testTag(TAG_ADB), recheck = { checkShell() }) {
                com.diegonmarcos.superapp.system.AdbReadiness.openAdbShell(requireContext())
            }
            Spacer(Modifier.height(16.dp))
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
                        com.diegonmarcos.superapp.system.AdbReadiness.openAdbShell(requireContext())
                    }
                    if (t.activeMode) ModeActions(busy, off = { modeOff(force = false) }, reapply = { modePick(t.id) })
                    Spacer(Modifier.height(8.dp))
                }
                if (group.kind == LauncherPresets.KIND_MODE) {
                    ModeReportPanel(modeReport, allSteps, { allSteps = !allSteps }, leave = { modeOff(force = true) })
                    PendingPanel(pendingModes, labelOf = { id -> LauncherModes.load().firstOrNull { it.id == id }?.label ?: id },
                        retry = { id -> requireContext().applicationContext.let { app -> modeRun("Not changed: the restore needs the ADB shell.") { sh -> PresetModes.retryPending(app, id, sh) } } },
                        forget = { id -> requireContext().applicationContext.let { app -> PresetModes.async { PresetModes.forgetPending(app, id); activity?.runOnUiThread { repaintAll() } } } })
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
        runCatching {
            val app = requireContext().applicationContext
            modeReport = PresetModes.report(app)
            pendingModes = PresetModes.pending(app)
        }
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
                PresetState.setSandbox(ctx, row.id) // #1720 the one state source: the store holds one sandbox, the badge follows
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
                PresetState.changed(ctx) // #1720 the Notify badge shows the theme too
                // The one pick here that legitimately recreates the Activity.
                (activity as? ShellActivity)?.notifyLauncherThemeChanged()
                com.diegonmarcos.superapp.appstore.ConstellationWorker.start(ctx)
            }
        }
    }

    private fun fillModes(): List<Tile> {
        val ctx = requireContext()
        // #1720 ONE source for "which mode is on": PresetState. Exactly one tile (or none) can be selected, Super Saving included.
        val selected = PresetState.mode(ctx)
        return LauncherModes.load().mapNotNull { mode ->
            val isCurrent = mode.id == selected
            if (mode.engine == com.diegonmarcos.superapp.bgpolicy.SuperSavingRuntime.ENGINE) {
                // Yields to the system's extreme saver: hidden unless it is on (so it can still be restored).
                val sys = com.diegonmarcos.superapp.bgpolicy.SystemSaver.extreme(ctx)
                return@mapNotNull if (sys && !isCurrent) null else superSavingTile(mode, isCurrent)
            }
            val modified = isCurrent && LauncherModes.isModified(ctx, mode)
            Tile(
                mode.id,
                label = mode.label + if (modified) "  ·  modified" else if (isCurrent) "  ·  on, tap to turn off" else "",
                subtitle = if (modified)
                    "You changed a switch by hand, so this is no longer exactly " +
                        "${mode.label}. Tap to re-apply it."
                else mode.subtitle,
                selected = isCurrent,
                cmd = mode.cmd.isNotBlank(),
                activeMode = isCurrent,
            ) {
                // picked while on and unchanged = Off; picked while modified = re-apply; picked while another is on = switch
                if (isCurrent && !modified) modeOff(force = false) else modePick(mode.id)
            }
        }
    }

    /**
     * #1720 Pick a mode through the one switch ([PresetModes.select]): the mode that was on is restored first, then this one is
     * applied (or re-applied when it is already on). On its own thread; the page repaints from the store when it is done.
     */
    private fun modePick(id: String) {
        val mode = LauncherModes.load().firstOrNull { it.id == id } ?: return
        val app = requireContext().applicationContext // read here, on the UI thread: the worker must not ask a detached fragment
        modeRun("Not turned on.", after = {
            // #1591 a preset can trigger a shell action: run its command through the adb-shell broker.
            if (mode.cmd.isNotBlank() && PresetState.mode(app) == id) runPresetCmd(mode)
            com.diegonmarcos.superapp.appstore.ConstellationWorker.start(app)
        }) { sh -> PresetModes.select(app, id, sh) }
    }

    /** #1720 Off of the active mode: restore its journal. [force] = leave the steps that cannot be restored as pending. */
    private fun modeOff(force: Boolean) {
        val app = requireContext().applicationContext
        modeRun("Not changed: Off needs the ADB shell for the steps that used it.") { sh -> PresetModes.off(app, sh, force) }
    }

    /** [cancelled] = what the toast says when the owner backs out of the ADB prompt. */
    private fun modeRun(cancelled: String, after: () -> Unit = {}, op: (com.diegonmarcos.superapp.bgpolicy.BgShell?) -> ModeSwitch.Outcome) {
        if (busy || !isAdded) return
        busy = true
        val act = requireActivity()
        Thread({
            try {
                val sh = com.diegonmarcos.superapp.bgpolicy.BgUi.acquire(act, dialogs, withoutAdb = true, title = GATE_TITLE, message = GATE_MESSAGE)
                val text = if (!sh.proceed) cancelled else runCatching { op(sh.shell).message }.getOrElse { "Not changed: ${it.message ?: it.javaClass.simpleName}" }
                com.diegonmarcos.superapp.bgpolicy.BgUi.toast(act, text)
            } finally {
                act.runOnUiThread {
                    busy = false
                    if (isAdded) {
                        // The switches moved under the shell's chrome — same follow-up a
                        // flip on the Controls tab does, minus the Activity recreate.
                        (activity as? ShellActivity)?.applyLauncherChrome()
                        applyShellLiveToggles(activity)
                        runCatching(after)
                        repaintAll()
                        checkShell()
                    }
                }
            }
        }, "preset-mode").start()
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
    private fun superSavingTile(mode: LauncherModes.Mode, on: Boolean): Tile =
        Tile(mode.id, mode.label + if (on) "  ·  on, tap to turn off" else "", mode.subtitle, on, activeMode = on) {
            // #1720 the same switch as every mode: On restores the mode that was on first, Off restores this one's journal
            if (on) modeOff(force = false) else modePick(mode.id)
        }

    companion object {
        fun newInstance() = LauncherPresetsFragment()
        const val TAG_ADB = "presets-adb-status"
        const val GATE_TITLE = "This mode needs the ADB shell"
        const val GATE_MESSAGE = "Most steps of a mode run through the ADB shell and it is not connected. Re-arm it on the ADB Shell page " +
            "(Cloud Webserver), turn on wireless debugging, or continue without it: the steps that need the shell are then skipped and listed with that reason."
    }
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

/**
 * #1720 The ADB shell status line at the top of Presets: the broker's LIVE verdict ([ShellStatus.line]: a real `id -u` round
 * trip through the ADB owner, with the channel that answered and the time of the check), never a remembered flag. Check
 * runs the round trip again; ADB Shell opens the ADB owner's ADB Shell page (Cloud Webserver).
 */
@Composable
private fun AdbStatusLine(st: ShellStatus?, modifier: Modifier = Modifier, recheck: () -> Unit, open: () -> Unit) {
    val p = LocalKitPalette.current
    val (ink, text) = AdbStatusText.of(st).let { (s, t) -> (when (s) { AdbStatusText.UP -> p.ok; AdbStatusText.DOWN -> p.warn; else -> p.textSecondary }) to t }
    Row(modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text("● $text", color = ink, style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f).padding(vertical = 4.dp).testTag("presets-adb-text"))
        Spacer(Modifier.width(8.dp))
        KitStoreQuick("Check", KitStoreRow.RULE, Modifier.testTag("presets-adb-recheck"), recheck)
        Spacer(Modifier.width(8.dp))
        KitStoreQuick("ADB Shell", KitStoreRow.BLUE_FILL, Modifier.testTag("presets-adb-open"), open)
    }
}

/** #1720 Pure: the status line's words and state from the broker status (null = not read yet). */
internal object AdbStatusText {
    const val UP = "up"; const val DOWN = "down"; const val CHECKING = "checking"
    fun of(st: ShellStatus?): Pair<String, String> = when {
        st == null -> CHECKING to "ADB shell: checking (a live round trip through the ADB owner)..."
        st.armed -> UP to st.line() + " · modes can run every step"
        else -> DOWN to st.line() + " · the steps that need the shell will be skipped"
    }
}

/** #1720 The row under the active mode: Off (restore its journal) and Re-apply (idempotent). */
@Composable
private fun ModeActions(busy: Boolean, off: () -> Unit, reapply: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 4.dp)) {
        KitStoreQuick(if (busy) "Working..." else "Off", KitStoreRow.AMBER_FILL, Modifier.testTag("mode-off"), if (busy) null else off)
        Spacer(Modifier.width(8.dp))
        KitStoreQuick("Re-apply", KitStoreRow.BLUE_FILL, Modifier.testTag("mode-reapply"), if (busy) null else reapply)
    }
}

/**
 * #1720 What the last mode change did, step by step: every step that did not take with its reason (always shown), the skipped
 * ones with theirs and the applied ones on demand. When an Off left steps unrestored the mode stays on; "Leave as is" parks them.
 */
@Composable
private fun ModeReportPanel(r: ModeReport?, all: Boolean, toggleAll: () -> Unit, leave: () -> Unit) {
    if (r == null) return
    val p = LocalKitPalette.current
    val small = androidx.compose.material3.MaterialTheme.typography.bodySmall
    val rows = r.restored + r.steps
    val failed = rows.filter { it.state == com.diegonmarcos.superapp.bgpolicy.SsState.FAILED }
    val rest = rows.filter { it.state != com.diegonmarcos.superapp.bgpolicy.SsState.FAILED }
    val at = if (r.at > 0) java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(r.at)) else ""
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).testTag("mode-report")) {
        Text("Last change $at · ${r.message}", color = if (r.ok) p.textSecondary else p.warn, style = small, modifier = Modifier.testTag("mode-report-head"))
        for (f in failed) Text("● ${f.label}: did not take - ${f.note.ifBlank { "no reason given" }}", color = p.warn, style = small,
            modifier = Modifier.padding(start = 12.dp, top = 2.dp).testTag("mode-step-failed:${f.id}"))
        if (all) for (x in rest) Text(
            (if (x.state == com.diegonmarcos.superapp.bgpolicy.SsState.APPLIED) "✓ ${x.label}: done" else "○ ${x.label}: skipped") + if (x.note.isBlank()) "" else " - ${x.note}",
            color = p.textSecondary, style = small, modifier = Modifier.padding(start = 12.dp, top = 2.dp).testTag("mode-step:${x.id}"))
        Row(Modifier.padding(top = 6.dp)) {
            if (rest.isNotEmpty()) KitStoreQuick(if (all) "Hide steps" else "All ${rows.size} steps", KitStoreRow.RULE, Modifier.testTag("mode-report-all"), toggleAll)
            if (!r.ok && r.op == "off" && r.mode.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                KitStoreQuick("Leave as is", KitStoreRow.AMBER_FILL, Modifier.testTag("mode-leave"), leave)
            }
        }
    }
}

/** #1720 Restores that could not run when their mode was left (shell down, a step refused): kept, shown, retried on a tap. */
@Composable
private fun PendingPanel(pending: Map<String, List<String>>, labelOf: (String) -> String, retry: (String) -> Unit, forget: (String) -> Unit) {
    if (pending.isEmpty()) return
    val p = LocalKitPalette.current
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).testTag("mode-pending")) {
        for ((id, steps) in pending) {
            Text("● Restore pending · ${labelOf(id)}: ${steps.joinToString()}", color = p.warn,
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("mode-pending:$id"))
            Row(Modifier.padding(top = 4.dp, bottom = 4.dp)) {
                KitStoreQuick("Retry restore", KitStoreRow.BLUE_FILL, Modifier.testTag("mode-pending-retry:$id")) { retry(id) }
                Spacer(Modifier.width(8.dp))
                KitStoreQuick("Forget", KitStoreRow.RULE, Modifier.testTag("mode-pending-forget:$id")) { forget(id) }
            }
        }
    }
}

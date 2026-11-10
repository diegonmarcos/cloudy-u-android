package com.diegonmarcos.superapp.settings
import com.diegonmarcos.superapp.BuildConfig
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.system.SystemDisplay
import com.diegonmarcos.superapp.configs.DeviceControls
import com.diegonmarcos.superapp.launcher.Sections
import com.diegonmarcos.superapp.ui.Haptics
import com.diegonmarcos.superapp.ui.LauncherPalette
import com.diegonmarcos.superapp.ui.StatusLight
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import com.diegonmarcos.superapp.ShellActivity

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Base64
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentContainerView
import org.json.JSONArray

/**
 * Configs → Launcher → Controls (tab 2 of three: Presets · Controls · One-Hand).
 *
 * The ONE producer of the Controls tab (#574). It draws, top to bottom: the
 * device/fleet switch board — [com.diegonmarcos.superapp.configs.ControlFragment],
 * the Samsung-style icon grid that used to be Configs ▸ Panel ▸ Control, hosted
 * here as a child fragment so its ticker, lights and tap/hold are untouched —
 * then the launcher's own screensaver, switches, sliders and the
 * set-as-default-launcher call-to-action.
 *
 * What used to open this page — the theme picker — is Presets ▸ Themes now
 * ([LauncherPresetsFragment]); it calls the same [LauncherThemes.apply], so the
 * engine was moved, not copied. The tab was called "Modes" back when a theme
 * was called a mode; the name Modes now belongs to Presets ▸ Modes.
 */
class LauncherConfigFragment : KitComposeFragment() {

    /**
     * The redraw key of every row that can repaint ITSELF from the store.
     *
     * #349: every control here used to answer a flip with [rerenderPage] — a
     * detach+attach of the whole fragment — to change one badge and one derived
     * label. That rebuilt twenty-four tiles and two pickers, and threw the
     * scroll position away on every tap.
     *
     * A row reads the store when it is drawn and takes this epoch as an input,
     * so a bump re-asks every one of them and nothing cached in a captured
     * `val` can go stale: the store is the only state, and the scroll position
     * lives in the page, which a bump does not touch.
     */
    private var repaintEpoch by mutableIntStateOf(0)

    /** Every row re-reads the store and repaints. The whole of what a flip now costs. */
    private fun repaintFromStore() { repaintEpoch++ }

    override fun palette() = LauncherPalette.kit(requireContext())

    @Composable
    override fun Content() {
        val ctx = requireContext()
        val epoch = repaintEpoch

        // Configs ▸ Launcher ▸ Controls ▸ Panels is four tabs over this ONE producer: UI (UI controls only: screensaver,
        // icons per row, brightness, Cloud-superapp Scale / Font Size / Zoom Size), Battery Hungers (the battery-effect
        // switches, the heaviest consumers, the Battery Hunger panel), Tools (the device switch board, Connectivity,
        // Notifications, Others, default-launcher action) and All = UI + Battery Hungers + Tools in that order.
        val scope = arguments?.getString(ARG_SCOPE).orEmpty()
        val all = scope.isEmpty() || scope == SCOPE_ALL
        val showUi = all || scope == SCOPE_UI
        val showBattery = all || scope == SCOPE_BATTERY
        val showTools = all || scope == SCOPE_TOOLS

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            val settingsPrefs = remember { LauncherSettingsPrefs(ctx) }

            if (showUi) {
                // ── Screensaver section ────────────────────────────────────
                GapDp(24)
                SectionHeaderUi("Screensaver",
                    "Which screensaver the floating-nav ‘Screensaver’ action shows, and the idle auto-start timer.")
                // Same shape as the mode tiles above: the ● / ○ is derived from the
                // store, so the box is repainted rather than the page rebuilt.
                val currentSaver = epoch.let { settingsPrefs.screensaver }
                for (saver in LauncherSettingsPrefs.Config.screensavers) {
                    GenericTileUi(saver.label, saver.subtitle, saver.id == currentSaver) {
                        settingsPrefs.screensaver = saver.id
                        repaintFromStore()
                    }
                    GapDp(8)
                }
                // Idle auto-start timer (seconds; 0 = never).
                val st = LauncherSettingsPrefs.Config.screensaverTimeout
                sliderRow(st.label, st.subtitle, st.min, st.max,
                    settingsPrefs.screensaverTimeout) { v -> settingsPrefs.screensaverTimeout = v }

                // ── Icons per row ──────────────────────────────────────────
                // Two separate steppers, one per grid family. Neither writes until it is
                // tapped, so a phone that never touches them keeps following the
                // shipped defaults (see GridColumns). The grids redraw themselves when
                // the stored value changes.
                GapDp(24)
                SectionHeaderUi("Icons per row",
                    "How many icons the Cloud apps and Phone apps grids fit on a line (${GridColumns.MIN}–${GridColumns.MAX}).")
                stepperRow("Cloud apps", "Cloud grids",
                    GridColumns.cloud(ctx)) { GridColumns.set(ctx, GridColumns.Kind.CLOUD, it) }
                GapDp(8)
                stepperRow("Phone apps", "Phone grids",
                    GridColumns.phone(ctx)) { GridColumns.set(ctx, GridColumns.Kind.PHONE, it) }
                GapDp(8)

                // Screen brightness (device-wide → needs WRITE_SETTINGS)
                val b = LauncherSettingsPrefs.Config.brightness
                sliderRow(b.label, b.subtitle, b.min, b.max,
                    settingsPrefs.brightness.let { if (it < b.min) b.min else it }) { v ->
                    settingsPrefs.brightness = v
                    applySystemBrightness(ctx, v)
                }

                // Cloud-superapp Scale (ruler centred on 411), Font Size, Zoom Size: live status + privileged write.
                DisplayControlsSection()

                // #1621 Background: enable, star animation, the 3D/4D polytope shape picker and auto-cycle.
                BackgroundSection(settingsPrefs, epoch)
            }

            // ── Battery Hungers: the effect switches, the heaviest consumers, the Battery Hunger panel ──
            if (showBattery) {
                toggleGroups("battery", settingsPrefs, epoch)
                GapDp(24)
                // Per-app mAh per charge cycle / month, resolved to app names (BatteryRepository; needs the ADB channel for mAh).
                com.diegonmarcos.superapp.launcher.BatteryUsageBody()
                // #1506 Battery Hunger: drainers since the last charge + the Background policy (on demand, needs the ADB shell).
                com.diegonmarcos.superapp.bgpolicy.BatteryHungerPanel(requireActivity())
            }

            // ── Tools: the device switch board, Connectivity, Notifications, Others, default launcher ──
            if (showTools) {
                // The device switch board. ControlFragment is embedded, not re-implemented: one implementation of every
                // tile, one place its behaviour is asserted. It is committed only when nothing is already attached at
                // the host — on a restore the FragmentManager re-binds the existing child to the same stable id.
                ControlsGridHost()
                toggleGroups("tools", settingsPrefs, epoch)
                val p = LocalKitPalette.current
                val view = LocalView.current
                GapDp(16)
                Text(if (isDefaultLauncher(ctx))
                    "✓ SuperApp is the active default launcher."
                else
                    "SuperApp is NOT the default launcher yet.",
                    color = p.textPrimary, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 8.dp))
                Text("Set as default launcher →", color = p.accent, style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.fillMaxWidth().background(p.surfaceSelected).clickable {
                        Haptics.tap(view)
                        runCatching {
                            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
                        }.onFailure {
                            runCatching {
                                startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
                            }
                        }
                    }.padding(12.dp).testTag("launcher:default-cta"))
            }
        }
    }

    /** The switch boxes declared for [tab] (`battery` / `tools`), in declared order; Scale rides the Others box. */
    @Composable
    private fun toggleGroups(tab: String, settingsPrefs: LauncherSettingsPrefs, epoch: Int) {
        val ctx = requireContext()
        // ── Toggles ────────────────────────────────────────────────
        // One box per build.json::ui.launcher_settings.toggle_groups entry, in
        // declared order, holding the toggles that name it. This replaced a
        // `filter { it.battery }` / `filter { !it.battery }` split, which could
        // express exactly two boxes and no more — the four the owner asked for
        // were a Kotlin change under that shape and are a data change under this
        // one. A group with no toggles draws nothing rather than an empty header.
        val allToggles = LauncherSettingsPrefs.Config.toggles
        for (group in LauncherSettingsPrefs.Config.groups.filter { it.tab == tab }) {
            val rows = allToggles.filter { it.group == group.id }
            if (rows.isEmpty()) continue

            GapDp(24)
            SectionHeaderUi(group.label, group.subtitle)

            if (group.master) {
                // The master's own position is DERIVED from its children, so it
                // is passed as a question, not an answer: flipping one child
                // below has to move this switch, and it re-asks instead of
                // being told.
                toggleRow(group.masterLabel, group.masterSubtitle, epoch,
                    { rows.all { settingsPrefs.toggle(it) } }) { on ->
                    // A master must not stomp a user-owned switch either: the
                    // edge menus are not the master's to turn off.
                    rows.filterNot { it.userOwned }.forEach { settingsPrefs.setToggle(it, on) }
                    onToggleChanged()
                    repaintFromStore() // reflect the child switches
                }
                GapDp(8)
            }

            toggleGrid(rows, settingsPrefs, epoch) { t, on ->
                // Dark mode is a device-wide setting written over the shell
                // channel, so it is the one switch that must not be flipped
                // on this thread — and the one that has to say so when
                // there is no channel, instead of lighting up and doing nothing.
                if (t.store == LauncherSettingsPrefs.Config.STORE_NIGHTMODE) {
                    if (!SystemDisplay.hasChannel(ctx)) Toast.makeText(
                        ctx, "Dark mode needs the shell channel — turn on wireless debugging first.",
                        Toast.LENGTH_LONG).show()
                    // A switch thumb moved under the finger; a tile does not. So
                    // the repaint has to be asked for, and only AFTER the write
                    // lands — repaint eagerly and the grid draws the old value.
                    else Thread({
                        settingsPrefs.setToggle(t, on)
                        view?.post { repaintFromStore() }
                    }, "night-mode").start()
                    return@toggleGrid
                }
                settingsPrefs.setToggle(t, on)
                // Eye protection = the ANDROID SYSTEM night-light (blue-light
                // filter), NOT a custom overlay — open its settings to enable.
                if (t.id == "eye_protection" && on) {
                    runCatching { startActivity(Intent("android.settings.NIGHT_DISPLAY_SETTINGS")) }
                        .onFailure { runCatching { startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS)) } }
                }
                onToggleChanged()
                // The tile that was tapped repaints itself, and the mode tiles
                // refill — a hand-flip can move the selected mode into its
                // "modified" state, and that label is derived, not stored. What
                // this used to do was detach and re-attach the whole fragment
                // for those two facts: #349's "the page reloads".
                repaintFromStore()
            }
            GapDp(8)

            // Scale lives in the Others box because it is the same kind of
            // whole-UI preference as the switches above it — Samsung draws
            // Screen zoom and Font size on this screen too, as two separate
            // knobs that drift apart. SystemDisplay drives both from this one.
            if (group.id == "others") {
                val sc = LauncherSettingsPrefs.Config.scale
                // ticks, the one-tap sizes AND the shipped default all come off
                // the SAME declared record the range does (#384, #408). Nothing
                // about this control is written twice, so no edit to build.json
                // can leave a button setting a number the slider never had, or
                // a button called "Default" that is not what the app installs
                // with — Slider.default IS the shipped preset's value.
                sliderRow(sc.label, sc.subtitle, sc.min, sc.max, settingsPrefs.scale,
                    ticks = sc.ticks, presets = sc.presets) { v ->
                    // #601: SystemDisplay.wrap applies this in-process, so the
                    // change needs no shell channel — just a re-read, which
                    // recreate() gets by re-running attachBaseContext.
                    settingsPrefs.scale = v
                    SystemDisplay.applyScale(ctx)
                }
                GapDp(8)
            }
        }
    }

    /**
     * #1621 Background — Configs ▸ Launcher ▸ Quick Actions ▸ UI ▸ Background.
     * The one settings surface the animated launcher backdrop never had:
     * enable/disable, the galaxy-stars toggle (a VIEW onto the existing
     * `stars_anim`, not a copy), the 3D/4D regular-polytope shape picker and the
     * auto-cycle (on/off, minutes, which shapes take part). Entirely declared in
     * build.json::ui.launcher_settings.background and read through
     * LauncherSettingsPrefs; the renderer (Home3DFragment / PolytopeMath) obeys
     * the same keys, so flipping a control here changes the live home background.
     */
    @Composable
    private fun BackgroundSection(settingsPrefs: LauncherSettingsPrefs, epoch: Int) {
        val bg = LauncherSettingsPrefs.Config.background
        GapDp(24)
        SectionHeaderUi(bg.label, bg.subtitle)

        // Draw the animated background at all.
        toggleRow(bg.enableLabel, bg.enableSubtitle, epoch, { settingsPrefs.backgroundEnabled }) { on ->
            settingsPrefs.backgroundEnabled = on; onToggleChanged(); repaintFromStore()
        }
        GapDp(8)

        // Star animation — a VIEW onto the existing stars_anim switch, never a second copy of it.
        val starsItem = LauncherSettingsPrefs.Config.toggles.firstOrNull { it.id == bg.starsToggle }
        if (starsItem != null) {
            toggleRow(starsItem.label, starsItem.subtitle, epoch, { settingsPrefs.toggle(starsItem.id) }) { on ->
                settingsPrefs.setToggle(starsItem.id, on); onToggleChanged(); repaintFromStore()
            }
            GapDp(8)
        }

        // Shape picker, grouped (3D Platonic solids, then their 4D analogues).
        GapDp(8)
        SectionHeaderUi("Background shape",
            "The rotating shape on the home pane. 4D polytopes render as a rotating 4D→3D projection in the same slot.")
        val currentShape = epoch.let { settingsPrefs.backgroundShape }
        var lastGroup = ""
        for (s in bg.shapes) {
            if (s.group.isNotBlank() && s.group != lastGroup) { lastGroup = s.group; GapDp(8); SectionHeaderUi(s.group, "") }
            val sub = when {
                s.dim == 4 && s.dualOf.isBlank() -> "4D · self-dual (no regular 3D twin)"
                s.dim == 4 -> "4D · analogue of the ${s.dualOf}"
                else -> "3D Platonic solid"
            }
            GenericTileUi(s.label, sub, s.id == currentShape) {
                settingsPrefs.backgroundShape = s.id; repaintFromStore()
            }
            GapDp(8)
        }

        // Auto-cycle: on/off, the cadence, and which shapes take part.
        GapDp(16)
        SectionHeaderUi("Auto-cycle", bg.cycleEnableSubtitle)
        toggleRow(bg.cycleEnableLabel, bg.cycleEnableSubtitle, epoch, { settingsPrefs.backgroundCycleEnabled }) { on ->
            settingsPrefs.backgroundCycleEnabled = on; onToggleChanged(); repaintFromStore()
        }
        GapDp(8)
        val cm = bg.cycleMinutes
        sliderRow(cm.label, cm.subtitle, cm.min, cm.max, settingsPrefs.backgroundCycleMinutes) { v ->
            settingsPrefs.backgroundCycleMinutes = v
        }
        GapDp(8)
        SectionHeaderUi("Shapes in the cycle", "Ticked shapes take part in the auto-cycle rotation (all on by default).")
        for (s in bg.shapes) {
            toggleRow(s.label, "", epoch, { settingsPrefs.cycleShapeEnabled(s.id) }) { on ->
                settingsPrefs.setCycleShapeEnabled(s.id, on); repaintFromStore()
            }
            GapDp(4)
        }
    }

    /** The Control grid, hosted as a child fragment so its ticker, lights and tap/hold are untouched. */
    @Composable
    private fun ControlsGridHost() {
        val hostId = R.id.controls_grid_host
        val committed = remember { BooleanArray(1) }
        AndroidView(
            modifier = Modifier.fillMaxWidth(),
            factory = { c -> FragmentContainerView(c).apply { id = hostId } },
            update = { v ->
                if (!committed[0]) {
                    committed[0] = true
                    v.post {
                        if (isAdded && childFragmentManager.findFragmentById(hostId) == null) {
                            childFragmentManager.beginTransaction()
                                .replace(hostId,
                                    com.diegonmarcos.superapp.configs.ControlFragment.newInstance(embedded = true))
                                .commitAllowingStateLoss()
                        }
                    }
                }
            },
        )
    }

    /** Compact −/+ stepper over [GridColumns.MIN]..[GridColumns.MAX]. */
    @Composable
    private fun stepperRow(
        label: String, subtitle: String, value: Int,
        onChange: (Int) -> Unit,
    ) {
        val p = LocalKitPalette.current
        val view = LocalView.current
        var cur by remember { mutableIntStateOf(GridColumns.clamp(value)) }
        @Composable
        fun btn(glyph: String, desc: String, delta: Int) {
            Box(Modifier.size(28.dp).background(p.surfaceSelected).semantics { contentDescription = desc }
                .clickable {
                    val next = GridColumns.clamp(cur + delta)
                    if (next == cur) return@clickable
                    Haptics.tap(view)
                    cur = next
                    onChange(next)
                }.testTag("stepper:$label:$desc"), contentAlignment = Alignment.Center) {
                Text(glyph, color = p.textPrimary, style = MaterialTheme.typography.titleSmall)
            }
        }
        Row(Modifier.fillMaxWidth().background(p.surface).padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, color = p.textPrimary, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, color = p.textSecondary, style = MaterialTheme.typography.labelSmall)
            }
            btn("−", "Fewer icons per row", -1)
            Text(cur.toString(), color = p.accent, style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center, modifier = Modifier.width(36.dp).testTag("stepper:readout:$label"))
            btn("+", "More icons per row", 1)
        }
    }

    /** A label/subtitle + right-aligned switch row, persisted on toggle.
     *
     *  [checked] is a QUESTION, not a value: this row is the group master and
     *  its position is derived from the children below it, which the user can
     *  flip one at a time. It is re-asked on every [repaintFromStore] (the
     *  [epoch] is the input that makes that happen). */
    @Composable
    private fun toggleRow(
        label: String, subtitle: String, epoch: Int, checked: () -> Boolean,
        onChange: (Boolean) -> Unit,
    ) {
        val p = LocalKitPalette.current
        val view = LocalView.current
        // The Switch reports a user flip only: following the children is a new `checked`, never a callback, so a sync can
        // not re-enter onChange and write the master's new position over every child.
        val on = epoch.let { checked() }
        Row(Modifier.fillMaxWidth().background(p.surface).padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, color = p.textPrimary, style = MaterialTheme.typography.titleSmall)
                if (subtitle.isNotBlank()) Text(subtitle, color = p.textSecondary, style = MaterialTheme.typography.labelSmall)
            }
            // Persist FIRST, then buzz — otherwise Haptics.tap reads the
            // stale (pre-toggle) value and the "Vibration on tap" switch
            // itself silently no-ops when flipped ON.
            Switch(checked = on, onCheckedChange = { isOn -> onChange(isOn); Haptics.tap(view) },
                modifier = Modifier.testTag("master:$label"))
        }
    }

    /**
     * One box of switches drawn the way Configs ▸ Panel ▸ Control draws its
     * controls: a grid of round icon badges with a status light under each.
     *
     * WHY IT IS NOT A COLUMN OF SWITCH ROWS ANY MORE. It was, and the whole
     * Modes tab had become twenty-four stacked sentences each ending in a
     * switch, read one row at a time top to bottom. What the owner actually
     * does here is SCAN — find the one that is on, or the one to turn off —
     * and an icon grid is scanned in one look. Same argument, same shape and
     * the same two theme colours swapped as #246 applied to Control, which is
     * the screen this now matches by design and not by coincidence.
     *
     * The column count is [DeviceControls.columns], the SAME number Control's
     * grid uses, rather than a constant written here. Two grids that are
     * supposed to look like one another must not be able to disagree about how
     * wide they are, and one of two constants is always the one that gets
     * missed.
     *
     * A tile REPAINTS ITSELF. It used to be painted once and rely on the page
     * being rebuilt under it, on the grounds that a flip rebuilt the page
     * anyway — and that assumption is #349: one badge changing colour was
     * paying for a detach+attach of a page holding twenty-four tiles and two
     * pickers, and it took the scroll position with it. The tile still tracks
     * no state of its own: it re-reads [prefs] each time it paints, which is
     * why [prefs] is passed down here instead of a boolean.
     */
    @Composable
    private fun toggleGrid(
        items: List<LauncherSettingsPrefs.Item>,
        prefs: LauncherSettingsPrefs,
        epoch: Int,
        onFlip: (LauncherSettingsPrefs.Item, Boolean) -> Unit,
    ) {
        val columns = DeviceControls.columns
        Column(Modifier.fillMaxWidth().background(LocalKitPalette.current.surface).padding(8.dp)) {
            for (strip in items.chunked(columns)) {
                Row(Modifier.fillMaxWidth()) {
                    for (item in strip) Box(Modifier.weight(1f)) { toggleTile(item, prefs, epoch, onFlip) }
                    // Pad the last strip so four tiles over three columns leaves the fourth
                    // under the first, not stretched to a third of the screen.
                    repeat(columns - strip.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }

    /** One cell of [toggleGrid]: filled badge + icon + label + status light.
     *  Badge fill and icon ink are the same two palette colours swapped, so a
     *  tile reads as on or off before any word on it is read. */
    @Composable
    private fun toggleTile(
        item: LauncherSettingsPrefs.Item,
        prefs: LauncherSettingsPrefs,
        epoch: Int,
        onFlip: (LauncherSettingsPrefs.Item, Boolean) -> Unit,
    ) {
        val ctx = LocalContext.current
        val p = LocalKitPalette.current
        val view = LocalView.current
        // Badge fill and icon ink are the same two palette colours swapped, plus
        // the status word under them — the three things that say on or off, and
        // the only three a flip changes. Read from the store every time: a
        // store-backed switch (dark mode) is a VIEW OF the owning store and can
        // change without this page being touched at all.
        val on = epoch.let { prefs.toggle(item) }
        val state = if (on) StatusLight.State.ON else StatusLight.State.OFF
        Column(
            Modifier.fillMaxWidth().padding(6.dp)
                // The grid replaced labelled rows with icons, which took the text a
                // screen reader was reading off the screen — so the whole tile
                // carries the sentence and its children are skipped. It carries the
                // STATE too, so it has to be re-spoken when the state moves.
                .semantics(mergeDescendants = true) { contentDescription = StatusLight.description(ctx, item.label, state) }
                .clickable {
                    Haptics.tap(view)
                    // Asked of the store, not of a value captured when this tile was
                    // built. The tile now outlives its own flips, so a captured `on`
                    // would be right once and wrong every time after.
                    onFlip(item, !prefs.toggle(item))
                }.testTag("toggle:${item.id}"),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // The icon's plate hugs the icon (was a 56dp disc: the owner's "box too big"); the tile is the target.
            Box(Modifier.size(TILE_BADGE_DP.dp).clip(CircleShape).background(if (on) p.accent else p.surfaceSelected)
                .testTag("toggle:badge:${item.id}"),
                contentAlignment = Alignment.Center) {
                Icon(painterResource(Sections.iconResFor(ctx, item.icon)), contentDescription = null,
                    tint = if (on) p.surface else p.textSecondary, modifier = Modifier.size(TILE_ICON_DP.dp))
            }
            Text(item.label, color = p.textPrimary, fontSize = 11.sp, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 3.dp))
            Text(StatusLight.text(ctx, state), color = Color(StatusLight.colour(ctx, state)), fontSize = 9.sp,
                textAlign = TextAlign.Center)
        }
    }

    /** A label + slider row. [value] pre-positions the handle; [onChange] fires
     *  on release so we don't spam writes while dragging.
     *
     *  [ticks] (#384) draws one mark per step under the line and puts the
     *  current step beside the label. The slider's value was ALREADY an
     *  integer, so the handle always landed on a step — what was missing was
     *  any way to SEE that, which is the whole of the owner's "then it don't
     *  have middle range numbers and we can see in which level is". Marks +
     *  readout, not a new snapping rule. It is declared per slider because
     *  Screen brightness is 0..255 and 256 marks are a grey smear.
     *
     *  [presets] (#384, widened by #408) adds one one-tap action per declared
     *  size — "Default" and "Normal" on the Scale row, the owner's own words —
     *  each moving the handle to a value that came out of the declaration and
     *  never out of a literal here. The row does not know which of them is the
     *  shipped one and must not: that is Slider.default's job, derived from the
     *  same records, so the button and the first install cannot disagree.
     *
     *  NEITHER reloads the page. The restore path writes the store, moves the
     *  handle of THIS slider and calls the same onChange a drag would, so it
     *  costs one state update — not the detach+attach (#349a) or the
     *  Activity.recreate (#349b) that made a tap on this page lose the scroll
     *  position and come back on the Profiles tab. */
    @Composable
    private fun sliderRow(
        label: String, subtitle: String, min: Int, max: Int, value: Int,
        ticks: Boolean = false,
        presets: List<LauncherSettingsPrefs.Preset> = emptyList(),
        onChange: (Int) -> Unit,
    ) {
        val p = LocalKitPalette.current
        val view = LocalView.current
        // The live step. It follows the FINGER, not the release — a number that only catches up when you let go is the
        // same "which level am I on" question with an extra second of delay.
        var progress by remember { mutableIntStateOf(value.coerceIn(min, max)) }
        Column(Modifier.fillMaxWidth().background(p.surface).padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text(label, color = p.textPrimary, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (ticks) Text(progress.toString(), color = p.accent, style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.testTag("slider:readout:$label"))
            }
            if (subtitle.isNotBlank()) Text(subtitle, color = p.textSecondary, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(bottom = 4.dp))
            // One stop per step: the slider's own discrete marks are drawn from the SAME range as the stops, so the marks
            // and the stops are one fact and cannot drift. A hand-drawn tick bar under the line would be a second copy of
            // the range (#170).
            Slider(
                value = progress.toFloat(),
                onValueChange = { progress = it.toInt().coerceIn(min, max) },
                onValueChangeFinished = { onChange(progress) },
                valueRange = min.toFloat()..max.toFloat(),
                steps = if (ticks) (max - min - 1).coerceAtLeast(0) else 0,
                modifier = Modifier.fillMaxWidth().testTag("slider:$label"),
            )
            if (ticks) Row(Modifier.fillMaxWidth()) {
                // The two ends named, so the row reads as a scale even before the
                // handle is touched. Derived from the declared bounds.
                Text(min.toString(), color = p.textSecondary, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                Text(max.toString(), color = p.textSecondary, style = MaterialTheme.typography.labelSmall)
            }
            if (presets.isNotEmpty()) Row(Modifier.padding(top = 8.dp)) {
                // One button per DECLARED preset, in declared order. A loop and not
                // two blocks: a third size is then a build.json entry, the way a
                // fourth toggle group was (#384's "a third box was a code change").
                for (preset in presets) Text(
                    // The number is shown, and it is the declared one — a label
                    // reading "Default" over a handle that landed somewhere else is
                    // the disagreement this whole row is shaped to prevent.
                    "${preset.label} (${preset.value})",
                    color = p.accent, style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(end = 18.dp).clickable {
                        Haptics.tap(view)
                        // Moving the handle is a state write and does not fire the
                        // release callback, so the write is asked for explicitly here
                        // and happens exactly once.
                        progress = preset.value.coerceIn(min, max)
                        onChange(progress)
                    }.testTag("slider:preset:${preset.label}"))
            }
        }
    }

    /** Device-wide brightness via Settings.System (manual mode). Needs the
     *  WRITE_SETTINGS special-access grant — if absent, route the user to the
     *  grant screen and apply on their next adjustment. value<min = untouched. */
    private fun applySystemBrightness(ctx: android.content.Context, value: Int) {
        if (value < 0) return
        if (!Settings.System.canWrite(ctx)) {
            runCatching {
                startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:" + ctx.packageName)))
            }
            return
        }
        runCatching {
            Settings.System.putInt(ctx.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(ctx.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS, value.coerceIn(0, 255))
        }
    }

    /** What every switch has to do after it is written, whichever group it is
     *  in: re-apply the launcher chrome so stars / cube / pets pick the change
     *  up, and re-arm the constellation worker so `fleet_check` takes effect
     *  now rather than at the next cold start. One place, so a new group cannot
     *  be added without it.
     *
     *  IT DOES NOT CALL notifyLauncherThemeChanged, and the sentence above is
     *  why: that is the MODE-change hook, and since 7600f71a9 its body is
     *  `LauncherStyle.restartForModeChange(this)` — an `Activity.recreate()`.
     *  A mode HAS to recreate (Android resolves a Material3 style once per
     *  Activity, at inflate time, so a mode applied any other way leaves every
     *  XML surface in the old one). A TOGGLE names no style, and this line
     *  bought a whole-Activity rebuild for each of twenty-four switches: the
     *  page flashed, the scroll position went, and the tab strip around it came
     *  back on Profiles because the controller that remembers the tab died with
     *  the Activity. That is #349, both halves, from one call.
     *
     *  [ShellActivity.applyLauncherChrome] is what "re-apply the launcher
     *  chrome" always meant — public, on the NavHost interface, documented
     *  idempotent — and [applyShellLiveToggles] pushes the three prefs whose
     *  views live in the shell and so cannot re-read themselves. Between them
     *  they are what the recreate was actually being used for. */
    private fun onToggleChanged() {
        (activity as? ShellActivity)?.applyLauncherChrome()
        applyShellLiveToggles(activity)
        runCatching {
            com.diegonmarcos.superapp.appstore.ConstellationWorker.start(requireContext())
        }
    }

    /** True when the SuperApp's MainActivity is currently the resolved
     *  default Home Screen handler. The picker uses this to surface a
     *  live "active / not active" hint above the CTA. */
    private fun isDefaultLauncher(ctx: android.content.Context): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
        }
        val resolved = ctx.packageManager.resolveActivity(intent, 0)
        return resolved?.activityInfo?.packageName == ctx.packageName
    }

    companion object {
        /** [scope] "all" (or "") / "ui" / "battery" / "tools" draws one Panels tab; All is UI + Battery Hungers + Tools. */
        fun newInstance(scope: String = "") = LauncherConfigFragment().apply {
            arguments = android.os.Bundle().apply { putString(ARG_SCOPE, scope) }
        }

        private const val ARG_SCOPE = "scope"
        const val SCOPE_ALL = "all"
        const val SCOPE_UI = "ui"
        const val SCOPE_BATTERY = "battery"
        const val SCOPE_TOOLS = "tools"

        // The same two numbers Configs ▸ Panel ▸ Control uses for its badges, so
        // the two grids read as one screen. #338 is why they are stated once:
        // that regression was a size bumped in one place and not the other.
        private const val TILE_BADGE_DP = 26
        private const val TILE_ICON_DP = 22
    }
}

/** Parses build.json::ui.launcher_themes from the baked BuildConfig
 *  blob. New themes only need a build.json entry + a LauncherTheme
 *  enum case — no Kotlin list edits required here.
 *
 *  Also hosts the data-driven [Features] decoder used by MainActivity
 *  / BackgroundOrchestrator to apply each theme's behavior (kept in
 *  this file so the picker code and the runtime code share one
 *  registry — no risk of two parallel parsers drifting). */
object LauncherThemes {
    data class Theme(
        val id: String,
        val label: String,
        val subtitle: String,
        val default: Boolean,
    )

    fun loadFromBuildConfig(): List<Theme> = runCatching {
        val json = String(Base64.decode(BuildConfig.UI_LAUNCHER_THEMES_B64, Base64.NO_WRAP))
        val arr = JSONArray(json)
        (0 until arr.length()).map { idx ->
            val o = arr.getJSONObject(idx)
            Theme(
                id       = o.optString("id"),
                label    = o.optString("label"),
                subtitle = o.optString("subtitle"),
                default  = o.optBoolean("default", false),
            )
        }
    }.getOrDefault(emptyList())

    /** Mirrors the keys in build.json::ui.launcher_themes[*].features. */
    data class Features(
        val home3d:            Boolean,
        val bottomNav:         Boolean,
        val dynamicIsland:     Boolean,
        val animations:        Boolean,
        val drawer:            Boolean,
        val oledBlack:         Boolean,
        val grid:              String,
        val backgroundPause:   Boolean,
        val wireguardRequired: Boolean,
    ) {
        companion object {
            val SAFE_DEFAULT = Features(
                home3d            = true,
                bottomNav         = true,
                dynamicIsland     = true,
                animations        = true,
                drawer            = true,
                oledBlack         = false,
                grid              = "default",
                backgroundPause   = false,
                wireguardRequired = false,
            )
        }
    }

    private val featuresById: Map<String, Features> by lazy { parseFeatures() }

    fun featuresFor(themeId: String): Features = featuresById[themeId] ?: Features.SAFE_DEFAULT
    fun featuresFor(theme: LauncherTheme): Features = featuresFor(theme.id)

    // ── Theme → toggle mapping ──────────────────────────────────────────────
    /**
     * The device state each theme sets, read from
     * build.json::ui.launcher_themes[*].toggles. Picking a theme applies the
     * chrome ([Features]) and this map in ONE action, which is what makes the
     * switches on the Theme tab agree with what the theme actually did.
     *
     * The mapping is DATA. A new theme is a new entry in that array — there is
     * deliberately no `when (theme)` here to add an arm to, because the previous
     * shape of this code had exactly that and it is why the toggles and the
     * theme could disagree: two places to edit, one of them easy to forget.
     *
     * A toggle the theme does not name keeps whatever the user had.
     */
    private val togglesById: Map<String, Map<String, Boolean>> by lazy { parseToggles() }

    fun togglesFor(themeId: String): Map<String, Boolean> = togglesById[themeId] ?: emptyMap()
    fun togglesFor(theme: LauncherTheme): Map<String, Boolean> = togglesFor(theme.id)

    private fun parseToggles(): Map<String, Map<String, Boolean>> = runCatching {
        parseTogglesFrom(JSONArray(String(Base64.decode(BuildConfig.UI_LAUNCHER_THEMES_B64, Base64.NO_WRAP))))
    }.getOrDefault(emptyMap())

    /**
     * `id → toggles` for any declaration array whose entries carry a `toggles`
     * object — the themes, and (#574) the modes. ONE parser, so the `user_owned`
     * drop below cannot be missing from one of the two.
     */
    fun parseTogglesFrom(arr: JSONArray): Map<String, Map<String, Boolean>> {
        // Read ONCE, here, and drop the ids no theme may write. Filtering at the
        // source means no caller can forget to: an `edge_menus` key mistakenly
        // added to a theme record simply does not survive parsing. Doing it at
        // the call sites instead would be one `if` per site and one forgotten
        // site away from a theme silently switching the user's edge menus off.
        val userOwned = LauncherSettingsPrefs.Config.userOwnedIds
        val out = mutableMapOf<String, Map<String, Boolean>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isBlank()) continue
            val t = o.optJSONObject("toggles") ?: continue
            val map = mutableMapOf<String, Boolean>()
            for (key in t.keys()) {
                if (key in userOwned) continue
                map[key] = t.optBoolean(key)
            }
            out[id] = map
        }
        return out
    }

    /**
     * CHOOSING A THEME SETS THE TOGGLES AND APPLIES THE UI TOGETHER — one call,
     * both effects. Every surface that changes the theme goes through here, so
     * there is no path that applies the chrome without the device state.
     */
    fun apply(ctx: android.content.Context, theme: LauncherTheme) {
        LauncherThemePrefs(ctx).theme = theme
        applyToggles(ctx, togglesFor(theme))
    }

    /**
     * The device-state half of [apply], shared with the modes (#574): write each
     * named switch through its declared Item. One engine, two callers — a mode
     * is a theme without the chrome, not a second implementation of it.
     */
    fun applyToggles(ctx: android.content.Context, toggles: Map<String, Boolean>) {
        val settings = LauncherSettingsPrefs(ctx)
        val byId = LauncherSettingsPrefs.Config.toggles.associateBy { it.id }
        for ((id, want) in toggles) {
            // Route through the Item so a switch backed by another subsystem's
            // store is written where that subsystem reads it. An unknown id is
            // skipped rather than written blind: it would create a preference
            // key no switch displays, which is the invisible state this whole
            // change exists to remove.
            val item = byId[id] ?: continue
            settings.setToggle(item, want)
        }
    }

    /**
     * Does the device still match what [theme] declares?
     *
     * DERIVED, never stored. The alternative — a "modified" flag written when a
     * switch is flipped — is a second copy of the truth that goes stale the
     * moment anything else writes a toggle. Comparing against the mapping cannot
     * be wrong, because the mapping is what "being on this theme" MEANS.
     *
     * The picker shows "· modified" when this is true rather than silently
     * claiming a theme the phone no longer matches. A label that lies is worse
     * than no label: the user came to this screen precisely to find out what is
     * on, and the honest answer is "Cloud Minimalist Black, except you changed something".
     */
    fun isModified(ctx: android.content.Context, theme: LauncherTheme): Boolean =
        differs(ctx, togglesFor(theme))

    /** [isModified]'s comparison for any toggle map — the modes use it too. */
    fun differs(ctx: android.content.Context, toggles: Map<String, Boolean>): Boolean {
        val settings = LauncherSettingsPrefs(ctx)
        val byId = LauncherSettingsPrefs.Config.toggles.associateBy { it.id }
        return toggles.any { (id, want) ->
            val item = byId[id] ?: return@any false
            settings.toggle(item) != want
        }
    }

    private fun parseFeatures(): Map<String, Features> = runCatching {
        val json = String(Base64.decode(BuildConfig.UI_LAUNCHER_THEMES_B64, Base64.NO_WRAP))
        val arr = JSONArray(json)
        val out = mutableMapOf<String, Features>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isBlank()) continue
            val f = o.optJSONObject("features") ?: org.json.JSONObject()
            out[id] = Features(
                home3d            = f.optBoolean("home_3d",            true),
                bottomNav         = f.optBoolean("bottom_nav",         true),
                dynamicIsland     = f.optBoolean("dynamic_island",     true),
                animations        = f.optBoolean("animations",         true),
                drawer            = f.optBoolean("drawer",             true),
                oledBlack         = f.optBoolean("oled_black",         false),
                grid              = f.optString("grid",                "default"),
                backgroundPause   = f.optBoolean("background_pause",   false),
                wireguardRequired = f.optBoolean("wireguard_required", false),
            )
        }
        out
    }.getOrDefault(emptyMap())
}

/** Parses build.json::ui.launcher_profiles from the baked BuildConfig
 *  blob. Same shape as [LauncherThemes]. Add a profile via build.json
 *  + LauncherProfile enum — no other Kotlin edits needed.
 *
 *  Also hosts the [Behavior] decoder + [allowedPackagesFor] used by
 *  PhoneAppsFragment / MainActivity to enforce per-profile lockdowns. */
object LauncherProfiles {
    data class Profile(
        val id: String,
        val label: String,
        val subtitle: String,
        val default: Boolean,
    )

    fun loadFromBuildConfig(): List<Profile> = runCatching {
        val json = String(Base64.decode(BuildConfig.UI_LAUNCHER_PROFILES_B64, Base64.NO_WRAP))
        val arr = JSONArray(json)
        (0 until arr.length()).map { idx ->
            val o = arr.getJSONObject(idx)
            Profile(
                id       = o.optString("id"),
                label    = o.optString("label"),
                subtitle = o.optString("subtitle"),
                default  = o.optBoolean("default", false),
            )
        }
    }.getOrDefault(emptyList())

    /** Mirrors build.json::ui.launcher_profiles[*].behavior. */
    data class Behavior(
        val appFilter:     String,        // "all" | "whitelist"
        val whitelist:     List<String>,
        val wireguard:     String,        // "on" | "off"
        val allowIntents:  Any,           // "all" | List<String>
    ) {
        val isLockdown: Boolean get() = appFilter == "whitelist"
        val wireguardOff: Boolean get() = wireguard.equals("off", ignoreCase = true)

        companion object {
            val PASSTHROUGH = Behavior(
                appFilter    = "all",
                whitelist    = emptyList(),
                wireguard    = "on",
                allowIntents = "all",
            )
        }
    }

    private val behaviorById: Map<String, Behavior> by lazy { parseBehaviors() }

    fun behaviorFor(profileId: String): Behavior = behaviorById[profileId] ?: Behavior.PASSTHROUGH
    fun behaviorFor(profile: LauncherProfile): Behavior = behaviorFor(profile.id)

    /** Resolve the set of allowed package names for the active profile.
     *  Returns null when the active profile is passthrough — caller
     *  should NOT filter. Returns a possibly-empty set otherwise. */
    fun allowedPackagesFor(context: android.content.Context, profile: LauncherProfile): Set<String>? {
        val b = behaviorFor(profile)
        if (!b.isLockdown) return null
        val out = mutableSetOf<String>()
        b.whitelist.forEach { tag -> out.addAll(packagesForTag(context, tag)) }
        return out
    }

    private fun packagesForTag(context: android.content.Context, tag: String): Set<String> = when (tag) {
        "browser" -> browserPackages(context)
        else      -> setOf(tag)
    }

    private fun browserPackages(context: android.content.Context): Set<String> {
        val pm = context.packageManager
        val probe = android.content.Intent(
            android.content.Intent.ACTION_VIEW,
            android.net.Uri.parse("http://example.com"),
        )
        val flags = android.content.pm.PackageManager.MATCH_ALL or
                    android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
        val matches = try { pm.queryIntentActivities(probe, flags) } catch (_: Throwable) { emptyList() }
        return matches.mapNotNull { it.activityInfo?.packageName }.toSet()
    }

    private fun parseBehaviors(): Map<String, Behavior> = runCatching {
        val json = String(Base64.decode(BuildConfig.UI_LAUNCHER_PROFILES_B64, Base64.NO_WRAP))
        val arr = JSONArray(json)
        val out = mutableMapOf<String, Behavior>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isBlank()) continue
            val b = o.optJSONObject("behavior") ?: org.json.JSONObject()
            val whitelist = mutableListOf<String>()
            val wlArr = b.optJSONArray("whitelist") ?: JSONArray()
            for (j in 0 until wlArr.length()) {
                val s = wlArr.optString(j); if (s.isNotBlank()) whitelist.add(s)
            }
            val rawAllow = b.opt("allow_intents")
            val allow: Any = when (rawAllow) {
                is JSONArray -> (0 until rawAllow.length()).mapNotNull {
                    val s = rawAllow.optString(it); if (s.isBlank()) null else s
                }
                is String -> rawAllow
                else -> "all"
            }
            out[id] = Behavior(
                appFilter    = b.optString("app_filter", "all"),
                whitelist    = whitelist,
                wireguard    = b.optString("wireguard",  "on"),
                allowIntents = allow,
            )
        }
        out
    }.getOrDefault(emptyMap())
}

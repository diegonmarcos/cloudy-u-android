package com.diegonmarcos.superapp.configs

import com.diegonmarcos.superapp.system.RamPlus
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.ui.LauncherPalette
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import com.diegonmarcos.superapp.BuildConfig
import com.diegonmarcos.superapp.appstore.ConstellationWorker
import com.diegonmarcos.superapp.battery.EnergyWatchdog
import com.diegonmarcos.superapp.floatingnav.FloatingNavService
import com.diegonmarcos.superapp.health.HealthConnectGateway
import com.diegonmarcos.superapp.health.HealthMetrics
import com.diegonmarcos.superapp.system.PermAskTracker
import com.diegonmarcos.superapp.system.DeviceTuning
import com.diegonmarcos.superapp.system.PrivilegedGrants
import com.diegonmarcos.superapp.system.ScreenLocker
import com.diegonmarcos.superapp.system.ShellPlane
import com.diegonmarcos.superapp.system.PackageVerifier
import android.content.pm.PackageManager

/** Permissions page — runtime perms, special access, Health Connect,
 *  auto-granted, and all grant/set action buttons. Extracted from
 *  DevControlFragment so it has its own dedicated Configs tab. */
class PermissionsFragment : KitComposeFragment() {

    private val notifPermLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {
            Toast.makeText(requireContext(),
                if (it) "Notifications: granted" else "Notifications: denied",
                Toast.LENGTH_SHORT).show()
            rebuildFragment()
        }

    private val allPermsLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val granted = result.count { it.value }
            val denied  = result.size - granted
            Toast.makeText(requireContext(),
                "Permissions: $granted granted, $denied denied", Toast.LENGTH_SHORT).show()
            rebuildFragment()
        }

    // ── #1274 catalogue section: the ONE place that asks for permissions ──
    /** Re-read key of the catalogue rows only: bumped on resume and after every grant, never tears the fragment down. */
    private var centerEpoch by mutableIntStateOf(0)
    private var highlight by mutableStateOf<com.diegonmarcos.superapp.perms.PermsLink.Request?>(null)
    private var adbResults by mutableStateOf<List<AdbRunResult>>(emptyList())
    private var adbRunning by mutableStateOf(false)
    private var autoStarted = false

    /** One system dialog per batch; the result moves to the next batch. The session set guarantees no loop. */
    private val centerLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { result ->
            centerEpoch++
            if (walking) view?.post { if (isAdded) walkNext() }
            else if (autoChain) view?.post { if (isAdded) autoNext() }
            // #1546 a tap's result: no dialog shown (permanent denial) -> the app's settings page; location on but the switch off -> Location settings
            else if (userAsk) { userAsk = false; activity?.let { com.diegonmarcos.superapp.perms.PermsRequest.handleResult(it, result, it) } }
        }
    private var userAsk = false
    private var autoChain = false

    private fun autoNext() {
        val act = activity ?: return
        val cat = com.diegonmarcos.superapp.perms.Perms.catalogue(act)
        val vars = com.diegonmarcos.superapp.perms.Perms.vars(act)
        val batch = com.diegonmarcos.superapp.perms.AutoRequestPlanner.batches(cat,
            { com.diegonmarcos.superapp.perms.PermStatusReader.status(act, it, act, vars) }, askedThisSession).firstOrNull()
        if (batch.isNullOrEmpty()) { autoChain = false; centerEpoch++; return }
        askBatch(batch)
    }

    private fun askBatch(batch: List<com.diegonmarcos.superapp.perms.PermEntry>) {
        // #1519 one combined request: fine location always travels with coarse on API 31+, or Android ignores / downgrades it
        val perms = com.diegonmarcos.superapp.perms.PermsRequest.expand(batch.map { it.permission }.filter { it.isNotEmpty() })
        if (perms.isEmpty()) return
        askedThisSession.addAll(perms)
        com.diegonmarcos.superapp.perms.PermAskLog.markAsked(requireContext(), perms)
        com.diegonmarcos.superapp.perms.PermAskLog.markAskedIds(requireContext(), batch.map { it.id })
        com.diegonmarcos.superapp.perms.PermsRequest.markLaunch()
        runCatching { centerLauncher.launch(perms.toTypedArray()) }.onFailure { autoChain = false; walking = false; userAsk = false }
    }

    /** The Grant button of a row: the combined dialog while Android still shows it, the settings page after a permanent denial. */
    private fun grantRow(e: com.diegonmarcos.superapp.perms.PermEntry) {
        val act = requireActivity()
        userAsk = true
        com.diegonmarcos.superapp.perms.PermsRequest.requestVia(act, listOf(e), act) { perms ->
            askedThisSession.addAll(perms)
            runCatching { centerLauncher.launch(perms) }
        }
        centerEpoch++
    }

    // ── #1519 Grant via shell (the shell broker, allowlist-checked), read back, then the page re-reads ──
    private var shellNote by mutableStateOf<String?>(null)

    private fun shellGrant(e: com.diegonmarcos.superapp.perms.PermEntry, then: () -> Unit = {}) {
        val ctx = requireContext().applicationContext
        val cmds = com.diegonmarcos.superapp.perms.PermsShellGrant.commands(ctx.packageName, e)
        if (cmds == null) { shellNote = "${e.label}: no shell line can grant it"; then(); return }
        shellNote = "${e.label}: granting by shell…"
        Thread {
            val vars = com.diegonmarcos.superapp.perms.Perms.vars(ctx)
            val out = com.diegonmarcos.superapp.perms.PermsShellGrant.run(e.label, cmds, BrokerGrantChannel(ctx)) {
                com.diegonmarcos.superapp.perms.PermStatusReader.status(ctx, e, null, vars).state == com.diegonmarcos.superapp.perms.PermState.GRANTED
            }
            view?.post { shellNote = out.message; centerEpoch++; then() }
        }.start()
    }

    /** #1546 Shell shown: each row through the shell broker in turn, read back after each. */
    private fun shellAll(entries: List<com.diegonmarcos.superapp.perms.PermEntry>) {
        val left = kotlin.collections.ArrayDeque(entries)
        fun next() { val e = left.removeFirstOrNull() ?: return; shellGrant(e) { if (isAdded) next() } }
        next()
    }

    // ── #1601 Reset all to default ───────────────────────────────────────
    /** The components (listener / accessibility / device-admin) the reset lines need, from this device's manifest. */
    private fun resetComponents(ctx: Context): Map<String, String> {
        val v = com.diegonmarcos.superapp.perms.Perms.vars(ctx)
        return buildMap {
            v["listener"]?.takeIf { it.isNotEmpty() }?.let { put("notification_listener", it) }
            v["a11y"]?.takeIf { it.isNotEmpty() }?.let { put("accessibility", it) }
            v["admin"]?.takeIf { it.isNotEmpty() }?.let { put("device_admin", it) }
        }
    }

    /** True while the "Reset all" question is open ([ResetAllConfirm] draws it). */
    private var confirmReset by mutableStateOf(false)

    /** Confirm, then revoke every runtime permission and reset every app-op / special access of this app to its default state. */
    private fun confirmResetAll() { confirmReset = true }

    /** The question behind "Reset all to default": the kit's Compose dialog; dismissing it (back, outside tap, Cancel) resets nothing. */
    @Composable
    private fun ResetAllConfirm() {
        if (!confirmReset) return
        com.diegonmarcos.superapp.uikit.KitConfirmDialog(
            "Reset all permissions to default",
            "This revokes every runtime permission and resets every app-op, special access, Do Not Disturb, " +
                "notification access, accessibility and device-admin grant of this app back to its default state. You will " +
                "have to grant them again. It runs through the ADB Shell channel. Continue?",
            "Reset all",
            "Cancel",
            onConfirm = { confirmReset = false; resetAllToDefault() },
            onDismiss = { confirmReset = false },
        )
    }

    private fun resetAllToDefault() {
        val ctx = requireContext().applicationContext
        val rows = readPermRows(ctx, null).filter { it.status.state != com.diegonmarcos.superapp.perms.PermState.NOT_APPLICABLE }
        val items = rows.map { com.diegonmarcos.superapp.perms.PermsView.item(it.entry, it.status) }
        val comps = resetComponents(ctx)
        val a11y = runCatching {
            android.provider.Settings.Secure.getString(ctx.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        }.getOrNull().orEmpty()
        val plan = com.diegonmarcos.superapp.perms.PermsActions.resetPlan(ctx.packageName, items, comps, a11y)
        shellNote = "Resetting ${plan.steps.size} permissions to default…"
        Thread {
            val res = com.diegonmarcos.superapp.perms.PermsActions.run(plan, BrokerGrantChannel(ctx))
            view?.post { shellNote = "Reset all: ${res.text()}"; centerEpoch++; rebuildFragment() }
        }.start()
    }

    // ── #1519 Ask all missing: walk the missing rows, real dialog first, then shell or settings, re-checking after each step ──
    private var walking = false
    private var awaitingResume = false
    private val walked = HashSet<String>()
    private val asked = HashSet<String>()

    private fun startWalk() { walked.clear(); asked.clear(); walking = true; awaitingResume = false; walkNext() }

    private fun walkNext() {
        val act = activity ?: return
        val ctx = act.applicationContext
        val rows = pageRows(ctx, readPermRows(act, act))
        val shellable = { e: com.diegonmarcos.superapp.perms.PermEntry -> com.diegonmarcos.superapp.perms.PermsShellGrant.commands(ctx.packageName, e) != null }
        val up = BrokerGrantChannel(ctx).up()
        when (val step = com.diegonmarcos.superapp.perms.PermsPage.nextStep(rows, walked, up, shellable, asked)) {
            null -> { walking = false; centerEpoch++; shellNote = com.diegonmarcos.superapp.perms.PermsPage.summary(rows).text }
            is com.diegonmarcos.superapp.perms.PermsPage.Step.Dialog -> { asked += step.entries.map { it.id }; askBatch(step.entries) }
            is com.diegonmarcos.superapp.perms.PermsPage.Step.Shell -> {
                walked += step.row.entry.id
                shellGrant(step.row.entry) { if (walking && isAdded) walkNext() }
            }
            is com.diegonmarcos.superapp.perms.PermsPage.Step.Settings -> {
                walked += step.row.entry.id; awaitingResume = true
                if (!com.diegonmarcos.superapp.perms.PermsSettings.open(act, step.row.entry)) { awaitingResume = false; walkNext() }
            }
        }
    }

    private fun startAuto(force: Boolean) {
        if (force) askedThisSession.clear()
        autoChain = true
        autoNext()
    }

    private fun runAdbBlock(rows: List<PermRowState>) {
        val plane = shellPlane() ?: return
        val ctx = requireContext().applicationContext
        val lines = adbLinesFor(ctx, rows)
        adbRunning = true; adbResults = emptyList()
        Thread {
            val out = ArrayList<AdbRunResult>()
            for (l in lines) {
                val o = runCatching { plane.exec(ctx, l.command) }.getOrNull()
                out += AdbRunResult(l, AdbResult.ok(o), o ?: "no answer from ${plane.name()}")
                view?.post { adbResults = out.toList() }
            }
            view?.post { adbRunning = false; centerEpoch++ }
        }.start()
    }

    @Composable
    private fun CatalogueHead() {
        val act = requireActivity()
        val rows = remember(centerEpoch) { readPermRows(act, act) }
        val plane = remember(centerEpoch) { shellPlane() }
        // the readiness state is a cached read: refresh it once when the page opens, then redraw
        androidx.compose.runtime.LaunchedEffect(Unit) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { com.diegonmarcos.superapp.system.AdbReadiness.check(ctxAny()) } }
            centerEpoch++
        }
        androidx.compose.runtime.LaunchedEffect(Unit) {
            if (!autoStarted) { autoStarted = true; startAuto(force = false) }
        }
        PermsCenterSection(
            rows = rows, highlight = highlight, channelName = plane?.name(), adbResults = adbResults, adbRunning = adbRunning,
            shellNote = shellNote,
            onClearHighlight = { highlight = null },
            onGrant = { grantRow(it) },
            onShell = { shellGrant(it) },
            onRunAdb = { runAdbBlock(rows) },
            onOpenAdbShell = { com.diegonmarcos.superapp.shellclient.ShellClient.openArmPage(ctxAny()) },
            ctx = act,
            onAskAgain = { startAuto(force = true) },
            onAskMany = { userAsk = true; askBatch(it) },
            onShellMany = { shellAll(it) },
            statusState = statusSel,
            onAdbToggle = { e, t -> runAdbToggle(t) {
                com.diegonmarcos.superapp.perms.PermStatusReader.status(ctxAny().applicationContext, e, null,
                    com.diegonmarcos.superapp.perms.Perms.vars(ctxAny().applicationContext)).state == com.diegonmarcos.superapp.perms.PermState.GRANTED
            } },
        )
    }

    // ── #1688 Run on ADB: one toggle (a permission row or Power saving) through the ADB owner channel ──
    /** The last run's output, shown under the note so a run is never silent. */
    private var adbToggleOutput by mutableStateOf("")
    private var adbToggleDown by mutableStateOf(false)

    private fun runAdbToggle(t: AdbToggle, reread: () -> Boolean?) {
        val ctx = requireContext().applicationContext
        shellNote = "${t.label}: ${t.direction.word.lowercase()} by ADB…"
        Thread {
            val out = runCatching { AdbToggle.run(t, BrokerGrantChannel(ctx), reread) }
                .getOrElse { AdbToggle.Outcome(false, "${t.label}: ${it.message ?: it.javaClass.simpleName}", "") }
            view?.post { shellNote = out.message; adbToggleOutput = out.output; adbToggleDown = out.channelDown; centerEpoch++; powerEpoch++; rolesEpoch++ }
        }.start()
    }

    // ── #1688 Roles held: every role / admin / listener this app holds, real state, release behind a confirm ──
    private var rolesEpoch by mutableIntStateOf(0)
    private var rolesState by mutableStateOf<Pair<List<RolesHeld.Held>, String>?>(null)
    private var confirmRelease by mutableStateOf<RolesHeld.Held?>(null)

    private fun loadRoles() {
        val ctx = requireContext().applicationContext
        Thread {
            val ch = BrokerGrantChannel(ctx)
            val shell: ((String) -> String?)? = if (runCatching { ch.up() }.getOrDefault(false)) { c -> ch.exec(c) } else null
            val r = runCatching { RolesHeld.read(ctx, shell) }.getOrElse { emptyList<RolesHeld.Held>() to "read failed: ${it.message}" }
            view?.post { rolesState = r }
        }.start()
    }

    @Composable
    private fun RolesHeldSection() {
        val ctx = requireContext()
        androidx.compose.runtime.LaunchedEffect(rolesEpoch) { loadRoles() }
        Column(Modifier.fillMaxWidth().testTag("perms:roles")) {
            SectionHead("ROLES HELD — what keeps this app privileged (roles, device admin, listeners)")
            val st = rolesState
            if (st == null) Small("Reading…")
            else {
                Small("Source: ${st.second}")
                if (st.first.isEmpty()) Small("This app holds no role, device admin or listener.")
                for (h in st.first) {
                    val t = h.toggle()
                    KvRow(h.kind.word, h.name)
                    if (h.note.isNotBlank()) Small(h.note)
                    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth()) {
                        AdbToggleActions(t, ctx, "perms-role") { confirmRelease = h }
                    }
                }
                PermButtonRow { PermButton("Refresh roles", null) { rolesEpoch++ } }
            }
        }
        val c = confirmRelease
        if (c != null) com.diegonmarcos.superapp.uikit.KitConfirmDialog(
            "Release ${c.kind.word.lowercase()}",
            "This runs through the ADB Shell channel:\n" + c.release.joinToString("\n") + "\n\nThe app stops holding ${c.name}. Continue?",
            "Release",
            "Cancel",
            onConfirm = {
                confirmRelease = null
                val t = c.toggle()
                runAdbToggle(t) {
                    val again = RolesHeld.read(ctx.applicationContext, BrokerGrantChannel(ctx.applicationContext).let { ch -> if (ch.up()) { x: String -> ch.exec(x) } else null })
                    again.first.none { it.id == c.id }.not()
                }
                rolesEpoch++
            },
            onDismiss = { confirmRelease = null },
        )
    }

    // ── #1689 Super power saving: same declared control as the Quick actions tile (ui.control_panel super_power_saving) ──
    private var superEpoch by mutableIntStateOf(0)
    private var superReport by mutableStateOf<List<String>>(emptyList())

    private var confirmVpn by mutableStateOf(false)

    @Composable
    private fun SuperVpnConfirm() {
        if (!confirmVpn) return
        com.diegonmarcos.superapp.uikit.KitConfirmDialog(
            "Stop the VPN too",
            "Super power saving keeps the Firewall / Firestack VPN running by default. Stopping it also stops per-app blocking until you turn the mode off. Continue?",
            "Stop VPN too", "Keep VPN",
            onConfirm = {
                confirmVpn = false
                val app = requireContext().applicationContext
                val S = com.diegonmarcos.superapp.system.SuperPowerSaving
                Thread {
                    val r = runCatching { S.turnOn(app, stopVpn = true) }.getOrElse { com.diegonmarcos.superapp.system.SuperPowerSaving.Report(false, listOf(it.message ?: "failed")) }
                    view?.post { superReport = r.lines; superEpoch++; powerEpoch++; rolesEpoch++ }
                }.start()
            },
            onDismiss = { confirmVpn = false },
        )
    }

    @Composable
    private fun SuperPowerCard() {
        val ctx = requireContext()
        val S = com.diegonmarcos.superapp.system.SuperPowerSaving
        val decl = remember { DeviceControls.groups.flatMap { it.rows }.firstOrNull { it.id == S.CONTROL_ID } }
        if (decl == null || DeviceControls.declared(decl.id)?.optBoolean("perms_surface") != true) return
        val on = remember(superEpoch, powerEpoch) { S.isOn(ctx) }
        val steps = remember(superEpoch, powerEpoch) { S.steps(ctx) }
        Column(Modifier.fillMaxWidth().testTag("perms:superpower")) {
            SectionHead("SUPER POWER SAVING — power saving + releases this app's own Device admin and Notification access")
            KvRow(decl.label, if (on) "✓ on" else "◯ off")
            Small(decl.subtitle)
            for (t in steps) {
                KvRow(t.label, (if (t.on == true) "✓ on" else "◯ off") + (if (on) " · remembered: " + (if (t.id == "spm_admin") S.releasedAdmin(ctx) else S.releasedListener(ctx)).let { if (it) "restore on exit" else "was off" } else ""))
                androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth()) {
                    AdbToggleActions(t, ctx, "perms-spm") { runAdbToggle(t) { if (t.id == "spm_admin") S.adminActive(ctx) else S.listenerEnabled(ctx) } }
                }
            }
            KvRow("Accessibility", "untouched")
            val running = remember(superEpoch) { com.diegonmarcos.superapp.system.SuperServices.runningForeground(ctx) }
            val stopped = remember(superEpoch) { com.diegonmarcos.superapp.system.SuperServices.remembered(ctx) }
            for (d in com.diegonmarcos.superapp.system.SuperServices.declared()) KvRow("Service: ${d.label}",
                (if (d.cls in running) "● running" else "○ stopped") + (if (d.cls in stopped) " · paused by Super power saving" else "") +
                    (if (d.keep) " · kept by default" else "") + (if (d.adbLast) " · ADB channel, stopped last" else ""))
            for (c in running.filter { r -> com.diegonmarcos.superapp.system.SuperServices.declared().none { it.cls == r } })
                KvRow("Service: ${c.substringAfterLast('.')}", "● running · undeclared (stopped too by the mode)")
            Small("The VPN stays on by default; the button below asks before stopping it as well.")
            if (on) Small("Paused: double-tap lock falls back to Accessibility (or shows 'paused by Super power saving'); Phone Notifications cannot read notifications.")
            PermButtonRow {
                if (!on) PermButton("On, and stop the VPN too", null) { confirmVpn = true }
                PermButton(if (on) "Turn Super power saving off" else "Turn Super power saving on", null) {
                    val app = ctx.applicationContext
                    shellNote = "Super power saving: switching ${if (on) "off" else "on"}…"
                    Thread {
                        val r = runCatching { if (on) S.turnOff(app) else S.turnOn(app) }.getOrElse { com.diegonmarcos.superapp.system.SuperPowerSaving.Report(false, listOf(it.message ?: "failed")) }
                        view?.post {
                            superReport = r.lines; shellNote = "Super power saving: " + (if (r.ok) "done" else "see the steps below")
                            activity?.let { a -> S.openFallbacks(a, r) }
                            superEpoch++; powerEpoch++; rolesEpoch++
                        }
                    }.start()
                }
            }
            for (l in superReport) Small(l)
        }
    }

    // ── #1687 Power saving: the same declared control as Launcher Quick actions (ui.control_panel power_saving) ──
    private var powerEpoch by mutableIntStateOf(0)

    @Composable
    private fun PowerSavingCard() {
        val ctx = requireContext()
        val decl = remember { DeviceControls.groups.flatMap { it.rows }.firstOrNull { it.id == com.diegonmarcos.superapp.system.PowerSaving.CONTROL_ID } }
        if (decl == null || DeviceControls.declared(decl.id)?.optBoolean("perms_surface") != true) return
        var low by remember { mutableStateOf(com.diegonmarcos.superapp.system.LowPowerMode.statusText()) }
        androidx.compose.runtime.DisposableEffect(Unit) {
            runCatching { com.diegonmarcos.superapp.system.PowerSaving.watch(ctx) }
            val stop = com.diegonmarcos.superapp.system.LowPowerMode.observe { low = com.diegonmarcos.superapp.system.LowPowerMode.statusText(); powerEpoch++ }
            onDispose { stop() }
        }
        val toggle = remember(powerEpoch) { com.diegonmarcos.superapp.system.PowerSaving.toggle(ctx) }
        val wss = remember(powerEpoch) { com.diegonmarcos.superapp.system.PowerSaving.hasSecureSettings(ctx) }
        val up = remember(powerEpoch) { com.diegonmarcos.superapp.system.PowerSaving.channelUp(ctx) }
        val blocked = remember(powerEpoch) { com.diegonmarcos.superapp.system.PowerSaving.blockedReason(ctx) }
        Column(Modifier.fillMaxWidth().testTag("perms:power")) {
            SectionHead("POWER SAVING — the phone's mode (same control as Quick actions)")
            KvRow(decl.label, "${if (toggle.on == true) "✓" else "◯"} ${com.diegonmarcos.superapp.system.PowerSaving.word(toggle.on)} (PowerManager.isPowerSaveMode)")
            KvRow("Permission", (if (wss) "✓ WRITE_SECURE_SETTINGS granted" else "◯ WRITE_SECURE_SETTINGS not granted") +
                " · ADB channel " + (if (up) "✓ connected" else "◯ not connected"))
            KvRow("Low-power mode", low)
            Small(decl.subtitle)
            if (blocked.isNotEmpty()) Small("⚠ $blocked")
            PermButtonRow {
                PermButton(if (toggle.on == true) "Turn power saving off" else "Turn power saving on", null) {
                    val want = toggle.on != true
                    val app = ctx.applicationContext
                    shellNote = "Power saving: switching ${com.diegonmarcos.superapp.system.PowerSaving.word(want)}…"
                    Thread {
                        val r = com.diegonmarcos.superapp.system.PowerSaving.set(app, want)
                        view?.post { shellNote = "Power saving: ${r.channel}: ${r.detail}"; adbToggleDown = r.channelDown; powerEpoch++ }
                    }.start()
                }
                PermButton("Connect on the ADB Shell page", null) { com.diegonmarcos.superapp.shellclient.ShellClient.openArmPage(ctxAny()) }
            }
            androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth()) {
                AdbToggleActions(toggle, ctx, "perms") { runAdbToggle(toggle) { com.diegonmarcos.superapp.system.PowerSaving.isOn(ctx) } }
            }
            PermButtonRow {
                val follow = com.diegonmarcos.superapp.system.LowPowerMode.followSystem
                PermButton(if (follow) "Low-power mode follows system: ON" else "Low-power mode follows system: OFF", null) {
                    com.diegonmarcos.superapp.system.PowerSaving.setFollowSystem(ctx, !follow); low = com.diegonmarcos.superapp.system.LowPowerMode.statusText(); powerEpoch++
                }
            }
            Small("Low-power mode slows: " + com.diegonmarcos.superapp.system.LowPowerMode.config.slows.joinToString(", "))
            if (adbToggleDown) PermButtonRow { PermButton("Open ADB Shell to connect ›", null) { com.diegonmarcos.superapp.shellclient.ShellClient.openArmPage(ctxAny()) } }
            if (adbToggleOutput.isNotBlank()) Small(adbToggleOutput)
        }
    }

    /** The sticky top: "N of M granted, K missing" and Ask all missing. */
    @Composable
    private fun SummaryBar() {
        val act = requireActivity()
        val rows = remember(centerEpoch) { readPermRows(act, act) }
        PermsSummaryBar(rows, act, statusSel) { startWalk() }
    }

    /** #1550 the status filter shared by the sticky KPI strip and the list below it. */
    private val statusSel = androidx.compose.runtime.mutableStateOf(com.diegonmarcos.superapp.perms.StatusFilter.ALL)

    /** Redraw key: bumping it re-reads every row (the old detach+attach, without tearing the fragment down). */
    private var epoch by mutableIntStateOf(0)

    private fun rebuildFragment() { epoch++ }

    private fun ctxAny(): Context = requireContext()

    /** #1688 open a declared chain; a chain that opens nothing says why (toast + the shell note), never silent. */
    private fun openChain(id: String) {
        val act = activity ?: return
        val r = PermsOpenChains.open(act, id) { i -> runCatching { startActivity(i) }.isSuccess }
        if (!r.ok) { shellNote = r.reason; Toast.makeText(act, r.reason, Toast.LENGTH_LONG).show() }
    }

    companion object {
        /** Runtime permissions already handed to the dialog in this process: the auto-request never asks one twice per run. */
        private val askedThisSession: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())
        fun newInstance() = PermissionsFragment()
        private val DARK_VIOLET = 0xFF4C1D95.toInt()
    }

    override fun palette() = LauncherPalette.kit(requireContext())

    @Composable
    override fun Content() {
        // rebuildFragment() bumps the epoch: the whole page is read again, exactly what detach+attach used to do.
        androidx.compose.runtime.key(epoch) { PermsPage() }
    }

    @Composable
    private fun PermsPage() {
        val ctx = requireContext()
        val perms = remember { parseRuntimePermissions() }
        // includeGranted=true: this screen must show the REAL state of every
        // fleet package × development permission, granted or not. Showing only
        // the outstanding ones makes a fully-granted plane look like an empty
        // section — indistinguishable from the bug where READ_LOGS silently
        // vanished because its build.json entry had no `apps` array.
        val priv = remember { PrivilegedGrants.resolve(ctxAny(), includeGranted = true) }
        val plane: ShellPlane? = remember { shellPlane() }

        // ── Tally ─────────────────────────────────────────────────────────
        val runtimeMissing = perms.filter { (_, perm) -> ctxAny().checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED }
        val privMissing = priv.filter { !PrivilegedGrants.isGranted(ctxAny(), it) }
        val pages = remember { buildPageItems(ctxAny()) }
        val pagesMissing = pages.count { it.granted == false }
        Column(Modifier.fillMaxSize()) {
        SummaryBar()
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 10.dp, top = 8.dp, end = 10.dp, bottom = 16.dp)) {
        PowerSavingCard()
        SuperPowerCard()
        SuperVpnConfirm()
        CatalogueHead()
        RolesHeldSection()
        // #1546 the device tools (the libs:perms section with its filter chips and actions, grant here, system pages, per-fleet
        // grants, helper apps, hotspot, recovery) are always on the page again: #1519 had folded them away, closed by default
        SectionHead("Device tools: helper apps, per-fleet grants, ADB, hotspot, recovery")
        run {
        // #1403 the libs:perms section with its filter chips and actions row; adb = this page's shell plane
        // #1546 its own tag namespace: the catalogue list above already answers perms:row:* / perms:summary / perms:progress
        androidx.compose.runtime.CompositionLocalProvider(com.diegonmarcos.superapp.perms.LocalPermsTagNs provides "perms-app") {
            com.diegonmarcos.superapp.perms.AppPermsSection(tick = epoch, adb = remember { permsAdb() }, dense = true)
        }
        Small("${perms.size - runtimeMissing.size + priv.size - privMissing.size} granted here · " +
            "${runtimeMissing.size + privMissing.size} missing here · $pagesMissing need a system page")

        // ── A. GRANT HERE ─────────────────────────────────────────────────
        SectionHead("GRANT HERE — one tap each, or all at once")
        PermButtonRow {
            PermButton("GRANT ALL reachable (${runtimeMissing.size + (if (plane != null) privMissing.size else 0)})", null) {
                if (runtimeMissing.isNotEmpty()) requestAllPermissions(runtimeMissing.map { it.second }.toTypedArray())
                if (plane != null) for (pp in privMissing) grantPrivileged(plane, pp)
                if (runtimeMissing.isEmpty()) rebuildFragment()
            }
        }
        Small("Runtime perms — ✓ Granted · ⏳ Ask each time · ✗ Denied (don't ask) · ◯ Not requested. Policy-restricted ones (SMS/Phone/Call Log) may stay ✗ on non-default handlers.")
        for ((label, perm) in perms) {
            val g = ctxAny().checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
            PermRow(label, g, permissionState(perm), if (g) "OK" else "Request") { requestAllPermissions(arrayOf(perm)) }
        }
        PermRow("Notifications (post)", grantedNotifWrite(ctxAny()), "", "Request") { requestNotificationsPermission() }
        Small("Privileged perms — signature perms Android never asks for. Granted by the privileged plane (embedded adb / Shizuku) via pm grant; persist across reboots + updates." +
            (if (plane == null) " ⚠ No shell channel ready — open ADB Shell and press Connect to enable these buttons." else " Channel: ${plane.name()}"))
        for (pp in priv) {
            val g = PrivilegedGrants.isGranted(ctxAny(), pp)
            PermRow("${pp.label} → ${pp.pkg.substringAfterLast('.')}", g, "", if (g) "OK" else if (plane != null) "Grant" else "needs plane") {
                if (plane != null) grantPrivileged(plane, pp) else Toast.makeText(ctxAny(), "Open ADB Shell and press Connect first", Toast.LENGTH_LONG).show()
            }
        }

        // ── Privileged channel: ONE page (ADB Shell) owns pairing, connecting and the server ──
        // This screen keeps a status chip only. Every control that used to live here (Wireless debugging
        // toggle, Pair, Connect, the server row) is on the ADB Shell page, which every app that links
        // libs:shizuku-adb-debug-tools has; test-adb-shell-one-place.sh holds it to that.
        KvRow("Privileged channel", plane?.let { "✓ ${it.name()}" } ?: "◯ no adb session (${com.diegonmarcos.superapp.system.AdbReadiness.state.value.why.ifBlank { "not checked yet" }})")
        PermButtonRow {
            PermButton("ADB Shell ›", null) { com.diegonmarcos.superapp.shellclient.ShellClient.openArmPage(ctxAny()) }
        }
        // #1682 P3 step 4e: the pairing network helpers (local Wi-Fi, Wi-Fi Direct) live on the owner's ADB Shell page (Perms tab, PairingHelpers).
        // ── Device tuning ────────────────────────────────────────────────
        // The shell-applied settings declared in data/device-tuning.json
        // (phantom-process killer off, ...) — the rows are the declared entries,
        // the light is the state DeviceTuning remembered from its last pass.
        SectionHead("DEVICE TUNING — applied through this app's own shell (data/device-tuning.json)")
        val tuning = DeviceTuning.records(ctxAny()).associateBy { it.id }
        for (e in DeviceTuning.entries()) {
            val r = tuning[e.id]
            val light = when (r?.state) {
                DeviceTuning.State.APPLIED -> "✓ APPLIED"
                DeviceTuning.State.FAILED -> "✗ FAILED"
                else -> "◯ PENDING"
            }
            KvRow(e.title, light + (r?.detail?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""))
            Small(e.why)
        }
        PermButtonRow {
            PermButton("Re-apply device tuning", tuning.values.all { it.state == DeviceTuning.State.APPLIED } && tuning.isNotEmpty()) {
                armPlane(); Toast.makeText(ctxAny(), "Verifying + applying device tuning in background…", Toast.LENGTH_SHORT).show()
            }
        }

        // ── #1602 ESSENTIAL-APP FLAG ──────────────────────────────────────
        // The SuperApp's own flag: ON (default) marks this package essential so the fleet background policy and Super Saving
        // Battery Mode never restrict or force-stop it, which is what keeps it alive on Samsung's aggressive power management.
        // Turning it OFF drops that self-protection so Samsung's super-saving mode is allowed to hibernate the app.
        SectionHead("ESSENTIAL-APP FLAG — keep the SuperApp out of battery hibernation")
        val essProtected = com.diegonmarcos.superapp.bgpolicy.EssentialSelfPolicy.protectedSelf(ctxAny())
        KvRow("Essential-app flag", if (essProtected) "✓ ON — protected from hibernation" else "✗ OFF — may be hibernated")
        Small("ON: battery policy and Super Saving never restrict this app (the default). OFF: Samsung's super-saving mode is allowed to hibernate the app — turn it off only if you want the OS to sleep the SuperApp.")
        PermButtonRow {
            PermButton("Essential-app flag: " + (if (essProtected) "ON" else "OFF"), essProtected) {
                val now = !com.diegonmarcos.superapp.bgpolicy.EssentialSelfPolicy.protectedSelf(ctxAny())
                com.diegonmarcos.superapp.bgpolicy.EssentialSelfPolicy.setProtectedSelf(ctxAny(), now)
                Toast.makeText(ctxAny(),
                    "Essential-app flag " + (if (now) "ON — protected from hibernation" else "OFF — may be hibernated"),
                    Toast.LENGTH_SHORT).show()
                rebuildFragment()
            }
        }

        // ── B. OPEN A SYSTEM PAGE ─────────────────────────────────────────
        SectionHead("OPEN A SYSTEM PAGE — Grant-All can NOT reach these")
        Small("Nothing can grant these for you: each button opens the exact system page, you flip it, come back. Missing ones listed first.")
        for (it in pages.sortedBy { if (it.granted == false) 0 else if (it.granted == null) 1 else 2 })
            PermRow(it.label, it.granted, it.state, "Open", it.open)
        if (!ScreenLocker.isAccessibilityEnabled(ctxAny())) Small(
            "Accessibility on Samsung: 'Open App Info' → ⋮ → 'Allow restricted settings', then reopen Accessibility and enable Cloud SuperApp.")
        // Only offer the manual `adb shell pm grant` when there is NO channel.
        // With the plane up the app grants DUMP itself (it is development-
        // protected, so PrivilegedGrants picks it up), and telling the user to
        // go find a PC for something already handled is advice that is simply
        // false — the kind that trains people to ignore the whole screen.
        if (plane == null && !specialAccessDumpGranted(ctxAny())) {
            ActionButton("Copy DUMP grant command for adb") {
                copy(ctxAny(), "adb shell pm grant ${ctxAny().packageName} android.permission.DUMP")
                Toast.makeText(ctxAny(), "Copied — paste into a shell with adb access", Toast.LENGTH_LONG).show()
            }
        }
        val hcTotal = HealthMetrics.allPermissions.size
        // null while the Health Connect read is in flight, then the granted count.
        val hcGranted by androidx.compose.runtime.produceState<Int?>(null) {
            value = runCatching { HealthConnectGateway.grantedPermissions(requireContext()).size }.getOrDefault(0)
        }
        KvRow("HC perms granted", hcGranted?.let { n ->
            if (n > 0) "✓ $n / $hcTotal granted" else "◯ 0 / $hcTotal — none granted"
        } ?: "checking… / $hcTotal")
        PermButtonRow {
            PermButton("Grant Health Perms", hcGranted?.let { it >= hcTotal && hcTotal > 0 }) { openHealthConnectPerms() }
        }

        // ── C. Already yours ─────────────────────────────────────────────
        SectionHead("SYSTEM AUTO-GRANTED — protection-NORMAL, granted at install")
        for ((label, status) in collectAutoGrantedPerms(ctxAny())) KvRow(label, status)

        Small("Floating nav — grant 'Display over other apps', then toggle the overlay:")
        var navRunning by remember { mutableStateOf(FloatingNavService.isRunning && FloatingNavService.armed) }
        PermButtonRow {
            PermButton("Set Display-over-apps", android.provider.Settings.canDrawOverlays(ctxAny())) { openOverlaySettings() }
            PermButton("Set Modify-system-settings", android.provider.Settings.System.canWrite(ctxAny())) { openWriteSettings() }
            PermButton(if (navRunning) "Stop Floating Nav" else "Start Floating Nav", if (navRunning) true else null, plain = true) {
                toggleFloatingNav { navRunning = it }
            }
        }

        Small("Auto-update — automatic app updates (default ON). Grant 'Install unknown apps' below for no-tap installs:")
        KvRow("Auto-update",
            if (com.diegonmarcos.superapp.updater.AutoUpdatePrefs.enabled(ctxAny())) "✓ ON" else "✗ OFF")
        KvRow("Install unknown apps",
            if (com.diegonmarcos.superapp.updater.AutoUpdatePrefs.canInstallSilently(ctxAny())) "✓ Granted" else "◯ Not granted")
        // "Auto-update is ON but nothing installs quietly" has three different
        // causes and they are indistinguishable from the toggle alone, so the
        // effective state is spelled out rather than left to be guessed.
        val silentCh = com.diegonmarcos.superapp.updater.Fleet.silentChannelName(ctxAny())
        KvRow("Unattended installs",
            if (silentCh != null) "✓ Silent via $silentCh (whole fleet per pass)"
            else "◯ Prompts — capped at ${com.diegonmarcos.superapp.updater.BuildConfig.AU_MAX_PER_PASS}/pass")
        if (silentCh == null) Small(
            "No shell channel, so each update opens a confirm dialog and holds a " +
            "PackageInstaller session until you answer — which is why the unattended pass " +
            "only takes a few apps at a time. Open ADB Shell and press Connect (embedded adb, local server or Shizuku" +
            ") and the pass installs the whole fleet with nothing shown. " +
            "'Install unknown apps' alone is not enough: USER_ACTION_NOT_REQUIRED is honoured " +
            "only for apps this one already installed, so anything installed by hand prompts " +
            "once regardless.")
        PermButtonRow {
            PermButton("Auto-update: " + (if (com.diegonmarcos.superapp.updater.AutoUpdatePrefs.enabled(ctxAny())) "ON" else "OFF"),
                       com.diegonmarcos.superapp.updater.AutoUpdatePrefs.enabled(ctxAny())) {
                val now = !com.diegonmarcos.superapp.updater.AutoUpdatePrefs.enabled(ctxAny())
                com.diegonmarcos.superapp.updater.AutoUpdatePrefs.setEnabled(ctxAny(), now)
                // #894 Cloud Store owns installs when present: no self-update schedule from here.
                if (!com.diegonmarcos.superapp.apps.CloudStoreHandoff.ownsInstalls(ctxAny()))
                    com.diegonmarcos.superapp.updater.Updater.start(ctxAny())
                ConstellationWorker.start(ctxAny())
                Toast.makeText(ctxAny(), "Auto-update " + (if (now) "ON" else "OFF"), Toast.LENGTH_SHORT).show()
                rebuildFragment()
            }
            PermButton("Set Install-unknown-apps",
                       com.diegonmarcos.superapp.updater.AutoUpdatePrefs.canInstallSilently(ctxAny())) { openUnknownAppSourcesSettings() }
        }
        // Gates the UNATTENDED passes only — "Update now" is the user asking,
        // and that still downloads on mobile data.
        KvRow("Update over Wi-Fi only",
            if (com.diegonmarcos.superapp.updater.AutoUpdatePrefs.requireUnmetered(ctxAny())) "✓ ON" else "✗ OFF")
        PermButtonRow {
            PermButton("Update over Wi-Fi only: " + (if (com.diegonmarcos.superapp.updater.AutoUpdatePrefs.requireUnmetered(ctxAny())) "ON" else "OFF"),
                       com.diegonmarcos.superapp.updater.AutoUpdatePrefs.requireUnmetered(ctxAny())) {
                val now = !com.diegonmarcos.superapp.updater.AutoUpdatePrefs.requireUnmetered(ctxAny())
                com.diegonmarcos.superapp.updater.AutoUpdatePrefs.setRequireUnmetered(ctxAny(), now)
                Toast.makeText(ctxAny(), "Update over Wi-Fi only " + (if (now) "ON" else "OFF"), Toast.LENGTH_SHORT).show()
                rebuildFragment()
            }
        }
        // ── RECOVERY ────────────────────────────────────────────────────────
        // Everything above this line assumes the update chain works. This is
        // what is left when it does not.
        //
        // "Set Install-unknown-apps" was the closest thing the screen had to a
        // recovery action and it only granted a PERMISSION — nothing on this
        // screen ever downloaded or installed anything. So a device on a stale
        // build with no privileged channel could not self-update, and therefore
        // could never receive the fix that would let it update: terminal, with
        // a human and a USB cable as the only exit. That does not scale to one
        // fleet, let alone thousands of users.
        val playProtect = com.diegonmarcos.superapp.system.PackageVerifier.state(ctxAny()).on
        KvRow("Play Protect scanning", if (playProtect) "✓ ON" else "◯ OFF")
        Small(
            "Recovery downloads the published build through the ordinary verified " +
            "source and hands it to Android's own installer. It needs NO pairing, " +
            "NO Shizuku and no special access — only the standard install " +
            "confirmation — which is exactly why it still works when nothing else " +
            "does. Play Protect can delay a sideloaded install with a scan; turning " +
            "it off removes Google's malware check on installs, so leave it ON " +
            "unless a recovery install is being blocked.")
        PermButtonRow {
            PermButton("Recovery: install directly", null) {
                startActivity(com.diegonmarcos.superapp.recovery.RecoveryActivity
                    .intent(ctxAny(), null))
            }
            PermButton("Play Protect: " + (if (playProtect) "ON" else "OFF"), playProtect) {
                togglePlayProtect(!playProtect)
            }
        }

        // ── #1601 RESET ──────────────────────────────────────────────────
        SectionHead("RESET — put every permission back to its default state")
        Small("Revokes every runtime permission and resets every app-op, special access, Do Not Disturb, notification access, accessibility and device-admin grant for this app to default. Runs through the ADB Shell channel; needs it connected.")
        shellNote?.let { Small(it) }
        ActionButton("Reset all to default", 0xFF991B1B.toInt()) { confirmResetAll() }
        ResetAllConfirm()

        ActionButton("Copy All Perms Status", DARK_VIOLET) {
            copy(ctxAny(), buildAllPermsStatus(ctxAny()))
            Toast.makeText(ctxAny(), "Copied full permission status", Toast.LENGTH_SHORT).show()
        }
        }
        }
        }
    }

    // ── Reorg helpers (state + button on ONE row; grant-here vs open-a-page) ──
    /** Enqueue the self-healing worker (connect + pm grant the privileged list). */
    private fun armPlane() = runCatching {
        androidx.work.WorkManager.getInstance(ctxAny()).enqueueUniqueWork(
            "privileged-plane", androidx.work.ExistingWorkPolicy.REPLACE,
            androidx.work.OneTimeWorkRequestBuilder<com.diegonmarcos.superapp.system.PrivilegedPlaneWorker>().build())
    }.let { }
    /** startLocalOnlyHotspot has THREE platform preconditions, and failing any
     *  throws or onFailed()s with an opaque code. Check + guide the user through
     *  each in order (measured 2026-09-03: WiFi radio off + location denied both
     *  hit at once). Apps CANNOT enable WiFi (deprecated no-op since Android 10)
     *  or the location toggle programmatically — deep-link to the right panel. */
    /**
     * Play Protect install scanning, on or off.
     *
     * [com.diegonmarcos.superapp.system.PackageVerifier] has been able to do
     * this the whole time and NOTHING on any screen called it — a capability
     * that exists in code with no way for the user to reach it is, from the
     * user's side, a capability that does not exist. It belongs here because
     * the one time it matters is a recovery install being held up by a scan.
     *
     * Reports the channel that did it, and re-reads the three Settings.Global
     * values rather than assuming the write took — [PackageVerifier.Result.ok]
     * is the re-read agreeing, never the call not throwing.
     */
    private fun togglePlayProtect(scan: Boolean) {
        val ctx = ctxAny().applicationContext
        kotlin.concurrent.thread(name = "play-protect-toggle") {
            val r = com.diegonmarcos.superapp.system.PackageVerifier.setScanning(ctx, scan)
            view?.post {
                Toast.makeText(ctxAny(),
                    (if (r.ok) "Play Protect scan ${if (scan) "ON" else "OFF"} via ${r.channel}"
                     else "NOT changed (${r.channel})") + "\n" + r.state.describe(),
                    Toast.LENGTH_LONG).show()
                rebuildFragment()
            }
        }
    }

    /** One row: "✓/◯ label  state" on the left, its own button on the right. */
    @Composable
    private fun PermRow(label: String, granted: Boolean?, state: String, btn: String, onClick: () -> Unit) {
        val ctx = LocalContext.current
        val icon = when (granted) { true -> "✓ "; false -> "◯ "; null -> "? " }
        Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
            // The app's one definition of healthy / failed / cannot-say. These
            // were the literals StatusLight was built from; pointing at it
            // instead is what stops this page and Configs ▸ Panel ▸ Control
            // drifting into two greens that mean the same thing.
            Text(icon + label + (if (state.isNotBlank()) "  ·  $state" else ""), fontSize = 11.5.sp,
                color = Color(com.diegonmarcos.superapp.ui.StatusLight.colour(ctx, com.diegonmarcos.superapp.ui.StatusLight.of(granted))),
                modifier = Modifier.weight(1f))
            PermButton(btn, granted, onClick = onClick, fill = false)
        }
    }

    @Composable
    private fun SectionHead(text: String) {
        Text(text, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = LocalKitPalette.current.accent,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 1.dp))
    }

    /**
     * The privileged (package, permission) rows — resolved by PrivilegedGrants,
     * the same call PrivilegedPlaneWorker makes, so what this screen lists is
     * exactly what the plane grants.
     *
     * The local PrivPerm class this replaced built its rows by iterating each
     * build.json entry's `apps` array, so an entry with no `apps` array (i.e.
     * one that had gone dynamic) produced ZERO rows and dropped off the screen
     * without a trace. Never re-derive the target list here.
     *
     * Grant goes through PrivilegedGrants.grant, which also sets the app-op for
     * appop-backed permissions — `pm grant` alone reports success there while
     * access stays denied.
     */
    private fun grantPrivileged(plane: ShellPlane, t: PrivilegedGrants.Target) {
        val out = PrivilegedGrants.grant(ctxAny(), t) { cmd -> plane.exec(ctxAny(), cmd) }
        Toast.makeText(ctxAny(), "grant ${t.pkg.substringAfterLast('.')} ${t.perm.substringAfterLast('.')}: " +
            (out?.trim()?.take(60) ?: "no output"), Toast.LENGTH_SHORT).show()
    }

    /** #1403 the adb channel the Perms actions row grants through, and the one place that pairs (ADB Shell). */
    private fun permsAdb() = com.diegonmarcos.superapp.perms.PermsAdb(
        channel = object : com.diegonmarcos.superapp.perms.PermsGrant.GrantChannel {
            // #1513 through the shell broker (its allowlist), not a channel of this screen's own
            override fun up() = com.diegonmarcos.superapp.system.AdbReadiness.check(ctxAny()).usable
            override fun exec(command: String): String? =
                (com.diegonmarcos.superapp.shellclient.ShellClient.exec(ctxAny(), command)
                    as? com.diegonmarcos.superapp.shellclient.ShellResult.Ok)?.let { it.stdout + it.stderr }
        },
        connect = { com.diegonmarcos.superapp.shellclient.ShellClient.openArmPage(ctxAny()) },
    )

    /** The one channel-availability check for this screen; null = no shell. */
    private fun shellPlane(): ShellPlane? = ShellPlane.current(ctxAny())

    /** A thing only a system page can grant: state + the page that opens it. */
    private class PageItem(val label: String, val granted: Boolean?, val state: String, val open: () -> Unit)
    private fun buildPageItems(ctx: Context): List<PageItem> {
        fun ok(s: String) = s.trimStart().startsWith("✓")
        val roles = parsePermissionRoles().map { r -> specialAccessRole(ctx, r.role, r.expectedHolders).let { st -> PageItem(r.label, ok(st), st) { openDefaultAppsSettings() } } }
        // #632 DECLARED device-state rows (build.json::ui.permissions.device[]),
        // measured live. Nothing about them is written here: the label and the
        // remedy come from the declaration and the state comes from a system
        // read, so the row renders like every other one and the #622 wizard
        // measures the same thing this page shows.
        val device = parseDeviceItems().map { d ->
            val measured = deviceState(ctx, d)
            PageItem(d.label, measured.first, measured.second) { openDeviceRemedy(ctx, d) }
        }
        // Cloud Vault as the DEFAULT passwords & passkeys provider: read from Settings.Secure, so
        // a value this uid cannot read shows as unknown (null) with the open-settings action, never a guess.
        val vault = if (com.diegonmarcos.superapp.fleetconfig.CredentialProviderStatus.rowVisible(
                com.diegonmarcos.superapp.fleetconfig.CredentialProviderStatus.isInstalled(ctx))) {
            val st = com.diegonmarcos.superapp.fleetconfig.CredentialProviderStatus.status(ctx)
            listOf(PageItem("Cloud Vault — passwords & passkeys provider",
                when (st.overall) {
                    com.diegonmarcos.superapp.fleetconfig.CredentialProviderStatus.State.DEFAULT -> true
                    com.diegonmarcos.superapp.fleetconfig.CredentialProviderStatus.State.UNKNOWN -> null
                    else -> false
                },
                "${st.overall.label} (${st.detail()})") {
                com.diegonmarcos.superapp.fleetconfig.CredentialProviderStatus.open(ctx, st.nextStep)
            })
        } else emptyList()
        // The two special permissions the first-run step also asks for (SpecialPerms): one live
        // read each, one tap to the exact grant (unknown-sources page / ROLE_HOME request).
        val special = com.diegonmarcos.superapp.system.SpecialPerms.facts(ctx).let { f ->
            com.diegonmarcos.superapp.system.SpecialPerms.Kind.values().map { k ->
                val g = com.diegonmarcos.superapp.system.SpecialPerms.granted(k, f)
                PageItem(com.diegonmarcos.superapp.system.SpecialPerms.label(k), g,
                    com.diegonmarcos.superapp.system.SpecialPerms.stateText(k, g)) {
                    com.diegonmarcos.superapp.system.SpecialPerms.request(requireActivity(), k)
                }
            }
        }
        specialAtBuild = com.diegonmarcos.superapp.system.SpecialPerms.snapshot(ctx)
        return special + vault + listOf(
            PageItem("Battery optimization (no-optim)", grantedBatteryOptim(ctx), specialAccessBattery(ctx)) { openBatteryOptimizationSettings() },
            PageItem("Usage stats", EnergyWatchdog.hasUsageAccess(ctx), specialAccessUsageStats(ctx)) { openUsageAccessSettings() },
            PageItem("Notification listener (read)", grantedNotifRead(ctx), specialAccessNotifListener(ctx)) { openNotificationListenerSettings() },
            PageItem("Manage all files", grantedFiles(), specialAccessManageStorage()) { openManageAllFilesSettings() },
            PageItem("Display over other apps", android.provider.Settings.canDrawOverlays(ctx), specialAccessOverlay(ctx)) { openOverlaySettings() },
            PageItem("Modify system settings", android.provider.Settings.System.canWrite(ctx), specialAccessWriteSettings(ctx)) { openWriteSettings() },
            PageItem("Accessibility (lock screen, preferred)", ScreenLocker.isAccessibilityEnabled(ctx), ScreenLocker.statusStringAccessibility(ctx)) { ScreenLocker.openSystemAccessibilitySettings(ctx) },
            PageItem("Device admin (lock fallback)", ScreenLocker.isActive(ctx), ScreenLocker.statusString(ctx)) { if (ScreenLocker.isActive(ctx)) ScreenLocker.openSystemDeviceAdminSettings(ctx) else ScreenLocker.requestActivation(requireActivity()) },
            PageItem("Samsung never-sleeping", null, "unknown until opened") { openSamsungNeverSleepingSettings() },
            PageItem("Dumpsys (DUMP, adb only)", specialAccessDumpGranted(ctx), specialAccessDump(ctx)) { openAppSettings() },
            PageItem("App info / settings", null, "") { openAppSettings() },
        ) + roles + device
    }

    // The special-permission rows re-check on resume. Rebuilt ONLY when their state
    // actually changed since this view was built, so the rebuild's own onResume finds
    // nothing new and stops — never the detach/attach loop described below.
    private var specialAtBuild: Map<com.diegonmarcos.superapp.system.SpecialPerms.Kind, Boolean>? = null
    override fun onResume() {
        super.onResume()
        val before = specialAtBuild
        if (before != null && com.diegonmarcos.superapp.system.SpecialPerms.snapshot(requireContext()) != before)
            view?.post { if (isAdded) rebuildFragment() }
        // #1274 live status: the catalogue rows re-read on every resume (back from a settings page). Only centerEpoch
        // moves, so this cannot start the detach/attach loop described below.
        com.diegonmarcos.superapp.perms.PermsLink.consume()?.let { highlight = it }
        centerEpoch++
        if (walking && awaitingResume) { awaitingResume = false; view?.post { if (isAdded) walkNext() } }
    }

    // NO onResume->rebuildFragment: rebuildFragment() detaches+attaches this
    // fragment, and attach() re-runs onResume, which re-rebuilds — an infinite
    // detach/attach loop that hung and crashed the screen on open (2026-09-03).
    // State refreshes on the next navigation to the tab, which is enough.

    // ── UI helpers ────────────────────────────────────────────────────

    @Composable
    private fun KvRow(key: String, value: String) {
        val p = LocalKitPalette.current
        val ctx = LocalContext.current
        Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
            Text(key, fontSize = 11.sp, color = p.textPrimary.copy(alpha = 0.8f), modifier = Modifier.width(100.dp))
            Text(value, fontSize = 11.sp, color = p.accent, fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f).combinedClickable(onClick = {}, onLongClick = {
                    copy(ctx, "$key: $value")
                    Toast.makeText(ctx, "Copied $key", Toast.LENGTH_SHORT).show()
                }))
        }
    }

    @Composable
    private fun Small(text: String) {
        Text(text, fontSize = 10.5.sp, color = LocalKitPalette.current.textPrimary.copy(alpha = 0.6f),
            modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp))
    }

    @Composable
    private fun ActionButton(label: String, bg: Int = 0xFF7C3AED.toInt(), onClick: () -> Unit) {
        Text(label, fontSize = 11.5.sp, color = Color.White, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp).background(Color(bg)).clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 4.dp).testTag("perm:action:$label"))
    }

    /**
     * A permission button: accent-filled while there is something to do, greyed and ticked once [granted]. [plain] keeps the
     * label as given (the Floating Nav toggle says Start/Stop itself, a tick on it would read as "granted").
     */
    @Composable
    private fun RowScope.PermButton(
        label: String, granted: Boolean?, plain: Boolean = false, fill: Boolean = true, onClick: () -> Unit,
    ) {
        val done = granted == true
        Text(if (done && !plain) "✓ $label" else label, fontSize = 11.sp, maxLines = 2, textAlign = TextAlign.Center,
            color = if (done) Color(0xFF9CA3AF) else Color.White,
            modifier = (if (fill) Modifier.weight(1f) else Modifier)
                .padding(horizontal = 1.5.dp)
                .heightIn(min = 26.dp).background(if (done) Color(0xFF2A2A33) else Color(0xFF7C3AED))
                .clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 3.dp)
                .wrapContentHeight(Alignment.CenterVertically).testTag("perm:button:$label"))
    }

    @Composable
    private fun PermButtonRow(content: @Composable RowScope.() -> Unit) {
        Row(Modifier.fillMaxWidth().padding(top = 3.dp), content = content)
    }

    private fun copy(ctx: Context, v: String) {
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
            ?.setPrimaryClip(ClipData.newPlainText("perms", v))
    }

    // ── Permission request ────────────────────────────────────────────

    private fun requestNotificationsPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            PermAskTracker(requireContext()).markAsked("android.permission.POST_NOTIFICATIONS")
            notifPermLauncher.launch("android.permission.POST_NOTIFICATIONS")
        } else {
            Toast.makeText(requireContext(), "Pre-API 33 — notifications granted by default", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestAllPermissions(perms: Array<String>) {
        val combined = com.diegonmarcos.superapp.perms.PermsRequest.expand(perms.toList()).toTypedArray()
        PermAskTracker(requireContext()).markAskedAll(combined.toList())
        allPermsLauncher.launch(combined)
    }

    private fun permissionState(perm: String): String {
        val ctx = ctxAny()
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(ctx, perm) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) return "✓ Granted"
        val act = activity ?: return "✗ Denied"
        val rationale = runCatching {
            androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(act, perm)
        }.getOrDefault(false)
        if (rationale) return "⏳ Ask each time"
        val askedBefore = PermAskTracker(ctx).hasBeenRequested(perm)
        return if (askedBefore) "✗ Denied (don't ask)" else "◯ Not requested"
    }

    // ── Settings openers ─────────────────────────────────────────────

    private fun openAppSettings() {
        // #1688 the declared Open chain (PermsOpenChains["app_info"]): resolved before launch, reason when none opens.
        openChain("app_info")
    }

    private fun openUsageAccessSettings() {
        // #1688 the declared Open chain (PermsOpenChains["usage_access"]): resolved before launch, reason when none opens.
        openChain("usage_access")
    }

    private fun openOverlaySettings() {
        // #1688 the declared Open chain (PermsOpenChains["overlay"]): resolved before launch, reason when none opens.
        openChain("overlay")
    }

    private fun openUnknownAppSourcesSettings() {
        // #1688 the declared Open chain (PermsOpenChains["unknown_sources"]): resolved before launch, reason when none opens.
        openChain("unknown_sources")
    }

    private fun openDefaultAppsSettings() {
        // #1688 the declared Open chain (PermsOpenChains["default_apps"]): resolved before launch, reason when none opens.
        openChain("default_apps")
    }

    private fun openBatteryOptimizationSettings() {
        // #1688 the declared Open chain (PermsOpenChains["battery_opt"]): resolved before launch, reason when none opens.
        openChain("battery_opt")
    }

    private fun openManageAllFilesSettings() {
        // #1688 the declared Open chain (PermsOpenChains["all_files"]): resolved before launch, reason when none opens.
        openChain("all_files")
    }

    private fun openSamsungNeverSleepingSettings() {
        val ctx = requireContext()
        for (intent in listOf(
            android.content.Intent("com.samsung.android.sm.ACTION_BACKGROUND_USAGE_LIMITS"),
            android.content.Intent().setComponent(android.content.ComponentName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity")),
            android.content.Intent().setComponent(android.content.ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity")),
            android.content.Intent(android.provider.Settings.ACTION_BATTERY_SAVER_SETTINGS),
            android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", ctx.packageName, null)),
        )) {
            if (intent.resolveActivity(ctx.packageManager) != null) { runCatching { startActivity(intent) }; return }
        }
    }

    private fun openNotificationListenerSettings() {
        // #1688 the declared Open chain (PermsOpenChains["notification_listener"]): resolved before launch, reason when none opens.
        openChain("notification_listener")
    }

    private fun openWriteSettings() {
        // #1688 the declared Open chain (PermsOpenChains["write_settings"]): resolved before launch, reason when none opens.
        openChain("write_settings")
    }

    private fun openHealthConnectPerms() {
        // Health Connect's own request dialog first (steps / distance / calories); it falls back to HC settings itself.
        if (runCatching { startActivity(android.content.Intent(ctxAny(),
                com.diegonmarcos.superapp.notificationcenter.HealthGrantActivity::class.java)) }.isSuccess) return
        val tried = sequenceOf(
            android.content.Intent("androidx.health.ACTION_HEALTH_CONNECT_SETTINGS"),
            android.content.Intent("android.health.connect.action.HEALTH_HOME_SETTINGS"),
        ).mapNotNull { intent -> runCatching { startActivity(intent); intent }.getOrNull() }.firstOrNull()
        if (tried == null) {
            runCatching { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("market://details?id=com.google.android.apps.healthdata"))) }
        }
    }

    private fun toggleFloatingNav(setRunning: (Boolean) -> Unit) {
        // #775: the button's state is `armed`; a running service may be only
        // the badge host, and stopping it would take the badges with it.
        if (FloatingNavService.isRunning && FloatingNavService.armed) {
            FloatingNavService.setEnabled(ctxAny(), false)
            Toast.makeText(ctxAny(), "Floating nav stopped", Toast.LENGTH_SHORT).show()
            setRunning(false)
        } else {
            val ok = FloatingNavService.setEnabled(ctxAny(), true)
            Toast.makeText(ctxAny(),
                if (ok) "Floating nav started" else "Grant 'Display over other apps' first",
                Toast.LENGTH_SHORT).show()
            setRunning(ok)
        }
    }

    // ── Grant predicates ─────────────────────────────────────────────

    private fun grantedNotifWrite(ctx: Context): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= 33)
            androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        else true

    private fun grantedNotifRead(ctx: Context): Boolean = runCatching {
        androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
    }.getOrDefault(false)

    private fun grantedFiles(): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= 30) runCatching { android.os.Environment.isExternalStorageManager() }.getOrDefault(false) else true

    private fun grantedBatteryOptim(ctx: Context): Boolean = runCatching {
        (ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager)
            .isIgnoringBatteryOptimizations(ctx.packageName)
    }.getOrDefault(false)

    // ── Special-access status ─────────────────────────────────────────

    private fun specialAccessBattery(ctx: Context): String = try {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        if (pm?.isIgnoringBatteryOptimizations(ctx.packageName) == true) "✓ Whitelisted (no Doze)" else "◯ Subject to Doze"
    } catch (_: Throwable) { "—" }

    private fun specialAccessLauncher(ctx: Context): String = try {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val rm = ctx.getSystemService(android.app.role.RoleManager::class.java)
            if (rm?.isRoleHeld(android.app.role.RoleManager.ROLE_HOME) == true) "✓ Default home/launcher" else "◯ Not default"
        } else "— (pre-API 29)"
    } catch (_: Throwable) { "—" }

    private data class RoleSpec(val label: String, val role: String, val expectedHolders: List<String>)

    private fun specialAccessRole(ctx: Context, role: String, expected: List<String>): String = try {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) "— (pre-API 29)"
        else {
            val rm = ctx.getSystemService(android.app.role.RoleManager::class.java)
            when {
                rm?.isRoleAvailable(role) != true -> "— (unsupported)"
                role == android.app.role.RoleManager.ROLE_DIALER -> {
                    val tm = ctx.getSystemService(android.telecom.TelecomManager::class.java)
                    val holder = runCatching { tm?.defaultDialerPackage }.getOrNull()
                    when {
                        holder.isNullOrBlank()    -> "◯ none — set in Default apps"
                        expected.contains(holder) -> "✓ $holder"
                        else                      -> "◯ $holder — set in Default apps"
                    }
                }
                rm.isRoleHeld(role) -> "✓ held by this app"
                else                -> "◯ set in Default apps"
            }
        }
    } catch (_: Throwable) { "—" }

    private fun specialAccessOverlay(ctx: Context): String = try {
        if (android.provider.Settings.canDrawOverlays(ctx)) "✓ Allowed" else "◯ Not allowed"
    } catch (_: Throwable) { "—" }

    private fun specialAccessWriteSettings(ctx: Context): String = try {
        if (android.provider.Settings.System.canWrite(ctx)) "✓ Allowed" else "◯ Not allowed"
    } catch (_: Throwable) { "—" }

    private fun specialAccessUsageStats(ctx: Context): String = try {
        val aom = ctx.getSystemService(android.app.AppOpsManager::class.java)
        val mode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q)
            aom?.unsafeCheckOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), ctx.packageName)
        else
            @Suppress("DEPRECATION") aom?.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), ctx.packageName)
        when (mode) {
            android.app.AppOpsManager.MODE_ALLOWED -> "✓ Allowed"
            null -> "—"
            else -> "◯ Not allowed"
        }
    } catch (_: Throwable) { "—" }

    private fun specialAccessNotifListener(ctx: Context): String = try {
        val flat = android.provider.Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners").orEmpty()
        if (flat.split(":").any { it.startsWith("${ctx.packageName}/") }) "✓ Allowed" else "◯ Not allowed"
    } catch (_: Throwable) { "—" }

    private fun specialAccessDumpGranted(ctx: Context): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(ctx, "android.permission.DUMP") ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    // "needs one-time adb pm grant" was printed unconditionally, which stopped
    // being true the moment the embedded plane could grant DUMP itself. Read
    // the live channel state instead of asserting a workflow.
    private fun specialAccessDump(ctx: Context): String =
        if (specialAccessDumpGranted(ctx)) "✓ Granted"
        else if (shellPlane() != null) "◯ Not granted — grantable here (privileged plane up)"
        else "◯ Not granted — needs one-time adb pm grant"

    private fun specialAccessManageStorage(): String = try {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R)
            if (android.os.Environment.isExternalStorageManager()) "✓ Allowed (R+)" else "◯ Not allowed (R+)"
        else "— (pre-API 30 — uses storage perms)"
    } catch (_: Throwable) { "—" }

    // ── Data parsers ──────────────────────────────────────────────────

    private fun parseRuntimePermissions(): List<Pair<String, String>> {
        val raw = runCatching {
            String(android.util.Base64.decode(BuildConfig.UI_PERMISSIONS_RUNTIME_B64, android.util.Base64.DEFAULT))
        }.getOrDefault("[]")
        val arr = runCatching { org.json.JSONArray(raw) }.getOrDefault(org.json.JSONArray())
        val out = mutableListOf<Pair<String, String>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val label = o.optString("label"); val perm = o.optString("perm")
            if (label.isBlank() || perm.isBlank()) continue
            out.add(label to perm)
        }
        return out
    }

    /** One DECLARED device-state row (#632): build.json::ui.permissions.device[]. */
    private class DeviceSpec(val key: String, val label: String, val want: String, val remedy: String, val intents: List<String> = emptyList())

    private fun parseDeviceItems(): List<DeviceSpec> {
        val raw = runCatching {
            String(android.util.Base64.decode(BuildConfig.UI_PERMISSIONS_DEVICE_B64, android.util.Base64.DEFAULT))
        }.getOrDefault("[]")
        val arr = runCatching { org.json.JSONArray(raw) }.getOrDefault(org.json.JSONArray())
        val out = mutableListOf<DeviceSpec>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val key = o.optString("key"); val label = o.optString("label")
            if (key.isBlank() || label.isBlank()) continue
            out.add(DeviceSpec(key, label, o.optString("want"), o.optString("remedy"),
                o.optJSONArray("remedy_intents")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()))
        }
        return out
    }

    /**
     * A declared device row's state, MEASURED. Nothing here reads a preference
     * or a remembered flag — that is the whole point of the row (#622/#452):
     * Play Protect is device state that GMS can re-arm behind us, so a stored
     * "done" would be green on a phone that is scanning again.
     *
     * Three outcomes, not two. `null` granted means CANNOT VERIFY, which is a
     * real answer here: Settings.Global.getInt needs a default and the default
     * is the stock-GMS value, so an absent key reads exactly like a consented
     * device. [PackageVerifier.readable] separates them, and an unverifiable row
     * says so and sends the user to look rather than showing a tick.
     */
    private fun deviceState(ctx: Context, spec: DeviceSpec): Pair<Boolean?, String> = when (spec.key) {
        "play_protect_off" -> {
            if (!PackageVerifier.readable(ctx))
                null to "— cannot verify on this device — check " + spec.remedy
            else PackageVerifier.state(ctx).let { st ->
                if (!st.on) true to "✓ OFF — sideloaded installs are not scanned or delayed"
                else false to "◯ ON — " + st.describe() + " — " + spec.remedy
            }
        }
        // #1508 RAM Plus: read-only, measured; the desired state is the row's declared `want` (default on).
        "ram_plus" -> RamPlus.read(ctx).let { on ->
            RamPlus.satisfied(on, spec.want.ifBlank { "on" }) to when (RamPlus.satisfied(on, spec.want.ifBlank { "on" })) {
                null -> "— cannot verify on this device — check " + spec.remedy
                true -> if (on == true) "✓ ON" else "✓ OFF"
                else -> (if (on == true) "◯ ON" else "◯ OFF") + " — wanted ${spec.want.ifBlank { "on" }.uppercase()} — " + spec.remedy
            }
        }
        // A key nobody measures is not a row: saying so beats drawing it grey
        // forever, which is how an unimplemented declaration hides.
        else -> null to "— no measurement declared for \"${spec.key}\""
    }

    /**
     * Take the user to where the declared row can actually be changed.
     *
     * This app CANNOT flip Play Protect: the three Settings.Global values behind
     * it need WRITE_SECURE_SETTINGS, which no sideloaded APK holds — so this is
     * the #616 All-Files-Access shape, not a pretend toggle. (With the embedded
     * adb channel paired, the Play Protect button in the Installs block above
     * does write them, through PackageVerifier.setScanning. That is the
     * privileged path and this row does not depend on it.)
     *
     * Every rung says where it landed or why it could not, because an action
     * that opens nothing and reports nothing is indistinguishable from a dead
     * button.
     */
    private fun openDeviceRemedy(ctx: Context, spec: DeviceSpec) {
        if (spec.key == "ram_plus") {
            // Ask: a prompt that deep-links to Device care > Memory > RAM Plus; the owner flips it, this app never does.
            val i = RamPlus.intent(ctx, spec.intents)
            if (runCatching { startActivity(i) }.isSuccess) Toast.makeText(ctx, spec.remedy, Toast.LENGTH_LONG).show()
            else Toast.makeText(ctx, "Could not open RAM Plus. " + spec.remedy, Toast.LENGTH_LONG).show()
            return
        }
        if (spec.key != "play_protect_off") {
            Toast.makeText(ctx, "No remedy page declared for ${spec.key}", Toast.LENGTH_LONG).show()
            return
        }
        val gms = android.content.Intent().setClassName(
            "com.google.android.gms", "com.google.android.gms.security.settings.VerifyAppsSettingsActivity")
        val ladder = listOf(
            gms to "Play Protect settings",
            android.content.Intent("com.android.settings.action.SECURITY_SETTINGS") to "Security settings",
            android.content.Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS) to "Security settings",
            android.content.Intent(android.provider.Settings.ACTION_SETTINGS) to "System settings",
        )
        for ((intent, where) in ladder) {
            if (intent.resolveActivity(ctx.packageManager) == null) continue
            if (runCatching { startActivity(intent) }.isSuccess) {
                Toast.makeText(ctx, "$where — " + spec.remedy, Toast.LENGTH_LONG).show()
                return
            }
        }
        Toast.makeText(ctx, "Nothing on this device answers for Play Protect settings. " + spec.remedy,
            Toast.LENGTH_LONG).show()
    }

    private fun parsePermissionRoles(): List<RoleSpec> {
        val raw = runCatching {
            String(android.util.Base64.decode(BuildConfig.UI_PERMISSIONS_ROLES_B64, android.util.Base64.DEFAULT))
        }.getOrDefault("[]")
        val arr = runCatching { org.json.JSONArray(raw) }.getOrDefault(org.json.JSONArray())
        val out = mutableListOf<RoleSpec>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val label = o.optString("label"); val role = o.optString("role")
            if (label.isBlank() || role.isBlank()) continue
            val holdersArr = o.optJSONArray("expected_holders")
            val holders = mutableListOf<String>()
            if (holdersArr != null) for (j in 0 until holdersArr.length()) {
                val h = holdersArr.optString(j); if (h.isNotBlank()) holders.add(h)
            }
            out.add(RoleSpec(label, role, holders))
        }
        return out
    }

    private fun collectAutoGrantedPerms(ctx: Context): List<Pair<String, String>> = try {
        val pm = ctx.packageManager
        @Suppress("DEPRECATION")
        val info = pm.getPackageInfo(ctx.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
        val requested = info.requestedPermissions ?: return emptyList()
        val flags = info.requestedPermissionsFlags ?: IntArray(requested.size)
        val out = mutableListOf<Pair<String, String>>()
        for ((i, perm) in requested.withIndex()) {
            val grantedAtInstall = (flags.getOrNull(i) ?: 0) and android.content.pm.PackageInfo.REQUESTED_PERMISSION_GRANTED != 0
            if (!grantedAtInstall) continue
            val info2 = runCatching { pm.getPermissionInfo(perm, 0) }.getOrNull()
            val base = (info2?.protectionLevel ?: -1) and android.content.pm.PermissionInfo.PROTECTION_MASK_BASE
            if (base == android.content.pm.PermissionInfo.PROTECTION_DANGEROUS) continue
            val tag = when (base) {
                android.content.pm.PermissionInfo.PROTECTION_NORMAL    -> "NORMAL"
                android.content.pm.PermissionInfo.PROTECTION_SIGNATURE -> "SIGNATURE"
                else -> "?"
            }
            out.add(perm.removePrefix("android.permission.").take(36) to "✓ auto · $tag")
        }
        out
    } catch (_: Throwable) { emptyList() }

    private fun buildAllPermsStatus(ctx: Context): String = buildString {
        appendLine("Cloud SuperApp — permission status")
        appendLine("pkg: ${ctx.packageName}")
        appendLine()
        appendLine("== Runtime ==")
        for ((label, perm) in parseRuntimePermissions()) appendLine("$label: ${permissionState(perm)}")
        appendLine()
        appendLine("== Special access ==")
        appendLine("Battery Optimization: ${specialAccessBattery(ctx)}")
        appendLine("Default launcher: ${specialAccessLauncher(ctx)}")
        for (r in parsePermissionRoles()) appendLine("${r.label}: ${specialAccessRole(ctx, r.role, r.expectedHolders)}")
        appendLine("Usage stats: ${specialAccessUsageStats(ctx)}")
        appendLine("Notif. listener: ${specialAccessNotifListener(ctx)}")
        appendLine("Manage all files: ${specialAccessManageStorage()}")
        appendLine("Display over apps: ${specialAccessOverlay(ctx)}")
        appendLine("Modify system settings: ${specialAccessWriteSettings(ctx)}")
        appendLine("Dumpsys (DUMP): ${specialAccessDump(ctx)}")
        appendLine("Lock-screen accessibility: ${ScreenLocker.statusStringAccessibility(ctx)}")
        appendLine("Device admin (lock): ${ScreenLocker.statusString(ctx)}")
        appendLine()
        appendLine("== Auto-granted (NORMAL) ==")
        for ((label, status) in collectAutoGrantedPerms(ctx)) appendLine("$label: $status")
    }
}

// #1688 kept in this file: P7 (perms guard) allows settings intents only on the Perms page.

/**
 * #1688 every "Open" link of Configs > Perms as a DECLARED fallback chain: candidates tried in order, each one
 * resolved (PackageManager) BEFORE it is launched, the first that resolves AND starts wins; none ⇒ a reason, never a
 * crash and never a silent no-op. Every chain ends on this app's App info page, which always exists.
 *
 * Root cause of the broken "Default home app" Open: SpecialPerms.request fired RoleManager.createRequestRoleIntent
 * (ROLE_HOME) first and counted the launch as success. On One UI the home role request is not offered to a launcher
 * (the dialog finishes at once with RESULT_CANCELED, also when the role is already held), so nothing appeared and the
 * Settings fallback never ran. The home chain now opens the Home settings page first.
 */
object PermsOpenChains {

    /** One candidate: an action, optionally scoped to this package (package:<pkg> data), or the role request. */
    data class Step(val action: String, val scoped: Boolean = false, val role: String = "") {
        val isRole: Boolean get() = role.isNotEmpty()
    }

    const val ROLE_HOME = "android.app.role.HOME"
    private val APP_INFO = Step(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, scoped = true)

    /** id → chain. Ids are the Open links of the page (PermissionsFragment.buildPageItems). */
    val chains: Map<String, List<Step>> = mapOf(
        "home" to listOf(Step(android.provider.Settings.ACTION_HOME_SETTINGS), Step(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS), Step("", role = ROLE_HOME), APP_INFO),
        "default_apps" to listOf(Step(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS), Step(android.provider.Settings.ACTION_SETTINGS), APP_INFO),
        "battery_opt" to listOf(Step(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), APP_INFO),
        "usage_access" to listOf(Step(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS), APP_INFO),
        "notification_listener" to listOf(Step(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS), APP_INFO),
        "all_files" to listOf(Step(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, scoped = true), Step(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION), APP_INFO),
        "overlay" to listOf(Step(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, scoped = true), Step(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION), APP_INFO),
        "write_settings" to listOf(Step(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS, scoped = true), Step(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS), APP_INFO),
        "unknown_sources" to listOf(Step(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, scoped = true), Step(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES), APP_INFO),
        "battery_saver" to listOf(Step(android.provider.Settings.ACTION_BATTERY_SAVER_SETTINGS), APP_INFO),
        "app_info" to listOf(APP_INFO),
    )

    fun intent(ctx: Context, s: Step): android.content.Intent? = runCatching {
        if (s.isRole) {
            if (android.os.Build.VERSION.SDK_INT < 29) return@runCatching null
            val rm = ctx.getSystemService(android.app.role.RoleManager::class.java) ?: return@runCatching null
            if (!rm.isRoleAvailable(s.role)) return@runCatching null
            rm.createRequestRoleIntent(s.role)
        } else android.content.Intent(s.action).apply { if (s.scoped) data = android.net.Uri.fromParts("package", ctx.packageName, null) }
    }.getOrNull()

    data class Opened(val ok: Boolean, val via: String, val reason: String)

    /** Try [id]'s chain; [launch] starts one resolved intent and answers whether it started. */
    fun open(ctx: Context, id: String, launch: (android.content.Intent) -> Boolean = { i -> defaultLaunch(ctx, i) }): Opened {
        val chain = chains[id] ?: return Opened(false, "", "no Open chain declared for $id")
        val tried = ArrayList<String>()
        for (s in chain) {
            val name = if (s.isRole) "role request ${s.role}" else s.action + (if (s.scoped) " (this app)" else "")
            val i = intent(ctx, s)
            val resolves = i != null && runCatching { i.resolveActivity(ctx.packageManager) != null }.getOrDefault(false)
            if (!resolves) { tried += "$name: not on this device"; continue }
            if (runCatching { launch(i!!) }.getOrDefault(false)) return Opened(true, name, "")
            tried += "$name: refused to start"
        }
        return Opened(false, "", "No settings page could be opened. " + tried.joinToString("; "))
    }

    private fun defaultLaunch(ctx: Context, i: android.content.Intent): Boolean = runCatching {
        if (ctx !is android.app.Activity) i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i); true
    }.getOrDefault(false)
}

package com.diegonmarcos.superapp.configs

import android.content.Context
import com.diegonmarcos.superapp.adbdebug.ChannelLog
import com.diegonmarcos.superapp.adbdebug.ChannelMode
import com.diegonmarcos.superapp.adbdebug.LaunchVia
import com.diegonmarcos.superapp.adbdebug.ChannelModePrefs
import com.diegonmarcos.superapp.adbdebug.ChannelOps
import com.diegonmarcos.superapp.adbdebug.ChannelFacts
import com.diegonmarcos.superapp.adbdebug.ConnectExecutor
import com.diegonmarcos.superapp.adbdebug.ConnectOutcome
import com.diegonmarcos.superapp.adbdebug.ConnectPlan
import com.diegonmarcos.superapp.adbdebug.ConnectStatus
import com.diegonmarcos.superapp.adbdebug.DeviceConnectExecutor
import com.diegonmarcos.superapp.adbdebug.LaunchViaPrefs
import com.diegonmarcos.superapp.adbdebug.SetupAction
import com.diegonmarcos.superapp.adbdebug.ShizukuStatus
import com.diegonmarcos.superapp.adbdebug.StepId
import com.diegonmarcos.superapp.adbdebug.StepResult
import com.diegonmarcos.superapp.perms.PermState
import com.diegonmarcos.superapp.perms.PermsLink

/**
 * What a cockpit run ended as: [failure] = a FAILED step's sentence (the Connection light goes red on it), [note] = the line the
 * page shows, [waiting] = the step a Connect run stopped at for the person (the page goes on from it by itself once it is done).
 */
data class CockpitResult(val note: String, val failure: String? = null, val waiting: StepId? = null)

/**
 * The cockpit's verbs on a real device. Every one is blocking (call off the main thread), logs to [ChannelLog.shared] (the stream the
 * Logs panel tails) and returns the line the page shows. Nothing here forces past a consent gate: pairing code, Shizuku's authorize
 * prompt, Developer Options and the Wireless debugging switch are opened for the person and the run stops there.
 */
object AdbCockpitOps {
    private val log get() = ChannelLog.shared

    /** The permissions the channel itself needs: WRITE_SECURE_SETTINGS (keeper) and POST_NOTIFICATIONS (the pairing code is typed into one). */
    fun required(r: PermRowState) = r.status.state != PermState.NOT_APPLICABLE &&
        (r.entry.id == "write_secure_settings" || r.entry.permission == "android.permission.POST_NOTIFICATIONS")

    /** The Permissions catalogue, read for the cockpit's Perms light. */
    fun digest(ctx: Context): AdbCockpitModel.PermsDigest? = runCatching {
        val req = readPermRows(ctx, null).filter { required(it) }
        val miss = req.filter { it.status.missing }
        AdbCockpitModel.PermsDigest(req.size, miss.map { it.entry.label }, miss.any { it.entry.permission == "android.permission.POST_NOTIFICATIONS" })
    }.getOrNull()

    /** Connect step 0: grant what is missing, over a channel that is ALREADY up. No channel yet: say so and go on (the later steps build it). */
    fun grantPerms(ctx: Context): String {
        val miss = runCatching { readPermRows(ctx, null).filter { required(it) && it.status.missing } }.getOrDefault(emptyList())
        if (miss.isEmpty()) { log.add("autofix", "perms: all required granted"); return "perms ok" }
        val ch = BrokerGrantChannel(ctx)
        if (!ch.up()) {
            log.add("autofix", "perms: ${miss.size} missing, no channel up yet - grant from the Perms tab (not a gate)")
            return "${miss.size} perms missing, no channel to grant with yet"
        }
        var bad = 0
        for (l in adbLinesFor(ctx, miss)) { val o = ch.exec(l.command); if (o != null && o.startsWith("error:")) bad++ }
        log.add("autofix", "perms: ${miss.size} missing, grant lines sent" + if (bad > 0) ", $bad refused" else "")
        return "perms granted via the channel" + if (bad > 0) " ($bad refused)" else ""
    }

    /**
     * The Connect plan with the consent gates opened for the person. Developer Options off -> About phone (it only appears after seven
     * Build-number taps); Wireless debugging: try the silent switch (needs WRITE_SECURE_SETTINGS), else open its Settings screen; Shizuku
     * not running -> open Shizuku. Each returns needsUser, so [ConnectPlan.run] stops there and a second tap resumes from fresh facts.
     */
    class AutoFixExecutor(ctx: Context) : ConnectExecutor {
        private val app = ctx.applicationContext
        private val inner = DeviceConnectExecutor(app)
        override fun facts(): ChannelFacts = inner.facts()
        override fun run(step: StepId): StepResult = when (step) {
            StepId.DEV_OPTIONS -> { ChannelOps.setup(app, SetupAction.OPEN_ABOUT_PHONE); StepResult(false, "opened About phone", needsUser = true) }
            StepId.WIRELESS_DEBUG -> inner.run(step).let { r ->
                if (r.ok) r else { ChannelOps.setup(app, SetupAction.OPEN_WIRELESS_DEBUGGING); StepResult(false, "${r.detail}; opened Wireless debugging", needsUser = true) }
            }
            StepId.SHIZUKU_RUNNING -> inner.run(step).let { r ->
                if (r.ok) r else { ShizukuStatus.act(app); StepResult(false, r.detail, needsUser = true) }
            }
            else -> inner.run(step)
        }
    }

    fun result(o: ConnectOutcome): CockpitResult = when (o.status) {
        ConnectStatus.SUCCESS -> CockpitResult("Connected.")
        ConnectStatus.WAITING_FOR_USER -> CockpitResult(o.explanation ?: "Waiting for you.", waiting = o.failedStep)
        ConnectStatus.FAILED -> CockpitResult(o.explanation ?: "Failed.", o.explanation)
    }

    /**
     * Connect, the page's ONE connect and its auto-fix: grant the perms, then walk the selected mode's whole plan in dependency order
     * to a real shell round trip, opening each consent gate for the person. It stops at the first step only the person can finish;
     * the page goes on from there by itself once that step is done ([AdbCockpitModel.resumes]), and a tap resumes it too.
     */
    fun connect(ctx: Context): CockpitResult {
        val app = ctx.applicationContext
        return connect(ChannelModePrefs.current(app), LaunchViaPrefs.current(app), ChannelModePrefs.ownsServer(), AutoFixExecutor(app)) { grantPerms(app) }
    }

    /** [connect] over [exec] (a fake in tests): perms first, then every step of [mode]'s plan, none skipped unless it is already true. */
    fun connect(mode: ChannelMode, via: LaunchVia, ownsServer: Boolean, exec: ConnectExecutor, grant: () -> String): CockpitResult {
        // Only the selected mode's own prerequisites are walked; no step of another mode, and no server start.
        log.add("autofix", "start, ${AdbCockpitModel.modeName(mode)} mode (" + AdbCockpitModel.autoFixOrder(mode, via, ownsServer).joinToString(", ") + ")")
        val perms = grant()
        val o = ConnectPlan.run(mode, via, exec, log, ownsServer)
        val r = result(o)
        log.add("autofix", when (o.status) {
            ConnectStatus.SUCCESS -> "done: connected"
            ConnectStatus.WAITING_FOR_USER -> "paused at ${o.failedStep?.label}: your turn, Connect goes on once it is done"
            ConnectStatus.FAILED -> "FAILED at ${o.failedStep?.label}"
        })
        return r.copy(note = r.note + if (perms.contains("missing")) " ($perms)" else "")
    }

    /** One row action. [paired] / [devOn] come from the facts the row was drawn from. */
    fun run(ctx: Context, what: AdbCockpitModel.Do, paired: Boolean, devOn: Boolean): CockpitResult {
        val app = ctx.applicationContext
        fun said(s: String) = CockpitResult(s)
        return when (what) {
            AdbCockpitModel.Do.OPEN_PERMS -> { PermsLink.open(ctx, "adb-shell", emptyList()); said("Opened Perms.") }
            AdbCockpitModel.Do.OPEN_DEV -> said(ChannelOps.setup(app, if (devOn) SetupAction.OPEN_DEVELOPER_OPTIONS else SetupAction.OPEN_ABOUT_PHONE))
            AdbCockpitModel.Do.OPEN_WD -> said(ChannelOps.setup(app, SetupAction.OPEN_WIRELESS_DEBUGGING))
            AdbCockpitModel.Do.SHIZUKU_START -> said(ChannelOps.setup(app, SetupAction.OPEN_SHIZUKU))
            AdbCockpitModel.Do.SHIZUKU_AUTH -> said(ChannelOps.setup(app, SetupAction.REQUEST_SHIZUKU))
            AdbCockpitModel.Do.LOCAL_PAIR -> said(ChannelOps.setup(app, SetupAction.PAIR))
            AdbCockpitModel.Do.LOCAL_START -> said(ChannelOps.startServer(app, restart = false))
            AdbCockpitModel.Do.LOCAL_STOP -> said(ChannelOps.stopServer(app))
            AdbCockpitModel.Do.LOCAL_RESTART -> said(ChannelOps.startServer(app, restart = true))
            // Explicit taps go through the lib's own connect path (ShellAccess.ensure via ChannelOps.reconnect), never the raw embedded autoConnect.
            AdbCockpitModel.Do.EMB_INIT -> if (!paired) said(ChannelOps.setup(app, SetupAction.PAIR)) else said(ChannelOps.reconnect(app))
            AdbCockpitModel.Do.EMB_RESET -> { ChannelOps.disconnect(app); said("Reset: " + ChannelOps.reconnect(app)) }
            AdbCockpitModel.Do.CONNECT -> connect(app)
            AdbCockpitModel.Do.DISCONNECT -> said(ChannelOps.disconnect(app))
            AdbCockpitModel.Do.RECONNECT -> said(ChannelOps.reconnect(app))
        }
    }
}

package com.diegonmarcos.superapp.adbdebug

import android.content.Context
import android.content.Intent

/** The two things [ShellAccess.ensure] can do to bring a channel up. */
enum class EnsureRung { EMBEDDED_ADB, SHIZUKU }

/**
 * Getting a shell channel, rather than just reporting that there isn't one.
 *
 * [ShellChannels.active] answers "is one ready?" — useful for a status line,
 * useless for a button. A button that needs SHELL privilege should START the
 * flow that grants it, so this takes the one action that moves the user forward
 * IN THE CHOSEN MODE, and only there: every mode is its own channel.
 *
 *   - Shizuku mode: re-bind Shizuku (ask for its permission, or open its app).
 *     It never touches the embedded adb client.
 *   - Embedded adb and Local server modes: reconnect the app's own adb client
 *     to adbd over mDNS with the keys of its one-time pairing. They never bind,
 *     prompt for or open Shizuku.
 *   - Auto: the declared ladder, internal first: the adb client, then Shizuku.
 *
 * Nothing here launches the app_process server: a connected adbd session IS
 * the shell channel, there is nothing further to start.
 */
object ShellAccess {

    /** Shizuku's own package. Not configuration — it is the app's identity. */
    private const val SHIZUKU_PKG = "moe.shizuku.privileged.api"

    /** What [ensure] may try in [mode], in order. Pure: the rule the modes' independence rests on. */
    fun rungs(mode: ChannelMode): List<EnsureRung> = when (mode) {
        ChannelMode.SHIZUKU -> listOf(EnsureRung.SHIZUKU)
        ChannelMode.EMBEDDED_ONLY, ChannelMode.LOCAL_SERVER -> listOf(EnsureRung.EMBEDDED_ADB)
        ChannelMode.AUTO -> listOf(EnsureRung.EMBEDDED_ADB, EnsureRung.SHIZUKU)
    }

    /**
     * Make the chosen mode's shell channel usable, starting whatever flow is
     * needed, and return the line to show the user.
     *
     * [onReady] fires when a channel becomes usable WITHOUT another tap — i.e.
     * the user approves the Shizuku prompt — so the caller can finish the job it
     * was already attempting. It lands on a binder thread; marshal to the UI
     * thread yourself. It is not called when the user must act elsewhere first
     * (start the Shizuku app, pair wireless debugging), because there is no
     * callback for those.
     *
     * Call off the main thread: probing binds services and the adb reconnect
     * runs an mDNS discovery.
     */
    fun ensure(ctx: Context, onReady: () -> Unit): String {
        ShellChannels.active(ctx)?.let {
            onReady()
            return "Using ${it.name()}"
        }
        val rungs = rungs(ChannelModePrefs.current(ctx))
        var adbMsg: String? = null
        if (EnsureRung.EMBEDDED_ADB in rungs) {
            // "No channel right now" is almost always "not reconnected since the
            // last reboot": the embedded client keeps the keys from the one-time
            // pairing and re-attaches over mDNS with no user step at all. The
            // result counts only when the new session answers a shell round trip.
            val (connected, msg) = EmbeddedAdbChannel.connectLive(ctx)
            adbMsg = msg
            if (connected) ShellChannels.active(ctx)?.let {
                onReady()
                return "Using ${it.name()} — $msg"
            }
        }
        if (EnsureRung.SHIZUKU !in rungs)
            return "Embedded adb is not connected ($adbMsg). Turn Wireless debugging on (on Wi-Fi) and pair " +
                   "once on the ADB Shell page, then tap again."
        if (ShizukuAdb.isAvailable()) {
            // Service is up, we just have no grant — this is the one case with a
            // real prompt and a real callback.
            if (!ShizukuAdb.isGranted()) {
                ShizukuAdb.requestPermission { onReady() }
                return "Approve the Shizuku prompt to continue…"
            }
            // Granted but the bind hasn't landed yet; force it.
            return if (ShizukuAdb.bindBlocking(ctx)) { onReady(); "Connected to Shizuku" }
                   else "Shizuku granted but the service did not bind — restart Shizuku"
        }
        // Shizuku isn't running. If it is at least installed, open it: its service
        // has to be started by the user once per boot, and no API can do that.
        val launch = ctx.packageManager.getLaunchIntentForPackage(SHIZUKU_PKG)
        if (launch != null) {
            runCatching { ctx.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return "Start the Shizuku service, then tap again"
        }
        if (adbMsg == null) return "Shizuku is not installed. Install it and start its service, or pick another mode."
        // Auto, both planes out. Lead with the internal one — it is the supported
        // path and the only one that needs no other app — and carry what the
        // embedded client actually said, because "Wireless Debugging is off" and
        // "never paired" need different answers from the user.
        return "Needs SHELL access: turn Wireless Debugging on and pair the embedded " +
               "adb channel under Dev tools (embedded adb: $adbMsg), or install Shizuku " +
               "and start it. Nothing else can write these settings."
    }
}

package com.diegonmarcos.superapp.adbdebug

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.util.UUID

/**
 * Bootstrap state for the self-contained app_process shell server
 * ([AdbShellServer]).
 *
 * The server runs in the SHELL domain only because it's launched by
 * `adb` (the one unavoidable privilege source on stock+non-rooted). We
 * own everything else: the server class is in our APK, it binds loopback,
 * and it authenticates with a per-install random token both sides share
 * (the app persists it here; the launch command embeds it).
 *
 * Port + class + nice-name are DATA-DRIVEN from
 * build.json::shizuku_diagnostics.local_server (baked into BuildConfig).
 */
object AdbShellBootstrap {

    fun port(): Int = BuildConfig.ADB_SHELL_SERVER_PORT
    fun serverClass(): String = BuildConfig.ADB_SHELL_SERVER_CLASS
    fun niceName(): String = BuildConfig.ADB_SHELL_SERVER_NICE

    /** Stable per-install token shared with the server via its launch
     *  args. Generated + persisted on first read. */
    fun token(ctx: Context): String {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.getString(K_TOKEN, null)?.let { if (it.isNotBlank()) return it }
        val fresh = UUID.randomUUID().toString().replace("-", "")
        sp.edit().putString(K_TOKEN, fresh).apply()
        return fresh
    }

    /**
     * The exact one-liner to run from any machine with `adb` (Wireless
     * Debugging counts) ONCE per boot. It puts our APK on the classpath
     * and launches [AdbShellServer] via app_process, which therefore runs
     * in the SHELL domain. `nohup … &` + `</dev/null` detaches it so it
     * survives the adb session.
     *
     * The token is embedded so only this app can talk to the server.
     */
    fun serverCommand(ctx: Context): String = "adb shell \"${shellCommand(ctx)}\""

    /** The same launch, as the shell line itself (what the Restart-server button sends). */
    fun shellCommand(ctx: Context): String {
        val pkg = ctx.packageName
        val tok = token(ctx)
        // `export …;` not `CLASSPATH=… nohup …`: every channel runs this through a
        // plain `sh -c`, and a leading assignment breaks the moment anything
        // (`timeout`, a wrapper) is put in front of it (rc 127).
        return "export CLASSPATH=\$(pm path $pkg | cut -d: -f2); " +
            "nohup app_process /system/bin --nice-name=${niceName()} " +
            "${serverClass()} $tok ${port()} </dev/null >/dev/null 2>&1 &"
    }

    /**
     * Launch the loopback server over the first ready channel of [sources] (launcher order: [LaunchViaPrefs.order],
     * embedded adb first, Shizuku as the fallback). Two callers, both in ChannelDevice.kt: the Restart-server button
     * ([ChannelOps.startServer], [reason] "button") and the automatic chain's LAUNCH_SERVER step
     * ([DeviceConnectExecutor], reason "auto"). Never from a lookup, a reconnect or the pairing service.
     * Logs one line per attempt with the real outcome and the launcher used; kept for [bootstrapState].
     */
    fun ensureServer(ctx: Context, sources: List<ShellChannel>, reason: String = "button"): Boolean {
        val listening = LocalShellChannel.isReady(ctx)
        if (listening && LocalShellChannel.probe(ctx)) return true
        val via = sources.firstOrNull { it !== LocalShellChannel && it.isReady(ctx) }
        if (via == null) {
            record(ctx, "$reason: no launcher ready (embedded adb not connected, Shizuku not granted)")
            return false
        }
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        record(ctx, "$reason via ${via.name()}: launching")
        // A listening server that does not answer is the previous APK's process (shell uid, it
        // outlives the app): kill it first, by its nice-name, through the channel that still works.
        val launch = (if (listening) "pkill -f '${niceName().dropLast(1)}[${niceName().last()}]'; sleep 1; " else "") + shellCommand(ctx)
        val out = via.exec(ctx, launch)?.trim().orEmpty()
        var up = false
        // Up = it ANSWERS a round trip, not that the port accepts.
        repeat(10) { if (!up) { Thread.sleep(500); up = LocalShellChannel.isReady(ctx) && LocalShellChannel.probe(ctx) } }
        val result = "$reason via ${via.name()}: " +
            if (up) "server up" else "server did not come up" + (if (out.isBlank()) "" else " (${out.take(80)})")
        sp.edit().putString(K_LAUNCHER, via.name()).apply()
        record(ctx, result)
        Log.i(TAG, "server $result")
        return up
    }

    /** Writes the real state (bootstrapState) and the ChannelLog line. */
    fun record(ctx: Context, text: String) {
        runCatching {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(K_BOOT_AT, SystemClock.elapsedRealtime()).putString(K_BOOT_RESULT, text).apply()
        }
        ChannelLog.shared.add("server", text)
    }

    /** Who launched the server last (channel name), or null. */
    fun lastLauncher(ctx: Context): String? =
        runCatching { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(K_LAUNCHER, null) }.getOrNull()

    /** One short string for /api/adb/status + the Permissions row: the last launch attempt this boot (automatic or by
     *  the Restart button), through which launcher, and how it really ended. No network, safe on the main thread. */
    fun bootstrapState(ctx: Context): String {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = sp.getLong(K_BOOT_AT, -1L)
        if (last < 0 || last > SystemClock.elapsedRealtime()) return NOT_STARTED
        return sp.getString(K_BOOT_RESULT, null) ?: NOT_STARTED
    }

    /** Nothing attempted yet this boot: in Local server mode the app starts it by itself when a command needs it. */
    const val NOT_STARTED = "no launch attempt this boot (Local server mode starts it by itself when a command needs it; Restart server forces it)"
    private const val TAG     = "AdbShellBootstrap"
    private const val PREFS   = "adb_shell"
    private const val K_TOKEN = "token"
    private const val K_BOOT_AT     = "bootstrap_at"      // elapsedRealtime ms of the last attempt
    private const val K_BOOT_RESULT = "bootstrap_result"
    private const val K_LAUNCHER    = "bootstrap_launcher"
}

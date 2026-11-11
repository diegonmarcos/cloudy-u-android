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
     * Launch the OPTIONAL loopback server over the first ready channel of [sources]. Called from ONE
     * place, the Restart-server button ([ChannelOps.startServer]); never from a connect, a reconnect, an
     * auto-fix or a channel lookup. The server is an extra (it keeps answering after the adb session
     * drops), not a prerequisite: the adbd connection is already the shell channel.
     * Logs one line per attempt; the result is kept for [bootstrapState].
     */
    fun ensureServer(ctx: Context, sources: List<ShellChannel>): Boolean {
        val listening = LocalShellChannel.isReady(ctx)
        if (listening && LocalShellChannel.probe(ctx)) return true
        val via = sources.firstOrNull { it !== LocalShellChannel && it.isReady(ctx) } ?: return false
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.edit().putLong(K_BOOT_AT, SystemClock.elapsedRealtime()).putString(K_BOOT_RESULT, "started by its button via ${via.name()}: launching").apply()
        // A listening server that does not answer is the previous APK's process (shell uid, it
        // outlives the app): kill it first, by its nice-name, through the channel that still works.
        val launch = (if (listening) "pkill -f '${niceName().dropLast(1)}[${niceName().last()}]'; sleep 1; " else "") + shellCommand(ctx)
        val out = via.exec(ctx, launch)?.trim().orEmpty()
        var up = false
        // Up = it ANSWERS a round trip, not that the port accepts.
        repeat(10) { if (!up) { Thread.sleep(500); up = LocalShellChannel.isReady(ctx) && LocalShellChannel.probe(ctx) } }
        val result = "started by its button via ${via.name()}: " +
            if (up) "server up" else "server did not come up" + (if (out.isBlank()) "" else " (${out.take(80)})")
        sp.edit().putString(K_BOOT_RESULT, result).apply()
        Log.i(TAG, "optional server $result")
        ChannelLog.shared.add("server", result)
        return up
    }

    /** One short string for /api/adb/status + the Permissions row: was the
     *  optional server started this boot (by its button), through which channel, and how
     *  did it end. No network — safe on the main thread. */
    fun bootstrapState(ctx: Context): String {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = sp.getLong(K_BOOT_AT, -1L)
        if (last < 0 || last > SystemClock.elapsedRealtime()) return NOT_STARTED
        return sp.getString(K_BOOT_RESULT, null) ?: NOT_STARTED
    }

    /** The server is optional and is started only by its button. */
    const val NOT_STARTED = "not started this boot (optional; started only from its Restart button)"
    private const val TAG     = "AdbShellBootstrap"
    private const val PREFS   = "adb_shell"
    private const val K_TOKEN = "token"
    private const val K_BOOT_AT     = "bootstrap_at"      // elapsedRealtime ms of the last attempt
    private const val K_BOOT_RESULT = "bootstrap_result"
}

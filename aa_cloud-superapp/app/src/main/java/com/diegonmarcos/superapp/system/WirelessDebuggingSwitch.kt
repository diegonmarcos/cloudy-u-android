package com.diegonmarcos.superapp.system

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import com.diegonmarcos.superapp.shellclient.ShellClient
import com.diegonmarcos.superapp.shellclient.ShellResult

/**
 * #1682 P3: the Wireless debugging switch as the SuperApp's own screens use it (Control panel, Dev control, the status strip), with no
 * ADB channel class: an unprivileged read, a write by WRITE_SECURE_SETTINGS when this app holds it, else through the owner's broker
 * (libs:shell-client, `settings put global adb_wifi_enabled`). Every [Result] re-reads the value, never trusts the write call.
 * Same ladder and honesty rule as the owner's own switch (libs:shizuku-adb-debug-tools WirelessDebugging), which stays the owner's.
 */
object WirelessDebuggingSwitch {
    private const val KEY = "adb_wifi_enabled"

    /** What actually happened; [ok] = the re-read agrees with what was asked. */
    data class Result(val ok: Boolean, val channel: String, val detail: String, val on: Boolean)

    /** The phone's own Wireless debugging switch in Developer options (no ADB class); the owner's ADB Shell page when the phone has none. */
    fun openSettings(ctx: Context): Boolean {
        val i = android.content.Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
        if (ctx !is android.app.Activity) i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { ctx.startActivity(i) }.isSuccess || ShellClient.openArmPage(ctx)
    }

    fun isOn(ctx: Context): Boolean = runCatching { Settings.Global.getInt(ctx.contentResolver, KEY, 0) == 1 }.getOrDefault(false)

    /** Blocking (the broker call): call off the main thread. */
    fun set(ctx: Context, on: Boolean): Result {
        val want = if (on) 1 else 0
        if (ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED) {
            val wrote = runCatching { Settings.Global.putInt(ctx.contentResolver, KEY, want) }.getOrElse { false }
            if (isOn(ctx) == on) return Result(true, "self (WRITE_SECURE_SETTINGS)", "$KEY=$want (${if (wrote) "accepted" else "rejected but state agrees"})", on)
        }
        val app = ctx.applicationContext
        val st = runCatching { ShellClient.status(app) }.getOrNull()
        if (st == null || !st.armed) return Result(false, "none",
            "No way to write this setting: WRITE_SECURE_SETTINGS is not granted to this app and the ADB shell is down. " +
            "Use \"Open Wireless Debugging\" and flip it in system settings.", isOn(ctx))
        val out = (ShellClient.exec(app, "settings put global $KEY $want") as? ShellResult.Ok)?.let { it.stdout + it.stderr }
        val after = isOn(ctx)
        return Result(after == on, st.channel, "settings put global $KEY $want -> " + ((out ?: "no output").trim().ifBlank { "ok" }), after)
    }
}

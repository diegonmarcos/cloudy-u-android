package com.diegonmarcos.superapp.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.provider.Settings
import com.diegonmarcos.superapp.configs.AdbToggle
import com.diegonmarcos.superapp.configs.BrokerGrantChannel
import com.diegonmarcos.superapp.configs.DeviceControls

/**
 * #1687 the phone's power-saving mode: ONE control, declared once in build.json::ui.control_panel
 * (controls[id=power_saving]) and drawn in two places — Launcher Quick actions (the control panel tile, DeviceControls)
 * and Configs > Perms (the Power saving card, PermissionsFragment). Both call [set] and both read [isOn].
 *
 * State = PowerManager.isPowerSaveMode (re-read after every write, never a remembered boolean) and the
 * ACTION_POWER_SAVE_MODE_CHANGED broadcast ([watch]), which also drives [LowPowerMode].
 *
 * Write ladder: WRITE_SECURE_SETTINGS (Settings.Global low_power), then the declared adb lines through the ADB owner
 * channel (the shell broker, [BrokerGrantChannel]). Neither available ⇒ the exact reason ([blockedReason]) and the
 * ADB Shell link, never a silent failure.
 */
object PowerSaving {

    const val CONTROL_ID = "power_saving"
    private const val LOW_POWER = "low_power"
    const val PREFS = "low_power_prefs"
    const val KEY_FOLLOW = "follow_system"

    fun isOn(ctx: Context): Boolean? = runCatching {
        (ctx.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode
    }.getOrNull()

    fun hasSecureSettings(ctx: Context): Boolean =
        ctx.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    fun channelUp(ctx: Context): Boolean = runCatching { BrokerGrantChannel(ctx).up() }.getOrDefault(false)

    /** "" when a write can land; otherwise the reason shown on both surfaces. */
    fun blockedReason(ctx: Context): String =
        if (hasSecureSettings(ctx) || channelUp(ctx)) ""
        else "Needs WRITE_SECURE_SETTINGS (Perms > Write secure settings) or a connected ADB channel; this device has neither. " +
            AdbToggle.CHANNEL_DOWN

    /** The declared adb lines (enable / disable) of the one declaration. */
    fun toggle(ctx: Context): AdbToggle {
        val adb = DeviceControls.declared(CONTROL_ID)?.optJSONObject("adb")
        fun list(k: String) = adb?.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } }.orEmpty()
        val label = DeviceControls.declared(CONTROL_ID)?.optString("label")?.ifBlank { null } ?: "Power saving"
        return AdbToggle(CONTROL_ID, label, isOn(ctx), list("enable"), list("disable"))
    }

    data class Result(val ok: Boolean, val channel: String, val detail: String, val channelDown: Boolean = false)

    /** Blocking (binder + shell); call off the main thread. */
    fun set(ctx: Context, on: Boolean): Result {
        if (hasSecureSettings(ctx)) {
            runCatching { Settings.Global.putInt(ctx.contentResolver, LOW_POWER, if (on) 1 else 0) }
            if (await { isOn(ctx) == on }) return Result(true, "self (WRITE_SECURE_SETTINGS)", "$LOW_POWER=${if (on) 1 else 0}; power saving is ${word(isOn(ctx))}")
        }
        val t = toggle(ctx).copy(on = !on) // run the direction asked for
        val out = AdbToggle.run(t, BrokerGrantChannel(ctx)) { if (await { isOn(ctx) == on }) on else isOn(ctx) }
        return Result(out.ok, if (out.channelDown) "none" else "ADB channel", out.message, out.channelDown)
    }

    private fun await(check: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + 1500
        while (!check()) { if (System.currentTimeMillis() > end) return false; runCatching { Thread.sleep(60) } }
        return true
    }

    fun word(b: Boolean?) = when (b) { true -> "on"; false -> "off"; null -> "unknown" }

    // ── the watcher that drives LowPowerMode ────────────────────────────

    @Volatile private var receiver: BroadcastReceiver? = null

    /** Idempotent: one process-wide receiver on the application context; reads the real state now. */
    @Synchronized fun watch(ctx: Context) {
        val app = ctx.applicationContext
        runCatching {
            LowPowerMode.config = LowPowerMode.Config.parse(DeviceControls.declared(CONTROL_ID)?.optJSONObject("low_power_mode"))
            LowPowerMode.setFollowSystem(app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_FOLLOW, true))
        }
        isOn(app)?.let { LowPowerMode.onSystemState(it) }
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) { isOn(app)?.let { LowPowerMode.onSystemState(it) } }
        }
        runCatching { app.registerReceiver(r, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)) }
            .onSuccess { receiver = r }
    }

    fun setFollowSystem(ctx: Context, follow: Boolean) {
        runCatching { ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_FOLLOW, follow).apply() }
        LowPowerMode.setFollowSystem(follow)
    }
}

package com.diegonmarcos.superapp.notificationcenter

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Wakes the Battery data widget when the process is gone: plug, unplug, low and okay are the few battery broadcasts the
 * platform still delivers to a manifest receiver (BATTERY_CHANGED is not one, which is why the live card is carried by
 * [BatteryBadgeService.ensureReceiver] while the process lives). A plain startService from here is allowed: the broadcast
 * gives the receiver its short allowlist. Starts nothing when the badge is switched off.
 */
class DataWidgetWakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val ctx = context ?: return
        if (intent?.action !in HANDLED) return
        com.diegonmarcos.superapp.xray.EngineProfiler.wake("notify", "widget_broadcast")
        if (!BadgeServices.wanted(ctx, BatteryBadgeService::class.java.name)) return
        runCatching { DataWidgetRuntime.start(ctx.applicationContext, BatteryBadgeService::class.java) }
    }

    companion object {
        val HANDLED = setOf(
            Intent.ACTION_POWER_CONNECTED,
            Intent.ACTION_POWER_DISCONNECTED,
            Intent.ACTION_BATTERY_LOW,
            Intent.ACTION_BATTERY_OKAY,
        )
    }
}

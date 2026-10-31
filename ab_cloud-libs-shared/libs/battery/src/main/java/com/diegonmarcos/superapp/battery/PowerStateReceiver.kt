package com.diegonmarcos.superapp.battery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Manifest-registered receiver for the EXACT moment the device is plugged in
 * or unplugged, whether or not the SuperApp is running: it records one battery
 * SoT sample ([BatteryRepository.record]), so the plug/unplug boundary in the
 * history — where a discharge cycle ends and a charge session begins, and what
 * "since last charge" counts from — is the real moment, not the next tick. It is also the
 * charge-cycle boundary: one [BatteryRepository.snapshotCycle] closes or opens the per-app cycle.
 *
 * ACTION_POWER_CONNECTED / ACTION_POWER_DISCONNECTED are exempt from the
 * Android 8+ implicit-broadcast manifest restriction, so they arrive with the
 * app cold. ACTION_BATTERY_CHANGED is deliberately NOT manifest-registered
 * (sticky and frequent); the recorder reads the sticky intent once instead.
 */
class PowerStateReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_POWER_CONNECTED, Intent.ACTION_POWER_DISCONNECTED -> {
                val pending = goAsync()
                val app = ctx.applicationContext
                Thread {
                    try {
                        runCatching { BatteryRepository.record(app) }
                        // A charge-cycle boundary: snapshot per-app batterystats at the real moment.
                        runCatching { BatteryRepository.snapshotCycle(app, intent.action == Intent.ACTION_POWER_CONNECTED) }
                    } finally { pending.finish() }
                }.start()
            }
        }
    }
}

package com.diegonmarcos.superapp.battery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build

/**
 * Manifest-registered receiver for the EXACT moment the device is plugged in
 * or unplugged, whether or not the SuperApp is running: it records one battery
 * SoT sample ([BatteryRepository.record]), so the plug/unplug boundary in the
 * history — where a discharge cycle ends and a charge session begins, and what
 * "since last charge" counts from — is the real moment, not the next tick. It is also the
 * charge-cycle boundary: one [BatteryRepository.snapshotCycle] closes or opens the per-app cycle.
 *
 * ACTION_POWER_CONNECTED / ACTION_POWER_DISCONNECTED are NOT on Android 8+'s
 * implicit-broadcast exemption list, so the manifest entry only works below
 * Android 8: [register] adds the same receiver at runtime from the app's
 * process (the SuperApp is the launcher, so it lives), which is what delivers
 * them now (#1381: the emulator recorded no cycle without it).
 * ACTION_BATTERY_CHANGED is deliberately NOT registered (sticky and frequent);
 * the recorder reads the sticky intent once instead.
 */
class PowerStateReceiver : BroadcastReceiver() {

    companion object {
        @Volatile private var registered: PowerStateReceiver? = null

        /** Runtime registration for the plug edges, once per process (App.onCreate). */
        fun register(ctx: Context) {
            if (registered != null) return
            val app = ctx.applicationContext
            val r = PowerStateReceiver()
            val f = IntentFilter().apply { addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED) }
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED) else app.registerReceiver(r, f)
            registered = r
        }
    }

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

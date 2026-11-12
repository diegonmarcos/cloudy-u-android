package com.diegonmarcos.clocktools

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * This app's own wakeups (ClockEngine.ACTION_FIRE) and notification buttons (ACTION_CONTROL).
 * Not exported: only this app's PendingIntents reach it, so no other app can dismiss an alarm.
 * An exact alarm's delivery is also what lets the foreground service start from the background
 * (Android 12+ exempts it), which is why a ring always starts here.
 */
class ClockReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val key = intent.getStringExtra(ClockEngine.EXTRA_KEY).orEmpty()
        when (intent.action) {
            ClockEngine.ACTION_FIRE -> ClockEngine.fire(ctx, key)
            ClockEngine.ACTION_CONTROL -> ClockEngine.control(ctx, intent.getStringExtra(ClockEngine.EXTRA_OP).orEmpty(), key)
        }
    }
}

/**
 * Everything that can lose or shift an AlarmManager alarm: a reboot clears them all, an app update
 * may, a clock or zone change moves wall-clock alarms, and an exact-alarm grant upgrades inexact
 * ones. Each re-plans every wakeup from the stored state (ClockEngine.reschedule). Exported because
 * the system sends these; it acts on nothing it is told beyond "re-plan".
 */
class ClockBootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        ClockEngine.reschedule(ctx)
    }
}

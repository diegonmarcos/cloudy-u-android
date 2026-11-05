package com.diegonmarcos.cloudwebserver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Starts [KeepAliveService] at boot and after this app is updated, only while Server and Keep alive are both ON (#1502; the
 * receiver is also disabled as a component while they are not, see MainActivity.syncKeepAlive).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!PowerState.serviceAllowed(context)) {
            Log.i("C3BootReceiver", "ignored " + intent.action + ": Server or Keep alive is OFF")
            return
        }
        Log.i("C3BootReceiver", "start keep-alive service on " + intent.action)
        context.startForegroundService(Intent(context, KeepAliveService::class.java))
    }
}

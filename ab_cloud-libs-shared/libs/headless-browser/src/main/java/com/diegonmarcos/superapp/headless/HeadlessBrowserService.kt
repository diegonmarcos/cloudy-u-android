package com.diegonmarcos.superapp.headless

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import org.json.JSONObject

/**
 * #1498 HeadlessBrowserService: the lifetime holder of the "This App" engine. Started on demand by the provider / loopback door (never at
 * boot), START_NOT_STICKY (the system does not bring it back), and it stops itself when no session is open and nothing was served for the
 * idle time, or at once when the screen goes dark (#1487: no screen-off work). Not exported; bound callers must hold the signature permission
 * through the provider, and the in-process [LocalBinder] gives the app itself the same dispatch.
 */
class HeadlessBrowserService : Service() {
    inner class LocalBinder : Binder() {
        fun execute(path: String, body: String): String = HeadlessRuntime.get(this@HeadlessBrowserService).execute(path, JSONObject(body)).toString()
    }

    private val handler = Handler(Looper.getMainLooper())
    private val binder = LocalBinder()
    private val sweep = object : Runnable {
        override fun run() {
            val done = runCatching { HeadlessRuntime.get(this@HeadlessBrowserService).sweep() }.getOrDefault(true)
            if (done) { stopSelf() } else handler.postDelayed(this, HeadlessPolicy.SWEEP_MS)
        }
    }
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { runCatching { HeadlessRuntime.get(c).shutdown() }; stopSelf() }
    }

    override fun onCreate() {
        super.onCreate()
        registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
        handler.postDelayed(sweep, HeadlessPolicy.SWEEP_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        handler.removeCallbacks(sweep)
        runCatching { unregisterReceiver(screenOff) }
        runCatching { HeadlessRuntime.get(this).shutdown() }
        super.onDestroy()
    }

    companion object {
        /** Start on demand; a refusal (background start limits) is harmless because the runtime works without the holder. */
        fun ensure(ctx: Context) { runCatching { ctx.startService(Intent(ctx, HeadlessBrowserService::class.java)) } }
    }
}

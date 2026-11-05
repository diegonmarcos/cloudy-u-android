package com.diegonmarcos.ide

import android.annotation.SuppressLint
import android.content.Context
import android.os.PowerManager

/**
 * #787 The partial wake lock an open terminal holds, so a shell keeps running with the screen
 * locked. This app has no terminal service: its sessions are the native PTYs [TerminalSessions]
 * holds (it calls [syncNative]) and the fallback SSH shells [SshBackend] holds (it calls [sync]),
 * each whenever its count changes. [CloudWakeLock] (the class cld.termux
 * and cld.termux.nix run) decides; this only does the PowerManager work it asks for.
 */
object TerminalWakeLock {

    private var lock: PowerManager.WakeLock? = null
    @Volatile private var sessions = 0
    @Volatile private var nativeSessions = 0

    /** [ssh] SSH shells are open now: take or drop the lock to match them, the native
     *  shells ([syncNative]) and the user's choice. */
    @Synchronized
    fun sync(ctx: Context, ssh: Int = sessions) {
        sessions = ssh
        val count = ssh + nativeSessions
        val app = ctx.applicationContext
        when (CloudWakeLock.STATE.update(IdePrefs.wakeLockWanted(app), count, System.currentTimeMillis())) {
            CloudWakeLock.Transition.ACQUIRE -> acquire(app)
            CloudWakeLock.Transition.RELEASE -> lock?.release().also { lock = null }
            else -> Unit
        }
    }

    /** [count] native (ICloudSession) shells are open now; [TerminalSessions] calls it. */
    @Synchronized
    fun syncNative(ctx: Context, count: Int) {
        nativeSessions = count
        sync(ctx)
    }

    @SuppressLint("WakelockTimeout", "BatteryLife")
    private fun acquire(app: Context) {
        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cloud-myterminal:terminal-sessions")
            .apply { acquire() }
        // #1515 the battery exemption is the Perms battery_opt row (Configs > Perms, asked at first run); a lock taken on every shell never asks.
    }
}

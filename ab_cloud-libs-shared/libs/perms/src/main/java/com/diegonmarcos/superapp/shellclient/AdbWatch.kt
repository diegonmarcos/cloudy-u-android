package com.diegonmarcos.superapp.shellclient

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/**
 * #1550 The event every ADB indicator re-checks on (besides resume): Settings.Global `adb_wifi_enabled` / `adb_enabled` changed.
 * No polling loop anywhere: the system calls [onChange] when the person (or the keeper) flips a switch. Returns the unregister.
 */
object AdbWatch {
    val KEYS = listOf("adb_wifi_enabled", "adb_enabled")

    fun observe(ctx: Context, onChange: () -> Unit): () -> Unit {
        val cr = ctx.applicationContext.contentResolver
        val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { onChange() }
        }
        val ok = runCatching { for (k in KEYS) cr.registerContentObserver(Settings.Global.getUriFor(k), false, obs) }.isSuccess
        return { if (ok) runCatching { cr.unregisterContentObserver(obs) } }
    }
}

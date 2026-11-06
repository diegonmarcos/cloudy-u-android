package com.diegonmarcos.superapp.updater.install

import android.annotation.SuppressLint
import android.content.Context

/**
 * #1551 Session ids this app abandoned ON PURPOSE because a newer session for the same package replaced them.
 *
 * Android answers every abandon of a committed session with INSTALL_FAILED_ABORTED "Session was abandoned" at the receiver. For a
 * superseded duplicate that answer is not news: the newer session is the install, and treating the old one's answer as a failure
 * opened the newer session's gate early and wrote a failure note over an install still in progress. Persisted (commit) because the
 * answer can arrive in a later process.
 */
object SupersededSessions {
    private const val PREFS = "updater_superseded_sessions"
    private const val KEY = "ids"
    /** Bounded: ids older than the newest [CAP] are forgotten (Android's own cap is 50 sessions). */
    private const val CAP = 64

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun ids(ctx: Context): List<Int> =
        prefs(ctx).getString(KEY, "").orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }

    @SuppressLint("ApplySharedPref")
    fun add(ctx: Context, sessionId: Int) {
        val next = (ids(ctx) - sessionId + sessionId).takeLast(CAP)
        prefs(ctx).edit().putString(KEY, next.joinToString(",")).commit()
    }

    /** True (once) when [sessionId] was superseded: the caller swallows its abort answer. */
    @SuppressLint("ApplySharedPref")
    fun consume(ctx: Context, sessionId: Int): Boolean {
        if (sessionId < 0) return false
        val all = ids(ctx)
        if (sessionId !in all) return false
        prefs(ctx).edit().putString(KEY, (all - sessionId).joinToString(",")).commit()
        return true
    }
}

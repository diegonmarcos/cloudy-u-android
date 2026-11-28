package com.diegonmarcos.clocktools

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * #1987 links to a Clock subpage. [offer] reads ClockEngine.EXTRA_MODE off an Intent (a notification tap, or Cloud Calc
 * handing over a page it no longer has) into [pending]; the host shell opens its Clock and FullClock turns to that page,
 * then clears it. [handOff] is Cloud Calc's side: a page Calc no longer shows opens in Cloud Tasks.
 */
object ClockLink {
    val pending = MutableStateFlow<String?>(null)

    /** True when [intent] named a Clock subpage (it is now [pending]). */
    fun offer(intent: Intent?): Boolean {
        val id = ClockSubpages.idOf(intent?.getStringExtra(ClockEngine.EXTRA_MODE)) ?: return false
        pending.value = id
        return true
    }

    /**
     * Opens subpage [id] in Cloud Tasks (its Clock, the same page) and returns true; false when [id] is not a page that
     * moved there or Cloud Tasks is not installed (the caller then opens the World clock).
     */
    fun handOff(ctx: Context, id: String?): Boolean {
        if (id == null || id !in ClockSubpages.MOVED_TO_TASKS) return false
        val launch = ctx.packageManager.getLaunchIntentForPackage(ClockSubpages.TASKS_PACKAGE) ?: return false
        return runCatching {
            ctx.startActivity(launch.putExtra(ClockEngine.EXTRA_MODE, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            true
        }.getOrDefault(false)
    }
}

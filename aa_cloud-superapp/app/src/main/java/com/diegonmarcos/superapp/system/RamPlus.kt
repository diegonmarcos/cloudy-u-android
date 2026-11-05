package com.diegonmarcos.superapp.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import java.io.File

/**
 * #1508 Samsung RAM Plus (virtual memory): a READ-ONLY check plus the deep link that lets the owner change it. This never
 * writes the setting. The state is read from `Settings.Global ram_expand_size` (Samsung's key, readable without a
 * permission; 0 = off, a positive size = on) and, when the key is absent or unparsable, from `/proc/meminfo` SwapTotal > 0.
 * The desired state is DATA (build.json::ui.permissions.device[ram_plus].want, "on" | "off", default on).
 */
object RamPlus {
    const val KEY = "ram_expand_size"

    /** The decision, pure: on / off / null (cannot tell) from the Samsung key's text and SwapTotal in kB. */
    fun decide(expandSize: String?, swapTotalKb: Long?): Boolean? {
        expandSize?.trim()?.toLongOrNull()?.let { return it > 0L }
        return swapTotalKb?.let { it > 0L }
    }

    /** `SwapTotal:  4194300 kB` of /proc/meminfo text, in kB; null when the line is missing. */
    fun parseSwapTotalKb(meminfo: String): Long? = meminfo.lineSequence()
        .firstOrNull { it.startsWith("SwapTotal:") }?.substringAfter(':')?.trim()?.substringBefore(' ')?.toLongOrNull()

    /** Does the measured state satisfy the declared wish ("on" by default)? null measured = unknown. */
    fun satisfied(on: Boolean?, want: String): Boolean? = on?.let { it == (want.trim().lowercase() != "off") }

    /** The live reading: the Samsung key first, SwapTotal second. */
    fun read(ctx: Context): Boolean? = decide(
        runCatching { Settings.Global.getString(ctx.contentResolver, KEY) }.getOrNull(),
        runCatching { parseSwapTotalKb(File("/proc/meminfo").readText()) }.getOrNull(),
    )

    /** A declared deep-link rung: "package/Class" is an activity, "package" the app's launcher. */
    data class Target(val pkg: String, val cls: String?)

    fun target(spec: String): Target = spec.substringBefore('/').let { p -> Target(p, spec.substringAfter('/', "").ifBlank { null }) }

    /** The first declared rung that resolves, else Settings; the caller starts it. Never writes anything. */
    fun intent(ctx: Context, rungs: List<String>): Intent {
        for (r in rungs) {
            val t = target(r)
            val i = if (t.cls != null) Intent().setComponent(ComponentName(t.pkg, t.cls)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    else ctx.packageManager.getLaunchIntentForPackage(t.pkg)
            if (i != null && i.resolveActivity(ctx.packageManager) != null) return i
        }
        return Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

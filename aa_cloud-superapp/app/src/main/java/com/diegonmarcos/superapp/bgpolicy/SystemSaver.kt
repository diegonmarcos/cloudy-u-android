package com.diegonmarcos.superapp.bgpolicy

import android.content.Context
import android.os.PowerManager
import android.provider.Settings

/**
 * #1506 follow-up: the system's own EXTREME / MAXIMUM battery saver (Samsung "Maximum power saving" / ultra power saving, or an
 * emergency mode) already does the saving, so the app's Super Saving Battery Mode on top of it is redundant. The mode yields:
 * its tile is hidden and it cannot be turned on while the system mode is on; if it was already on it is restored (turned off).
 * Nothing is removed: outside the system extreme mode everything behaves as before.
 */
object SystemSaver {
    /** Samsung / OEM switches that mean the extreme saver is on (Settings.System int 1). */
    internal val EXTREME_KEYS = listOf("ultra_powersaving_mode", "emergency_mode", "psm_extreme", "max_power_saving_mode")

    /** Pure: [flags] = the value of each key (null = unset); [powerSave] = PowerManager.isPowerSaveMode. The plain saver alone is NOT extreme. */
    internal fun isExtreme(flags: Map<String, Int?>, powerSave: Boolean): Boolean =
        flags.any { (k, v) -> k in EXTREME_KEYS && v == 1 } ||
            (powerSave && flags["psm_level"].let { it != null && it >= 2 })

    fun extreme(ctx: Context): Boolean = runCatching {
        val cr = ctx.applicationContext.contentResolver
        val flags = (EXTREME_KEYS + "psm_level").associateWith { k -> Settings.System.getInt(cr, k, -1).takeIf { it != -1 } }
        val ps = (ctx.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager).isPowerSaveMode
        isExtreme(flags, ps)
    }.getOrDefault(false)

    const val WHY = "The system's extreme battery saver is on and already saving, so Super Saving is paused."
}

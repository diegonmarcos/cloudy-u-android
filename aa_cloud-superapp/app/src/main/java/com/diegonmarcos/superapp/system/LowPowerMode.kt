package com.diegonmarcos.superapp.system

import java.util.concurrent.CopyOnWriteArrayList

/**
 * #1687 the SuperApp's low-power mode. It FOLLOWS the system power-save state (PowerManager.isPowerSaveMode plus the
 * ACTION_POWER_SAVE_MODE_CHANGED broadcast, wired by [PowerSaving.watch]); it is never a second switch of its own,
 * except that the owner may turn the following off (pref low_power_prefs:follow_system, declared in fleet-config.json).
 *
 * While active: the launcher 3D/4D auto-rotate is paused (Home3DFragment ANDs [pausesAnimation] into its existing
 * auto-rotate observer), and every periodic loop that asks [scaled] waits [Config.pollMultiplier] times longer
 * (network badge tick, floating-nav poll, resources / badge / control-panel refreshes, the energy sampler, status
 * dropdowns). On exit every listener is told and the loops return to their normal period at their next tick.
 *
 * Pure (no Android): unit-tested in LowPowerModeTest. Config is the declaration in
 * build.json::ui.control_panel controls[id=power_saving].low_power_mode, parsed by [Config.parse].
 */
object LowPowerMode {

    data class Config(
        val pollMultiplier: Int = 4,
        val pauseAnimation: Boolean = true,
        /** The periodic work the mode slows, by name: shown on the Perms card so the owner sees what it does. */
        val slows: List<String> = DEFAULT_SLOWS,
    ) {
        companion object {
            val DEFAULT_SLOWS = listOf("3D/4D auto-rotate (paused)", "network badge", "floating nav poll",
                "resources panel", "notify badges", "energy sampler", "status dropdowns")

            fun parse(o: org.json.JSONObject?): Config {
                if (o == null) return Config()
                val arr = o.optJSONArray("slows")
                val slows = if (arr == null) DEFAULT_SLOWS else (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
                return Config(o.optInt("poll_multiplier", 4).coerceIn(1, 20), o.optBoolean("pause_animation", true), slows)
            }
        }
    }

    @Volatile var config: Config = Config()

    /** The system said power-save is on (last real read or broadcast). */
    @Volatile var systemPowerSave: Boolean = false
        private set

    /** The owner lets the SuperApp follow the system state (default true). */
    @Volatile var followSystem: Boolean = true
        private set

    private val listeners = CopyOnWriteArrayList<(Boolean) -> Unit>()

    /** Low-power mode is on: the system is in power-save and the SuperApp follows it. */
    val active: Boolean get() = systemPowerSave && followSystem

    val pausesAnimation: Boolean get() = active && config.pauseAnimation

    /** A periodic delay under the mode: [baseMs] while normal, multiplied while active. */
    fun scaled(baseMs: Long): Long = if (active) baseMs * config.pollMultiplier else baseMs

    /** Feed a REAL system read (never a guess). Listeners hear only a change of [active]. */
    fun onSystemState(powerSave: Boolean) = update { systemPowerSave = powerSave }

    fun setFollowSystem(follow: Boolean) = update { followSystem = follow }

    private inline fun update(change: () -> Unit) {
        val before = active
        change()
        val now = active
        if (now != before) for (l in listeners) runCatching { l(now) }
    }

    /** Observe [active]; returns the unsubscribe. */
    fun observe(onChange: (Boolean) -> Unit): () -> Unit {
        listeners += onChange
        return { listeners -= onChange }
    }

    /** One line for the UI. */
    fun statusText(): String = when {
        active -> "Low-power mode ON (follows system power saving): 3D/4D paused, polling ×${config.pollMultiplier}"
        systemPowerSave -> "System power saving is on; SuperApp low-power mode is set not to follow it"
        else -> "Low-power mode off (system power saving is off)"
    }

    /** Test hook. */
    internal fun resetForTest() { systemPowerSave = false; followSystem = true; config = Config(); listeners.clear() }
}

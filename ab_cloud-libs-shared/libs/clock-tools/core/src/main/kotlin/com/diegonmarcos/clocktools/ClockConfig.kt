package com.diegonmarcos.clocktools

import org.json.JSONArray
import org.json.JSONObject

/**
 * The clock's declared numbers (#768: Cloud Calc's build.json::ui.modes[].clock objects, one per mode kind). A host hands
 * the lib its own declaration through [parse]; a host that declares none (Cloud Tasks, the SuperApp) gets [DEFAULT], the
 * same values Cloud Calc declares, so the clock behaves the same in every app that carries it.
 */
data class ClockConfig(
    val ringMinutes: Int = 10,
    val snoozeChoices: List<Int> = listOf(5, 10, 15, 20, 30),
    val snoozeDefault: Int = 10,
    val vibrateDefault: Boolean = true,
    val zones: List<String> = listOf("Europe/Madrid", "America/New_York", "America/Sao_Paulo", "Asia/Tokyo"),
    /** Timer presets in seconds. */
    val timerPresets: List<Long> = listOf(60, 180, 300, 600, 900, 1800, 3600),
    val sleepChoices: List<Int> = listOf(15, 30, 45, 60, 90),
    val bedtimeDefault: Int = 23 * 60,
    val intervalPresets: List<IntervalPreset> = listOf(
        IntervalPreset("Pomodoro", 1500, 300, 900, 4, 4),
        IntervalPreset("HIIT 30/30", 30, 30, 0, 0, 10),
        IntervalPreset("Tabata", 20, 10, 0, 0, 8),
    ),
) {
    data class IntervalPreset(val label: String, val workS: Long, val restS: Long, val longRestS: Long, val longEvery: Int, val rounds: Int)

    val ringMs: Long get() = ringMinutes * ClockLogic.MINUTE_MS

    companion object {
        val DEFAULT = ClockConfig()

        /**
         * [byKind] maps a mode kind (worldclock, alarms, timers, interval, bedtime) to its `clock` JSON object. A key the
         * declaration leaves out reads as Cloud Calc's ClockDecl read it before #1668 (an empty list, 10 minutes, ...);
         * unreadable JSON is an empty object, never a crash.
         */
        fun parse(byKind: Map<String, String?>): ClockConfig {
            fun of(kind: String): JSONObject = runCatching { JSONObject(byKind[kind] ?: "{}") }.getOrDefault(JSONObject())
            fun ints(a: JSONArray?): List<Int> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { runCatching { a.getInt(it) }.getOrNull() }
            val alarms = of("alarms")
            val bed = of("bedtime")
            return ClockConfig(
                ringMinutes = alarms.optInt("ring_minutes", 10),
                snoozeChoices = ints(alarms.optJSONArray("snooze_choices")),
                snoozeDefault = alarms.optInt("snooze_default", 10),
                vibrateDefault = alarms.optBoolean("vibrate_default", true),
                zones = of("worldclock").optJSONArray("zones").let { a -> if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) } },
                timerPresets = ints(of("timers").optJSONArray("presets")).map { it.toLong() },
                sleepChoices = ints(bed.optJSONArray("sleep_choices")),
                bedtimeDefault = ClockLogic.parseTime(bed.optString("bedtime_default")) ?: (23 * 60),
                intervalPresets = of("interval").optJSONArray("presets").let { a ->
                    if (a == null) emptyList() else (0 until a.length()).mapNotNull { i ->
                        runCatching {
                            val p = a.getJSONObject(i)
                            IntervalPreset(p.getString("label"), p.getLong("work"), p.optLong("rest"), p.optLong("long_rest"), p.optInt("long_every"), p.getInt("rounds"))
                        }.getOrNull()
                    }
                },
            )
        }
    }
}

package com.diegonmarcos.clocktools

import kotlin.math.abs

/**
 * #1970 the rules behind the redesigned Clock screens, plain JVM so the suite and PIT hold them: what value a wheel row
 * shows, which row is in the middle, how a wheel's h / m / s become a duration, the timer's preset chips, the alarm
 * sheet's vibration patterns and ring backgrounds. The screens draw; every number they show comes from here.
 */
object Wheel {
    /** An endless wheel is a long list of laps; it starts in the middle lap so it scrolls both ways. */
    const val LAPS = 1000

    fun size(min: Int, max: Int): Int = max - min + 1

    /** The value row [index] shows on a wheel of [min]..[max] that wraps (99 sits above 00). */
    fun valueAt(index: Int, min: Int, max: Int): Int = min + Math.floorMod(index, size(min, max))

    /** The row that shows [value] in the middle lap: a wheel opened on [value] can scroll both ways. */
    fun indexOf(value: Int, min: Int, max: Int, laps: Int = LAPS): Int = (laps / 2) * size(min, max) + (value.coerceIn(min, max) - min)

    /** [value] with [count] faded neighbours each side, top to bottom ([count] 1: 06, 07, 08). */
    fun neighbours(value: Int, min: Int, max: Int, count: Int): List<Int> =
        (-count..count).map { valueAt(indexOf(value, min, max) + it, min, max) }

    /**
     * The row in the middle of a wheel that shows [visible] rows (odd), from the list's first visible row and how far
     * it has scrolled past it: past half a row, the next one is the first.
     */
    fun centre(firstVisible: Int, offsetPx: Int, rowPx: Int, visible: Int): Int =
        firstVisible + (if (rowPx > 0 && offsetPx * 2 >= rowPx) 1 else 0) + visible / 2

    /** The first visible row that puts [index] in the middle. */
    fun firstFor(index: Int, visible: Int): Int = (index - visible / 2).coerceAtLeast(0)

    /** How faded a row [distance] rows from the middle is: the middle full, its neighbours dim, the rest dimmer. */
    fun alpha(distance: Int): Float = when (abs(distance)) {
        0 -> 1f
        1 -> 0.35f
        else -> 0.15f
    }

    /** The timer wheels (hours 0-99, minutes and seconds 0-59) as a duration. */
    fun hmsToMs(h: Int, m: Int, s: Int): Long = (h.coerceIn(0, 99) * 3600L + m.coerceIn(0, 59) * 60L + s.coerceIn(0, 59)) * ClockLogic.SECOND_MS

    /** A duration on the timer wheels: (hours capped at 99, minutes, seconds). */
    fun msToHms(ms: Long): Triple<Int, Int, Int> {
        val t = (ms.coerceAtLeast(0) / ClockLogic.SECOND_MS).coerceAtMost(99 * 3600L + 59 * 60L + 59)
        return Triple((t / 3600).toInt(), ((t % 3600) / 60).toInt(), (t % 60).toInt())
    }

    /** The alarm wheels (hour 0-23, minute 0-59) as a minute of the day, and back. */
    fun minuteOfDay(h: Int, m: Int): Int = h.coerceIn(0, 23) * 60 + m.coerceIn(0, 59)
    fun hourMinute(minuteOfDay: Int): Pair<Int, Int> = Math.floorMod(minuteOfDay, 1440).let { it / 60 to it % 60 }
}

/** #1970 the timer's preset chips (00:10:00, 00:15:00, 00:30:00): declared, and each one editable by the user. */
object TimerPresets {
    val DEFAULT: List<Long> = listOf(600L, 900L, 1800L)

    /** Seconds as the chip reads: HH:MM:SS. */
    fun text(seconds: Long): String {
        val t = seconds.coerceAtLeast(0)
        return "%02d:%02d:%02d".format(t / 3600, (t % 3600) / 60, t % 60)
    }

    /** "00:10:00" or "10:00" → seconds; null for anything else, a zero, or past 99:59:59. */
    fun parse(text: String): Long? {
        val parts = text.trim().split(':')
        if (parts.size !in 2..3 || parts.any { it.isEmpty() || !it.all(Char::isDigit) || it.length > 2 }) return null
        val n = parts.map { it.toInt() }
        val (h, m, s) = if (n.size == 3) Triple(n[0], n[1], n[2]) else Triple(0, n[0], n[1])
        if (m > 59 || s > 59) return null
        val total = h * 3600L + m * 60L + s
        return if (total > 0) total else null
    }

    /** What the chips show: the user's edits, else the declaration, else [DEFAULT]. */
    fun effective(stored: List<Long>?, declared: List<Long>): List<Long> = stored ?: declared.ifEmpty { DEFAULT }

    /** The chips with chip [index] set to [seconds]; an index off the row changes nothing. */
    fun edit(current: List<Long>, index: Int, seconds: Long): List<Long> =
        if (index !in current.indices || seconds <= 0) current else current.mapIndexed { i, v -> if (i == index) seconds else v }
}

/** #1970 the alarm sheet's declared choices. */
object ClockDesign {
    const val VIBRATION_DEFAULT = "basic"
    const val BACKGROUND_DEFAULT = "fleet"

    /** Vibration id → (label, waveform). The ring repeats the waveform from index 0. */
    val VIBRATIONS: Map<String, Pair<String, LongArray>> = linkedMapOf(
        "basic" to ("Basic call" to longArrayOf(0, 800, 600)),
        "heartbeat" to ("Heartbeat" to longArrayOf(0, 120, 120, 240, 700)),
        "ticktock" to ("Ticktock" to longArrayOf(0, 100, 400, 100, 400)),
        "waltz" to ("Waltz" to longArrayOf(0, 300, 200, 150, 200, 150, 600)),
        "zigzig" to ("Zig-zig-zig" to longArrayOf(0, 80, 80, 80, 80, 80, 800)),
    )

    /** Ring screen background ids, drawn in the fleet theme's own colours (no image assets). */
    val BACKGROUNDS: List<String> = listOf("fleet", "dusk", "ocean", "forest", "plain")

    fun vibrationLabel(id: String): String = (VIBRATIONS[id] ?: VIBRATIONS.getValue(VIBRATION_DEFAULT)).first
    fun vibrationPattern(id: String): LongArray = (VIBRATIONS[id] ?: VIBRATIONS.getValue(VIBRATION_DEFAULT)).second

    /** Snooze's detail line: "5 minutes, 3 times", "10 minutes, unlimited". */
    fun snoozeText(minutes: Int, times: Int): String =
        "$minutes minute" + (if (minutes == 1) "" else "s") + ", " + (if (times <= 0) "unlimited" else if (times == 1) "once" else "$times times")

    /** The snooze-count choices of the Snooze detail; 0 is unlimited. */
    val SNOOZE_TIMES: List<Int> = listOf(1, 2, 3, 5, 10, 0)
}

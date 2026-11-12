package com.diegonmarcos.clocktools

import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything the Clock section holds, as plain values. Times are wall-clock epoch milliseconds
 * (System.currentTimeMillis), the clock AlarmManager's RTC_WAKEUP alarms run on, so a value read
 * back after process death or a reboot still means the same instant.
 * ponytail: wall clock, so a manual clock change shifts a running timer; elapsedRealtime would
 * not, but it restarts at zero on every boot and a timer must outlive one.
 */
data class Alarm(
    val id: Long,
    val minuteOfDay: Int,
    /** Bit 0 = Monday … bit 6 = Sunday ([ClockLogic.dayBit]); 0 rings once and then switches off. */
    val days: Int = 0,
    val enabled: Boolean = true,
    val label: String = "",
    val vibrate: Boolean = true,
    /** A ringtone URI; "" is the system alarm sound, [SILENT] none. */
    val sound: String = "",
    val snoozeMinutes: Int = 10,
    /** A reminder (bedtime): it posts a notification and never rings. */
    val quiet: Boolean = false,
    val snoozedUntil: Long = 0,
) {
    companion object { const val SILENT = "silent" }
}

enum class TimerState { IDLE, RUNNING, PAUSED, RINGING }

data class Timer(
    val id: Long,
    val label: String,
    val durationMs: Long,
    val state: TimerState = TimerState.IDLE,
    /** When a RUNNING timer reaches zero. */
    val endsAt: Long = 0,
    /** What is left while IDLE or PAUSED. */
    val remainingMs: Long = durationMs,
)

/** [startedAt] is 0 while stopped; [laps] are the running totals at each lap press. */
data class Stopwatch(val startedAt: Long = 0, val accumulatedMs: Long = 0, val laps: List<Long> = emptyList()) {
    val running: Boolean get() = startedAt > 0
}

/** An interval (Pomodoro) session: [rounds] of work, a rest between them, a long rest every [longEvery]. */
data class Interval(
    val label: String,
    val workMs: Long,
    val restMs: Long,
    val rounds: Int,
    val longRestMs: Long = 0,
    val longEvery: Int = 0,
    val startedAt: Long,
)

/** Something sounding now: [key] is the wakeup that started it ("alarm:3", "timer:7", "interval"). */
data class Ring(val key: String, val label: String, val since: Long)

data class ClockData(
    val alarms: List<Alarm> = emptyList(),
    val timers: List<Timer> = emptyList(),
    val stopwatch: Stopwatch = Stopwatch(),
    val interval: Interval? = null,
    /** When the sleep timer stops playback; 0 when none is set. */
    val sleepUntil: Long = 0,
    /** The world clock's cities; null until the user edits them, which shows the declared defaults. */
    val zones: List<String>? = null,
    /** #1667 the world clock's default city (an IANA zone id); null is the device's zone, as before #1667. */
    val home: String? = null,
    val ringing: List<Ring> = emptyList(),
    /** The last id handed out; ids are never reused, so a stale wakeup cannot hit a newer item. */
    val seq: Long = 0,
)

/** ClockData ⇄ JSON, the one persisted form (SharedPreferences on the phone). */
object ClockCodec {
    fun encode(d: ClockData): String = JSONObject()
        .put("alarms", JSONArray(d.alarms.map { a ->
            JSONObject().put("id", a.id).put("minute", a.minuteOfDay).put("days", a.days).put("enabled", a.enabled)
                .put("label", a.label).put("vibrate", a.vibrate).put("sound", a.sound).put("snooze", a.snoozeMinutes)
                .put("quiet", a.quiet).put("snoozed_until", a.snoozedUntil)
        }))
        .put("timers", JSONArray(d.timers.map { t ->
            JSONObject().put("id", t.id).put("label", t.label).put("duration", t.durationMs).put("state", t.state.name)
                .put("ends_at", t.endsAt).put("remaining", t.remainingMs)
        }))
        .put("stopwatch", JSONObject().put("started_at", d.stopwatch.startedAt).put("accumulated", d.stopwatch.accumulatedMs)
            .put("laps", JSONArray(d.stopwatch.laps)))
        .put("interval", d.interval?.let { i ->
            JSONObject().put("label", i.label).put("work", i.workMs).put("rest", i.restMs).put("rounds", i.rounds)
                .put("long_rest", i.longRestMs).put("long_every", i.longEvery).put("started_at", i.startedAt)
        } ?: JSONObject.NULL)
        .put("sleep_until", d.sleepUntil)
        .put("zones", d.zones?.let { JSONArray(it) } ?: JSONObject.NULL)
        .put("home", d.home ?: JSONObject.NULL)
        .put("ringing", JSONArray(d.ringing.map { JSONObject().put("key", it.key).put("label", it.label).put("since", it.since) }))
        .put("seq", d.seq)
        .toString()

    /** Anything unreadable is an empty clock, never a crash: a corrupt store must not take alarms' UI down. */
    fun decode(json: String?): ClockData = runCatching {
        val o = JSONObject(json ?: return ClockData())
        ClockData(
            alarms = o.optJSONArray("alarms").objects().map { a ->
                Alarm(
                    a.getLong("id"), a.getInt("minute"), a.optInt("days"), a.optBoolean("enabled", true), a.optString("label"),
                    a.optBoolean("vibrate", true), a.optString("sound"), a.optInt("snooze", 10), a.optBoolean("quiet"),
                    a.optLong("snoozed_until"),
                )
            },
            timers = o.optJSONArray("timers").objects().map { t ->
                Timer(
                    t.getLong("id"), t.optString("label"), t.getLong("duration"),
                    TimerState.valueOf(t.optString("state", TimerState.IDLE.name)), t.optLong("ends_at"), t.optLong("remaining"),
                )
            },
            stopwatch = o.optJSONObject("stopwatch")?.let { s ->
                Stopwatch(s.optLong("started_at"), s.optLong("accumulated"), s.optJSONArray("laps").longs())
            } ?: Stopwatch(),
            interval = o.optJSONObject("interval")?.let { i ->
                Interval(
                    i.optString("label"), i.getLong("work"), i.getLong("rest"), i.getInt("rounds"),
                    i.optLong("long_rest"), i.optInt("long_every"), i.getLong("started_at"),
                )
            },
            sleepUntil = o.optLong("sleep_until"),
            zones = o.optJSONArray("zones")?.let { z -> (0 until z.length()).map { z.getString(it) } },
            home = if (o.isNull("home")) null else o.optString("home").ifBlank { null },
            ringing = o.optJSONArray("ringing").objects().map { Ring(it.getString("key"), it.optString("label"), it.getLong("since")) },
            seq = o.optLong("seq"),
        )
    }.getOrDefault(ClockData())

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }
    private fun JSONArray?.longs(): List<Long> = if (this == null) emptyList() else (0 until length()).map { getLong(it) }
}

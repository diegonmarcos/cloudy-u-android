package com.diegonmarcos.cloudcalc.debugapi

import android.app.AlarmManager
import android.content.Context
import com.diegonmarcos.cloudcalc.BuildConfig
import com.diegonmarcos.clocktools.Alarm
import com.diegonmarcos.clocktools.ClockTools
import com.diegonmarcos.clocktools.ClockEngine
import com.diegonmarcos.clocktools.ClockLogic
import com.diegonmarcos.clocktools.ClockService
import com.diegonmarcos.superapp.devtools.AppDebugServer
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * The Clock on the fleet debug API (#768), so alarms and timers can be verified and driven with the
 * screen locked:
 *
 *   /api/clock/status                              everything stored, each item's planned wakeup and
 *                                                  whether the system really holds it
 *   /api/clock/timer_start?seconds=90&label=x      a real timer, started
 *   /api/clock/timer_cancel?id=7                   delete it (and its wakeup)
 *   /api/clock/alarm_add?time=07:30&days=31&label= an alarm (days: bit 0 = Monday … 64 = Sunday, 0 once)
 *   /api/clock/alarm_delete?id=3
 *   /api/clock/dismiss?key=timer:7                 stop a ring
 *
 * Every write goes through ClockEngine — the path the screens and notification buttons take — and
 * every answer is the status read back AFTER it, so a reply reports what the store and
 * AlarmManager hold, never what the handler meant to do. The op is `status`, not `state`: the
 * update-ack guard owns the `state` key of every `when (op)` route table (GET /api/state).
 */
object ClockDebugApi {
    @Volatile private var registered = false

    fun register(ctx: Context) {
        if (registered) return
        registered = true
        val app = ctx.applicationContext
        AppDebugServer.route(
            BuildConfig.DEBUG_API_CLOCK_GROUP,
            listOf(
                AppDebugServer.Op("status", "", "alarms, timers, stopwatch, interval, sleep timer, rings; each planned wakeup and whether AlarmManager holds it"),
                AppDebugServer.Op("timer_start", "seconds=<n>&label=<text, optional>", "start a timer through the app's own engine"),
                AppDebugServer.Op("timer_cancel", "id=<timer id>", "delete a timer and its wakeup"),
                AppDebugServer.Op("alarm_add", "time=HH:MM&days=<bitmask, Monday=1 … Sunday=64, 0 once>&label=<text>", "add an enabled alarm"),
                AppDebugServer.Op("alarm_delete", "id=<alarm id>", "delete an alarm and its wakeup"),
                AppDebugServer.Op("dismiss", "key=<alarm:N | timer:N | interval>", "stop a ring"),
            ),
        ) { op, q -> handle(app, op, q) }
    }

    fun handle(ctx: Context, op: String, q: Map<String, String>): String? = when (op) {
        "status" -> status(ctx).toString()
        "timer_start" -> {
            val seconds = q["seconds"]?.toLongOrNull()?.takeIf { it > 0 }
            if (seconds == null) error("seconds must be a positive whole number")
            else after(ctx, "id", ClockEngine.addTimer(ctx, q["label"] ?: "debug", seconds * ClockLogic.SECOND_MS, start = true))
        }
        "timer_cancel" -> {
            val id = q["id"]?.toLongOrNull()
            if (id == null || ClockEngine.load(ctx).timers.none { it.id == id }) error("no timer ${q["id"]}")
            else { ClockEngine.timer(ctx, id, "delete"); after(ctx, "id", id) }
        }
        "alarm_add" -> {
            val minute = ClockLogic.parseTime(q["time"].orEmpty())
            if (minute == null) error("time must be HH:MM (24-hour)")
            else after(ctx, "id", ClockEngine.saveAlarm(ctx, Alarm(
                0, minute, days = (q["days"]?.toIntOrNull() ?: 0) and ClockLogic.ALL_DAYS, label = q["label"].orEmpty(),
                vibrate = ClockTools.config(ctx).vibrateDefault, snoozeMinutes = ClockTools.config(ctx).snoozeDefault,
            )))
        }
        "alarm_delete" -> {
            val id = q["id"]?.toLongOrNull()
            if (id == null || ClockEngine.load(ctx).alarms.none { it.id == id }) error("no alarm ${q["id"]}")
            else { ClockEngine.deleteAlarm(ctx, id); after(ctx, "id", id) }
        }
        "dismiss" -> {
            val key = q["key"].orEmpty()
            if (ClockEngine.load(ctx).ringing.none { it.key == key }) error("nothing rings as '$key'")
            else { ClockEngine.dismiss(ctx, key); after(ctx, "key", key) }
        }
        else -> null
    }

    private fun error(why: String): String = JSONObject().put("ok", false).put("error", why).toString()

    /** The status read back after a write, with the id the write produced. */
    private fun after(ctx: Context, name: String, value: Any): String = JSONObject().put(name, value).put("status", status(ctx)).toString()

    private fun iso(ms: Long): Any = if (ms > 0) Instant.ofEpochMilli(ms).atZone(ClockEngine.zone()).toOffsetDateTime().toString() else JSONObject.NULL

    fun status(ctx: Context): JSONObject {
        val d = ClockEngine.load(ctx)
        val now = ClockEngine.now()
        val zone = ClockEngine.zone()
        val plan = ClockLogic.plan(d, now, zone, ClockTools.config(ctx).ringMs)
        val planned = plan.associateBy { it.key }
        fun wake(key: String): JSONObject? = planned[key]?.let {
            JSONObject().put("at", it.at).put("at_local", iso(it.at)).put("alarm_clock", it.clock).put("pending", ClockEngine.isScheduled(ctx, key))
        }
        val next = runCatching { ctx.getSystemService(AlarmManager::class.java)?.nextAlarmClock }.getOrNull()
        return JSONObject()
            .put("now", now).put("now_local", iso(now)).put("zone", zone.id)
            .put("can_schedule_exact", ClockEngine.canExact(ctx))
            // The SYSTEM's next alarm clock: ours when its show-intent is this package's.
            .put("system_next_alarm_clock", next?.let {
                JSONObject().put("at", it.triggerTime).put("at_local", iso(it.triggerTime)).put("ours", it.showIntent?.creatorPackage == ctx.packageName)
            } ?: JSONObject.NULL)
            .put("service_running", ClockService.running())
            .put("alarms", JSONArray(d.alarms.map { a ->
                JSONObject().put("id", a.id).put("time", ClockLogic.timeText(a.minuteOfDay)).put("days", a.days)
                    .put("days_text", ClockLogic.daysText(a.days, java.time.DayOfWeek.MONDAY) { it.name.take(3) })
                    .put("enabled", a.enabled).put("label", a.label).put("quiet", a.quiet).put("vibrate", a.vibrate)
                    .put("sound", a.sound.ifBlank { "default" }).put("snooze_minutes", a.snoozeMinutes)
                    .put("snoozed_until", iso(a.snoozedUntil))
                    .put("wakeup", wake(ClockLogic.alarmKey(a.id)) ?: JSONObject.NULL)
            }))
            .put("timers", JSONArray(d.timers.map { t ->
                JSONObject().put("id", t.id).put("label", t.label).put("state", t.state.name).put("duration_ms", t.durationMs)
                    .put("remaining_ms", ClockLogic.remaining(t, now)).put("ends_at", iso(t.endsAt))
                    .put("wakeup", wake(ClockLogic.timerKey(t.id)) ?: JSONObject.NULL)
            }))
            .put("stopwatch", JSONObject().put("running", d.stopwatch.running).put("elapsed_ms", ClockLogic.elapsed(d.stopwatch, now))
                .put("laps_ms", JSONArray(ClockLogic.lapTimes(d.stopwatch))))
            .put("interval", d.interval?.let { i ->
                val p = ClockLogic.phaseAt(i, now)
                JSONObject().put("label", i.label).put("rounds", i.rounds).put("phase", p?.kind?.name ?: JSONObject.NULL)
                    .put("round", p?.round ?: JSONObject.NULL).put("phase_ends", iso(p?.endsAt ?: 0))
                    .put("wakeup", wake(ClockLogic.INTERVAL) ?: JSONObject.NULL)
            } ?: JSONObject.NULL)
            .put("sleep_until", iso(d.sleepUntil))
            .put("ringing", JSONArray(d.ringing.map { JSONObject().put("key", it.key).put("label", it.label).put("since", iso(it.since)) }))
            .put("plan", JSONArray(plan.map { w ->
                JSONObject().put("key", w.key).put("at_local", iso(w.at)).put("alarm_clock", w.clock).put("pending", ClockEngine.isScheduled(ctx, w.key))
            }))
            .put("zones", JSONArray(d.zones ?: ClockTools.config(ctx).zones))
    }
}

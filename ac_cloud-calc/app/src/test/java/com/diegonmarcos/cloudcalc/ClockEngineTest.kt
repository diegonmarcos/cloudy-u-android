package com.diegonmarcos.cloudcalc

import android.app.AlarmManager
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import com.diegonmarcos.clocktools.Alarm
import com.diegonmarcos.clocktools.ClockBootReceiver
import com.diegonmarcos.clocktools.ClockCodec
import com.diegonmarcos.clocktools.ClockTools
import com.diegonmarcos.clocktools.ClockEngine
import com.diegonmarcos.clocktools.ClockLogic
import com.diegonmarcos.clocktools.ClockLogic.MINUTE_MS
import com.diegonmarcos.clocktools.ClockReceiver
import com.diegonmarcos.clocktools.ClockService
import com.diegonmarcos.clocktools.TimerState
import com.diegonmarcos.cloudcalc.debugapi.ClockDebugApi
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The Android half of the Clock against Robolectric's AlarmManager, receivers and service: what
 * is really scheduled, that a wakeup delivered to a process holding nothing in memory still rings,
 * that a reboot or a zone change re-plans, that the notification buttons and /api/clock/… drive
 * the same engine. Time is ClockEngine.now, moved by hand.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClockEngineTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val am: AlarmManager get() = app.getSystemService(AlarmManager::class.java)
    private val madrid = ZoneId.of("Europe/Madrid")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int, zone: ZoneId = madrid) =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    // Friday 2026-10-02, noon in Madrid.
    private var t = at(2026, 10, 2, 12, 0)

    @Before fun setUp() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        ClockEngine.now = { t }
        ClockEngine.zone = { madrid }
    }

    @After fun tearDown() {
        ClockEngine.now = { System.currentTimeMillis() }
        ClockEngine.zone = { ZoneId.systemDefault() }
    }

    private fun scheduled(): List<ShadowAlarmManager.ScheduledAlarm> = shadowOf(am).getScheduledAlarms()
    @Suppress("DEPRECATION")
    private fun keyOf(a: ShadowAlarmManager.ScheduledAlarm): String? = shadowOf(a.operation).getSavedIntent().getStringExtra(ClockEngine.EXTRA_KEY)
    private fun wakeup(key: String) = scheduled().single { keyOf(it) == key }
    private fun hasWakeup(key: String) = scheduled().any { keyOf(it) == key }
    @Suppress("DEPRECATION")
    private fun deliver(a: ShadowAlarmManager.ScheduledAlarm) = ClockReceiver().onReceive(app, shadowOf(a.operation).getSavedIntent())

    private fun startedServices(): List<String> = generateSequence { shadowOf(app).nextStartedService }.mapNotNull { it.component?.className }.toList()

    // ── what is scheduled ───────────────────────────────────────────────────────────────────

    @Test fun `an alarm is an AlarmManager alarm clock at its next local time`() {
        val id = ClockEngine.saveAlarm(app, Alarm(0, 7 * 60, days = ClockLogic.WEEKDAYS))
        val w = wakeup("alarm:$id")
        // Friday noon -> Monday 07:00.
        assertEquals(at(2026, 10, 5, 7, 0), w.getTriggerAtMs())
        assertEquals(AlarmManager.RTC_WAKEUP, w.getType())
        assertNotNull("an alarm must be an alarm clock: exact in Doze, shown in the status bar", w.getAlarmClockInfo())
        assertEquals(at(2026, 10, 5, 7, 0), am.nextAlarmClock.triggerTime)
        assertTrue(ClockEngine.isScheduled(app, "alarm:$id"))
    }

    @Test fun `a timer is an exact while-idle wakeup at its end, moved by pause and resume`() {
        val id = ClockEngine.addTimer(app, "tea", 5 * MINUTE_MS, start = true)
        val w = wakeup("timer:$id")
        assertEquals(t + 5 * MINUTE_MS, w.getTriggerAtMs())
        assertNull(w.getAlarmClockInfo())
        assertTrue(w.isAllowWhileIdle())
        t += 2 * MINUTE_MS
        ClockEngine.timer(app, id, "pause")
        assertFalse("a paused timer has no wakeup", hasWakeup("timer:$id"))
        assertFalse(ClockEngine.isScheduled(app, "timer:$id"))
        t += 10 * MINUTE_MS
        ClockEngine.timer(app, id, "start")
        assertEquals(t + 3 * MINUTE_MS, wakeup("timer:$id").getTriggerAtMs())
        ClockEngine.timer(app, id, "add")
        assertEquals(t + 4 * MINUTE_MS, wakeup("timer:$id").getTriggerAtMs())
    }

    @Test fun `deleting or switching off an alarm cancels its wakeup`() {
        val a = ClockEngine.saveAlarm(app, Alarm(0, 7 * 60))
        val b = ClockEngine.saveAlarm(app, Alarm(0, 8 * 60))
        ClockEngine.deleteAlarm(app, a)
        assertFalse(hasWakeup("alarm:$a"))
        assertFalse(ClockEngine.isScheduled(app, "alarm:$a"))
        ClockEngine.saveAlarm(app, ClockEngine.load(app).alarms.single().copy(enabled = false))
        assertFalse(hasWakeup("alarm:$b"))
        assertTrue(scheduled().isEmpty())
    }

    @Test fun `without exact-alarm permission a wakeup is still set, never as an alarm clock`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        val id = ClockEngine.saveAlarm(app, Alarm(0, 7 * 60))
        val w = wakeup("alarm:$id")
        assertNull(w.getAlarmClockInfo())
        assertEquals(at(2026, 10, 3, 7, 0), w.getTriggerAtMs())
    }

    // ── process death, reboot, time zones ───────────────────────────────────────────────────

    @Test fun `a running timer survives process death and rings from the system's wakeup`() {
        val id = ClockEngine.addTimer(app, "eggs", MINUTE_MS, start = true)
        assertTrue("a running timer starts the foreground service", ClockService::class.java.name in startedServices())

        // The process dies. Nothing of the timer is in memory: what a new process has is the
        // store, read raw here, and the PendingIntent AlarmManager holds.
        val stored = ClockCodec.decode(app.getSharedPreferences(ClockEngine.PREFS, 0).getString("data", null))
        assertEquals(TimerState.RUNNING, stored.timers.single().state)
        assertEquals(t + MINUTE_MS, stored.timers.single().endsAt)
        val w = wakeup("timer:$id")

        t += MINUTE_MS + 50
        deliver(w)
        val d = ClockEngine.load(app)
        assertEquals(TimerState.RINGING, d.timers.single().state)
        assertEquals(listOf("timer:$id"), d.ringing.map { it.key })
        assertTrue("the ring starts the service", ClockService::class.java.name in startedServices())
        // The ring silences itself after the declared ring minutes.
        assertEquals(t + ClockTools.config(app).ringMs, wakeup(ClockLogic.SILENCE).getTriggerAtMs())

        // START_STICKY's restart: a service with no memory reads the store and rings, full-screen.
        val service = Robolectric.buildService(ClockService::class.java).create().startCommand(0, 1)
        val n: Notification = shadowOf(service.get()).lastForegroundNotification
        assertNotNull("a ring is a full-screen notification", n.fullScreenIntent)
        assertEquals(Notification.CATEGORY_ALARM, n.category)
        assertTrue(ClockService.running())

        // Dismiss from the notification's own button.
        @Suppress("DEPRECATION")
        val dismiss = shadowOf(n.actions.first().actionIntent).getSavedIntent()
        ClockReceiver().onReceive(app, dismiss)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val after = ClockEngine.load(app)
        assertTrue(after.ringing.isEmpty())
        assertEquals(TimerState.IDLE, after.timers.single().state)
        assertFalse(hasWakeup(ClockLogic.SILENCE))
        service.destroy()
        assertFalse(ClockService.running())
    }

    @Test fun `a wakeup delivered early or after the item changed rings nothing`() {
        val id = ClockEngine.addTimer(app, "", 5 * MINUTE_MS, start = true)
        val w = wakeup("timer:$id")
        t += MINUTE_MS
        deliver(w)
        assertEquals(TimerState.RUNNING, ClockEngine.load(app).timers.single().state)
        ClockEngine.timer(app, id, "delete")
        t += 10 * MINUTE_MS
        deliver(w)
        assertTrue(ClockEngine.load(app).ringing.isEmpty())
    }

    @Test fun `a reboot clears every wakeup and the boot receiver sets them all again`() {
        val alarm = ClockEngine.saveAlarm(app, Alarm(0, 7 * 60, days = ClockLogic.ALL_DAYS))
        val timer = ClockEngine.addTimer(app, "", 30 * MINUTE_MS, start = true)
        val before = scheduled().associate { keyOf(it) to it.getTriggerAtMs() }
        assertEquals(setOf("alarm:$alarm", "timer:$timer"), before.keys)
        @Suppress("DEPRECATION")
        scheduled().forEach { am.cancel(it.operation!!) }
        assertTrue(scheduled().isEmpty())

        ClockBootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertEquals(before, scheduled().associate { keyOf(it) to it.getTriggerAtMs() })
        assertNotNull(wakeup("alarm:$alarm").getAlarmClockInfo())
    }

    @Test fun `a time zone change moves alarms to the new local time`() {
        val id = ClockEngine.saveAlarm(app, Alarm(0, 7 * 60))
        assertEquals(at(2026, 10, 3, 7, 0), wakeup("alarm:$id").getTriggerAtMs())
        val tokyo = ZoneId.of("Asia/Tokyo")
        ClockEngine.zone = { tokyo }
        ClockBootReceiver().onReceive(app, Intent(Intent.ACTION_TIMEZONE_CHANGED))
        // Noon in Madrid is 19:00 in Tokyo: the next 07:00 there is Saturday's.
        assertEquals(at(2026, 10, 3, 7, 0, tokyo), wakeup("alarm:$id").getTriggerAtMs())
    }

    @Test fun `an alarm rings, snoozes for its own minutes and rings again`() {
        val id = ClockEngine.saveAlarm(app, Alarm(0, 7 * 60, snoozeMinutes = 9, label = "up"))
        t = at(2026, 10, 3, 7, 0)
        deliver(wakeup("alarm:$id"))
        assertEquals(listOf("alarm:$id"), ClockEngine.load(app).ringing.map { it.key })
        assertFalse("a one-shot alarm switches off when it rings", ClockEngine.load(app).alarms.single().enabled)
        ClockEngine.snooze(app, "alarm:$id")
        assertTrue(ClockEngine.load(app).ringing.isEmpty())
        assertEquals(t + 9 * MINUTE_MS, wakeup("alarm:$id").getTriggerAtMs())
        t += 9 * MINUTE_MS
        deliver(wakeup("alarm:$id"))
        assertEquals(listOf("alarm:$id"), ClockEngine.load(app).ringing.map { it.key })
    }

    // ── notification buttons ────────────────────────────────────────────────────────────────

    @Test fun `each running timer has its own notification whose buttons drive the engine`() {
        val a = ClockEngine.addTimer(app, "a", 5 * MINUTE_MS, start = true)
        val b = ClockEngine.addTimer(app, "b", 9 * MINUTE_MS, start = true)
        val service = Robolectric.buildService(ClockService::class.java).create().startCommand(0, 1)
        val nm = app.getSystemService(NotificationManager::class.java)
        val timers = shadowOf(nm).activeNotifications.filter { it.tag == "clock_timer" }
        assertEquals(setOf(a.toInt(), b.toInt()), timers.map { it.id }.toSet())
        val na = timers.single { it.id == a.toInt() }.notification
        assertEquals(t + 5 * MINUTE_MS, na.`when`)
        // Pause is the first action.
        @Suppress("DEPRECATION")
        ClockReceiver().onReceive(app, shadowOf(na.actions.first().actionIntent).getSavedIntent())
        assertEquals(TimerState.PAUSED, ClockEngine.load(app).timers.first { it.id == a }.state)
        assertFalse(hasWakeup("timer:$a"))
        assertTrue(hasWakeup("timer:$b"))
        service.destroy()
    }

    // ── /api/clock/* ────────────────────────────────────────────────────────────────────────

    @Test fun `the debug routes create, report and cancel a test timer`() {
        val started = JSONObject(ClockDebugApi.handle(app, "timer_start", mapOf("seconds" to "90", "label" to "probe"))!!)
        val id = started.getLong("id")
        val timer = started.getJSONObject("status").getJSONArray("timers").getJSONObject(0)
        assertEquals("probe", timer.getString("label"))
        assertEquals("RUNNING", timer.getString("state"))
        assertEquals(t + 90_000, timer.getJSONObject("wakeup").getLong("at"))
        assertTrue("the status reports what AlarmManager holds", timer.getJSONObject("wakeup").getBoolean("pending"))

        val status = JSONObject(ClockDebugApi.handle(app, "status", emptyMap())!!)
        assertTrue(status.getBoolean("can_schedule_exact"))
        assertEquals("timer:$id", status.getJSONArray("plan").getJSONObject(0).getString("key"))

        val cancelled = JSONObject(ClockDebugApi.handle(app, "timer_cancel", mapOf("id" to "$id"))!!)
        assertEquals(0, cancelled.getJSONObject("status").getJSONArray("timers").length())
        assertFalse(ClockEngine.isScheduled(app, "timer:$id"))
        assertFalse(hasWakeup("timer:$id"))

        assertFalse(JSONObject(ClockDebugApi.handle(app, "timer_start", mapOf("seconds" to "x"))!!).getBoolean("ok"))
        assertFalse(JSONObject(ClockDebugApi.handle(app, "timer_cancel", mapOf("id" to "$id"))!!).getBoolean("ok"))
        assertNull(ClockDebugApi.handle(app, "nonsense", emptyMap()))
    }

    @Test fun `the debug routes add and delete an alarm as an alarm clock`() {
        val added = JSONObject(ClockDebugApi.handle(app, "alarm_add", mapOf("time" to "07:30", "days" to "31", "label" to "w"))!!)
        val a = added.getJSONObject("status").getJSONArray("alarms").getJSONObject(0)
        assertEquals("Weekdays", a.getString("days_text"))
        assertTrue(a.getJSONObject("wakeup").getBoolean("alarm_clock"))
        assertEquals(at(2026, 10, 5, 7, 30), a.getJSONObject("wakeup").getLong("at"))
        assertEquals(at(2026, 10, 5, 7, 30), added.getJSONObject("status").getJSONObject("system_next_alarm_clock").getLong("at"))
        assertFalse(JSONObject(ClockDebugApi.handle(app, "alarm_add", mapOf("time" to "25:00"))!!).getBoolean("ok"))
        ClockDebugApi.handle(app, "alarm_delete", mapOf("id" to "${added.getLong("id")}"))
        assertTrue(scheduled().isEmpty())
    }
}

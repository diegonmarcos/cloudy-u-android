package com.diegonmarcos.superapp.notificationcenter

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * #1629 The Notify badges' switch and wake paths, as they run:
 *
 *  - a badge switched OFF really leaves: its card is cancelled and its alarm is not re-armed. Before, the settings
 *    switch only re-ensured the wanted services, so an off badge stayed in the shade, and Weather / MyHealth / Markets
 *    kept being started by their alarm, fetching and arming the next one until the next reboot;
 *  - the Data card's alarm chain survives a start that reads nothing (screen off), so the card is not left stale for
 *    good once the process is gone;
 *  - the AI card reads the network only when asked, or when the last read is minutes old, and never with the screen off.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NotifyDrainTest {

    private val ctx: Application = ApplicationProvider.getApplicationContext()
    private val nm: NotificationManager get() = ctx.getSystemService(NotificationManager::class.java)
    private fun alarmCount(): Int = shadowOf(ctx.getSystemService(AlarmManager::class.java)).scheduledAlarms.size

    @Before fun setUp() {
        shadowOf(ctx).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        NotifyGate.reset()
    }

    @After fun tearDown() { NotifyGate.reset() }

    private fun badge(id: String) = BadgeServices.declared.first { it.id == id }
    private fun switchOff(id: String) = BadgeCustomization.set(ctx, badge(id), BadgeCustomization.KEY_ENABLED, false)
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun shown(id: Int): Boolean = nm.activeNotifications.any { it.id == id }
    private fun screen(on: Boolean) = shadowOf(ctx.getSystemService(PowerManager::class.java)).setIsInteractive(on)

    /** A card as its widget left it in the shade, and the alarm a read left armed. */
    private fun leftBehind(cls: Class<*>, id: Int, badgeId: String) {
        val channel = badge(badgeId).channel
        nm.createNotificationChannel(NotificationChannel(channel, channel, NotificationManager.IMPORTANCE_LOW))
        nm.notify(id, NotificationCompat.Builder(ctx, channel).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("card").build())
        DataWidgetRuntime.scheduleNext(ctx, cls, id, 60_000L)
        assertTrue("control: the card is posted", shown(id))
        assertEquals("control: the alarm is armed", 1, alarmCount())
    }

    // ── 1. Switched off means gone ───────────────────────────────────────

    @Test fun `a switched-off badge is not the same as one that misses a grant`() {
        val weather = WeatherBadgeService::class.java.name
        assertFalse("on by default", BadgeServices.switchedOff(ctx, weather))
        switchOff(WeatherBadgeService.BADGE_ID)
        assertTrue(BadgeServices.switchedOff(ctx, weather))
        assertFalse("the other widgets are untouched", BadgeServices.switchedOff(ctx, MarketsBadgeService::class.java.name))
    }

    @Test fun `the alarm of a switched-off Weather badge retires it instead of fetching and re-arming`() {
        leftBehind(WeatherBadgeService::class.java, WeatherBadgeService.NOTIF_ID, WeatherBadgeService.BADGE_ID)
        switchOff(WeatherBadgeService.BADGE_ID)
        val c = Robolectric.buildService(WeatherBadgeService::class.java).create().startCommand(0, 1) // the alarm fires
        idle()
        assertEquals("the alarm chain of an off badge is still armed", 0, alarmCount())
        assertFalse("the card of an off badge is still in the shade", shown(WeatherBadgeService.NOTIF_ID))
        assertTrue("the service did not stop", shadowOf(c.get()).isStoppedBySelf)
    }

    @Test fun `the alarm of a switched-off MyHealth badge retires it instead of reading and re-arming`() {
        leftBehind(HealthBadgeService::class.java, HealthBadgeService.NOTIF_ID, HealthBadgeService.BADGE_ID)
        switchOff(HealthBadgeService.BADGE_ID)
        val c = Robolectric.buildService(HealthBadgeService::class.java).create().startCommand(0, 1)
        idle()
        assertEquals(0, alarmCount())
        assertFalse(shown(HealthBadgeService.NOTIF_ID))
        assertTrue(shadowOf(c.get()).isStoppedBySelf)
    }

    @Test fun `a switched-off Markets badge no longer re-arms its alarm on every start`() {
        leftBehind(MarketsBadgeService::class.java, MarketsBadgeService.NOTIF_ID, MarketsBadgeService.BADGE_ID)
        switchOff(MarketsBadgeService.BADGE_ID)
        var done = false
        MarketsBadgeService.refresh(ctx, force = true) { done = true }
        idle()
        assertEquals(0, alarmCount())
        assertFalse(shown(MarketsBadgeService.NOTIF_ID))
        assertTrue("the caller is still told it is over", done)
    }

    @Test fun `control - a Markets badge that is on keeps its one alarm`() {
        screen(false) // no network in a unit test: redraw only
        MarketsBadgeService.refresh(ctx, force = true)
        idle()
        assertEquals(1, alarmCount())
    }

    @Test fun `applying the switches takes an off badge's card and alarm down, and leaves the others`() {
        leftBehind(MarketsBadgeService::class.java, MarketsBadgeService.NOTIF_ID, MarketsBadgeService.BADGE_ID)
        // Battery is on and has its grant (notifications): its card must stay.
        val other = badge(BatteryBadgeService.BADGE_ID).channel
        nm.createNotificationChannel(NotificationChannel(other, other, NotificationManager.IMPORTANCE_LOW))
        nm.notify(BatteryBadgeService.NOTIF_ID, NotificationCompat.Builder(ctx, other).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("card").build())
        switchOff(MarketsBadgeService.BADGE_ID)
        NotifyGroups.apply(ctx) // what the settings switch calls
        idle()
        assertFalse("off, yet still in the shade", shown(MarketsBadgeService.NOTIF_ID))
        assertEquals("off, yet its alarm is still armed", 0, alarmCount())
        assertTrue("a badge that is still on was taken down too", shown(BatteryBadgeService.NOTIF_ID))
    }

    // ── 2. The Data card's alarm chain ───────────────────────────────────

    @Test fun `a Data start that finds the screen off reads nothing but keeps the next alarm`() {
        screen(false)
        Robolectric.buildService(DataBadgeService::class.java).create().startCommand(0, 1)
        // The read runs on the badge's worker thread: give it a moment.
        val until = System.currentTimeMillis() + 10_000L
        while (alarmCount() == 0 && System.currentTimeMillis() < until) { idle(); Thread.sleep(20) }
        assertEquals("the chain ended: with the process gone the card is never read again", 1, alarmCount())
        assertFalse("nothing is read or posted with the screen off", shown(DataBadgeService.NOTIF_ID))
    }

    // ── 3. The AI card's reads ───────────────────────────────────────────

    @Test fun `an AI read nobody asked for waits minutes, an asked one never waits, and none runs with the screen off`() {
        val t = 50_000_000L
        assertTrue("first read", AiBadgeService.readDue(force = false, nowMs = t, lastReadMs = 0L))
        assertFalse("21 s after the last read used to be a new HTTPS round trip", AiBadgeService.readDue(false, t + 21_000L, t))
        assertFalse(AiBadgeService.readDue(false, t + AiBadgeService.AUTO_GAP_MS - 1, t))
        assertTrue(AiBadgeService.readDue(false, t + AiBadgeService.AUTO_GAP_MS, t))
        assertTrue("the Refresh button", AiBadgeService.readDue(true, t + 1, t))
        assertTrue("a clock that went back must not block reads", AiBadgeService.readDue(false, t - 1, t))
        assertTrue("under the 5-minute tick, so the tick is always due", AiBadgeService.AUTO_GAP_MS in 60_000L until 5 * 60_000L)
        assertFalse(AiBadgeService.fetchAllowed(interactive = false))
        assertTrue(AiBadgeService.fetchAllowed(interactive = true))

        // An hour of unlocking the phone every 30 s.
        var last = 0L; var reads = 0
        for (i in 0 until 120) {
            val now = t + i * 30_000L
            if (AiBadgeService.readDue(false, now, last)) { reads++; last = now }
        }
        assertEquals("one read per 4 minutes, not one per unlock (was 120)", 15, reads)
    }
}

package com.diegonmarcos.superapp.notificationcenter

import android.app.Application
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.floatingnav.FloatingNavConfig
import com.diegonmarcos.superapp.floatingnav.FloatingNavPrefs
import com.diegonmarcos.superapp.floatingnav.FloatingNavService
import com.diegonmarcos.superapp.network.MeshSupervisorRunner
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSettings
import org.robolectric.shadows.ShadowWindowManagerImpl
import java.io.File
import java.time.Duration

/**
 * Background battery drains found by the audit: nothing wakes the phone while the screen is off, the polls are slow and
 * back off, the media badge follows the session events instead of the poll, a healthy mesh tunnel has no self-kick, and
 * the three pure data widgets (Data, AI, Markets) are no longer always-on foreground services. The badges still show
 * the same data: only WHEN they look changed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BackgroundWakeupsTest {

    private val main = "src/main/java/com/diegonmarcos/superapp"
    private fun code(path: String) = File("$main/$path").readLines()
        .filterNot { it.trim().startsWith("*") || it.trim().startsWith("//") || it.trim().startsWith("/*") }.joinToString("\n")

    private var svc: ServiceController<FloatingNavService>? = null
    @After fun tearDown() { svc?.destroy(); FloatingNavService.pollExecutor = null }

    // ── 1. FloatingNav ───────────────────────────────────────────────────

    @Test fun `floating nav poll_ms is at least 5 seconds and backs off to 15 seconds when the foreground is unchanged`() {
        assertTrue(FloatingNavConfig.get().pollMs >= 5_000L)
        assertEquals(5_000L, FloatingNavService.pollDelayMs(5_000L, 0))
        assertEquals(5_000L, FloatingNavService.pollDelayMs(5_000L, FloatingNavService.IDLE_AFTER_TICKS - 1))
        assertEquals(15_000L, FloatingNavService.pollDelayMs(5_000L, FloatingNavService.IDLE_AFTER_TICKS))
        assertEquals("a declared interval longer than the back-off is kept", 20_000L, FloatingNavService.pollDelayMs(20_000L, 10))
    }

    @Test fun `the media badge is driven by session events, not by the poll tick`() {
        val nav = code("floatingnav/FloatingNavService.kt")
        val tick = nav.substringAfter("private val pollTick").substringBefore("private fun foregroundPackage")
        assertFalse("media.refresh() is still in the poll tick", tick.contains("media.refresh"))
        assertTrue("the poll tick must stop when the screen is off", tick.contains("if (!interactive()) return"))
        val media = code("floatingnav/MediaProxy.kt")
        assertTrue(media.contains("addOnActiveSessionsChangedListener"))
        assertTrue(media.contains("registerCallback"))
        assertTrue(media.contains("removeOnActiveSessionsChangedListener"))
    }

    private fun windows(ctx: Context): Int =
        Shadow.extract<ShadowWindowManagerImpl>(ctx.getSystemService(WindowManager::class.java)).views.size

    @Test fun `floating nav does not poll while the screen is off and resumes on screen on`() {
        FloatingNavService.pollExecutor = java.util.concurrent.Executor { it.run() } // the poll's UsageStats read runs inline, so idle() sees its result
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        ctx.getSharedPreferences("floatingnav_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        ShadowSettings.setCanDrawOverlays(true)
        FloatingNavPrefs.setEnabled(ctx, true)
        val usm = shadowOf(ctx.getSystemService(UsageStatsManager::class.java))
        val now = { System.currentTimeMillis() }
        usm.addEvent("org.example.other", now() - 1_000, UsageEvents.Event.MOVE_TO_FOREGROUND)
        val c = Robolectric.buildService(FloatingNavService::class.java).create().startCommand(0, 1)
        svc = c
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("control: the bubble is drawn over the other app", 1, windows(ctx))

        ctx.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        shadowOf(Looper.getMainLooper()).idle()
        // The owner is "back home" by the usage events, but with the screen off nobody asks.
        usm.addEvent(ctx.packageName, now() - 100, UsageEvents.Event.MOVE_TO_FOREGROUND)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(40))
        assertEquals("a poll ran while the screen was off", 1, windows(ctx))

        usm.addEvent(ctx.packageName, now() - 100, UsageEvents.Event.MOVE_TO_FOREGROUND)
        ctx.sendBroadcast(Intent(Intent.ACTION_SCREEN_ON))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("screen on must look at once", 0, windows(ctx))
    }

    // ── 2. Mesh supervisor ───────────────────────────────────────────────

    @Test fun `a healthy tunnel has no self-kick and an unhealthy one waits at least five minutes`() {
        val now = 1_000_000L
        assertNull("healthy and nothing pending: no 30 s self-kick", MeshSupervisorRunner.nextLookMs(true, true, true, null, null, now))
        assertTrue(MeshSupervisorRunner.WATCH_MS >= 5 * 60_000L)
        assertEquals(MeshSupervisorRunner.WATCH_MS, MeshSupervisorRunner.nextLookMs(true, true, false, null, null, now))
        assertEquals("a backoff retry is still honoured", 20_000L, MeshSupervisorRunner.nextLookMs(true, true, false, now + 20_000L, null, now))
        assertEquals("an open alert debounce is still honoured", 10_000L, MeshSupervisorRunner.nextLookMs(true, true, true, null, now + 10_000L, now))
        assertNull("not wanted: no look", MeshSupervisorRunner.nextLookMs(false, true, false, null, null, now))
        assertNull("no engine: no look", MeshSupervisorRunner.nextLookMs(true, false, false, null, null, now))
    }

    @Test fun `signal strength is not a capability change but a transport or validation change is`() {
        val wifi = MeshSupervisorRunner.capSig(wifi = true, cell = false, eth = false, vpn = false, validated = true, internet = true)
        assertEquals(wifi, MeshSupervisorRunner.capSig(wifi = true, cell = false, eth = false, vpn = false, validated = true, internet = true))
        assertNotEquals(wifi, MeshSupervisorRunner.capSig(wifi = true, cell = false, eth = false, vpn = false, validated = false, internet = true))
        assertNotEquals(wifi, MeshSupervisorRunner.capSig(wifi = false, cell = true, eth = false, vpn = false, validated = true, internet = true))
        val src = code("network/MeshSupervisorRunner.kt")
        assertTrue(src.contains("if (sig != lastCapSig)"))
        assertTrue("the note is written even when unchanged", src.contains("if (lastNote(ctx) == s) return"))
        assertTrue("no screen-off gate on the tick", src.contains("!isInteractive(ctx)"))
    }

    // ── 3. Network badge ─────────────────────────────────────────────────

    @Test fun `the network tick needs the tunnel up and the screen on and is at least five minutes`() {
        assertTrue(NetworkBadgeService.shouldTick(connected = true, interactive = true))
        assertFalse(NetworkBadgeService.shouldTick(connected = true, interactive = false))
        assertFalse(NetworkBadgeService.shouldTick(connected = false, interactive = true))
        assertTrue(NetworkBadgeService.TICK_MS >= 5 * 60_000L)
        val src = code("notificationcenter/NetworkBadgeService.kt")
        assertTrue(src.contains("ACTION_SCREEN_ON") && src.contains("ACTION_SCREEN_OFF"))
    }

    // ── 4. Battery badge ─────────────────────────────────────────────────

    @Test fun `battery events are coalesced to 60 to 120 seconds and only a level or plug change counts`() {
        assertTrue(BatteryBadgeService.MIN_GAP_MS in 60_000L..120_000L)
        assertTrue("first reading", BatteryBadgeService.changed(71, 0))
        assertFalse("voltage / temperature jitter: same level, same plug", BatteryBadgeService.changed(71, 0))
        assertTrue("level moved", BatteryBadgeService.changed(70, 0))
        assertTrue("plug edge", BatteryBadgeService.changed(70, 2))
        assertFalse(BatteryBadgeService.changed(70, 2))
    }

    // ── 5. Markets badge ─────────────────────────────────────────────────

    @Test fun `markets fetches only with the screen on, never in power save, and only when due`() {
        val h = 3_600_000L
        assertFalse("screen off", MarketsBadgeService.fetchDue(false, false, 10 * h, 0L, h, force = true))
        assertFalse("power save", MarketsBadgeService.fetchDue(true, true, 10 * h, 0L, h, force = true))
        assertTrue("screen on, stale", MarketsBadgeService.fetchDue(true, false, 10 * h, 0L, h, force = false))
        assertFalse("screen on, fresh", MarketsBadgeService.fetchDue(true, false, 10 * h, 10 * h - 1_000L, h, force = false))
        assertTrue("explicit start refreshes a fresh one", MarketsBadgeService.fetchDue(true, false, 10 * h, 10 * h - 1_000L, h, force = true))
        assertTrue(MarketsBadgeService.DEFAULT_REFRESH_MIN in 60L..120L)
        val declared = BadgeServices.declared.first { it.id == MarketsBadgeService.BADGE_ID }
        assertEquals(MarketsBadgeService.DEFAULT_REFRESH_MIN.toString(), declared.customization.first { it.key == "refresh_minutes" }.default)
    }
}

package com.diegonmarcos.superapp.floatingnav

import android.app.Application
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.notificationcenter.BadgeServices
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSettings
import org.robolectric.shadows.ShadowWindowManagerImpl

/**
 * #775 — Configs ▸ One-Hand ▸ "Floating button" OFF must stay off.
 *
 * FloatingNavService is also the badge host (Quick Actions / Media / Alerts),
 * so it is started BARE by BadgeServices.ensureAll / launch and by the sticky
 * restart. Those starts used to bring the button back because only
 * startIfPermitted read the switch. Every case here runs the real service with
 * the overlay granted and a foreground app the button would draw over, and
 * counts the windows actually added to the WindowManager.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FloatingNavOffSticksTest {

    private lateinit var ctx: Application
    private var svc: ServiceController<FloatingNavService>? = null

    @Before fun setUp() {
        FloatingNavService.pollExecutor = java.util.concurrent.Executor { it.run() } // the poll's UsageStats read runs inline, so idle() sees its result
        ctx = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences("floatingnav_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        ShadowSettings.setCanDrawOverlays(true)
        // Some other app is in front — the case where the bubble is drawn.
        shadowOf(ctx.getSystemService(UsageStatsManager::class.java)).addEvent(
            "org.example.other", System.currentTimeMillis() - 1_000, UsageEvents.Event.MOVE_TO_FOREGROUND)
    }

    @After fun tearDown() { svc?.destroy(); FloatingNavService.pollExecutor = null }

    private fun windows(): Int =
        Shadow.extract<ShadowWindowManagerImpl>(ctx.getSystemService(WindowManager::class.java)).views.size

    /** A bare start, exactly what BadgeServices.launch and the sticky restart deliver. */
    private fun bareStart(intent: Intent? = null): FloatingNavService {
        val c = Robolectric.buildService(FloatingNavService::class.java, intent).create().startCommand(0, 1)
        svc = c
        shadowOf(Looper.getMainLooper()).idle()
        return c.get()
    }

    @Test fun `control - with the switch ON a bare start draws the button`() {
        FloatingNavPrefs.setEnabled(ctx, true)
        bareStart()
        assertEquals("the test must be able to see the bubble, or the OFF cases prove nothing", 1, windows())
        assertTrue(FloatingNavService.bubbleDrawn)
    }

    @Test fun `switch OFF - a bare start (badge launch, sticky restart) draws nothing`() {
        FloatingNavPrefs.setEnabled(ctx, false)
        bareStart()
        assertEquals(0, windows())
        assertFalse(FloatingNavService.bubbleDrawn)
        assertFalse(FloatingNavService.armed)
    }

    @Test fun `switch OFF - ensureAll on boot or update starts the service, and it stays headless`() {
        FloatingNavPrefs.setEnabled(ctx, false)
        BadgeServices.ensureAll(ctx)
        val started = generateSequence { shadowOf(ctx).nextStartedService }.toList()
        val intent = started.firstOrNull { it.component?.className == FloatingNavService::class.java.name }
        assertNotNull("the badges still need their host on boot", intent)
        bareStart(intent)
        assertEquals(0, windows())
    }

    @Test fun `switch OFF - badges are still posted`() {
        FloatingNavPrefs.setEnabled(ctx, false)
        val s = bareStart()
        val n = shadowOf(s).lastForegroundNotification
        assertNotNull("Quick Actions is the service's foreground notification", n)
        assertEquals("floating_nav", n!!.channelId)
        assertFalse(shadowOf(s).isStoppedBySelf)
    }

    @Test fun `switch OFF - SHOW_MENU draws no bar either`() {
        FloatingNavPrefs.setEnabled(ctx, false)
        bareStart(Intent(ctx, FloatingNavService::class.java).setAction(FloatingNavService.ACTION_SHOW_MENU))
        assertEquals(0, windows())
        assertFalse(FloatingNavService.barDrawn)
        assertFalse(FloatingNavService.showMenu(ctx))
    }

    @Test fun `turning it OFF takes a visible button down at once, without waiting a poll`() {
        FloatingNavPrefs.setEnabled(ctx, true)
        bareStart()
        assertEquals(1, windows())
        FloatingNavService.setEnabled(ctx, false)
        shadowOf(Looper.getMainLooper()).idle() // runs what is due NOW; the next poll is poll_ms away
        assertEquals(0, windows())
    }

    @Test fun `turning it OFF keeps the badge host running when its badges want it`() {
        FloatingNavPrefs.setEnabled(ctx, true)
        bareStart()
        FloatingNavService.setEnabled(ctx, false)
        assertNull("badges are on and granted, so the service must not be stopped", shadowOf(ctx).nextStoppedService)
    }

    @Test fun `turning it OFF stops the service when no badge wants it`() {
        ShadowSettings.setCanDrawOverlays(false) // every hosted badge requires the overlay grant
        FloatingNavService.setEnabled(ctx, false)
        assertEquals(FloatingNavService::class.java.name, shadowOf(ctx).nextStoppedService?.component?.className)
    }

    // ── Same audit, the other overlay with a bare-start path ─────────────
    // ShellActivity's idle timer starts ScreensaverService directly, not via
    // ScreensaverService.start, so the gate has to hold inside the service.

    @Test fun `screensaver - a bare start without the grant draws no cover`() {
        ShadowSettings.setCanDrawOverlays(false)
        val c = Robolectric.buildService(ScreensaverService::class.java).create()
        assertEquals(0, windows())
        assertTrue(shadowOf(c.get()).isStoppedBySelf)
        c.destroy()
    }

    @Test fun `screensaver - control, with the grant the bare start draws the cover`() {
        val c = Robolectric.buildService(ScreensaverService::class.java).create()
        assertEquals(1, windows())
        assertTrue(ScreensaverService.drawn)
        c.destroy()
        assertEquals(0, windows())
    }
}

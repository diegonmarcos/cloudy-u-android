package com.diegonmarcos.superapp

import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.diegonmarcos.superapp.edgetest.VictimActivity
import com.diegonmarcos.superapp.onehand.OneHandController
import com.diegonmarcos.superapp.onehand.OneHandOverlayService
import com.diegonmarcos.superapp.onehand.OneHandPrefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The edge menus, driven on a REAL emulator through the real input pipeline (UiAutomation.injectInputEvent),
 * in GESTURE navigation (the back-swipe zone is the owner's setup), with the "app below" being an activity of
 * ANOTHER package so the overlay sits over a foreign uid exactly as in life, and with SuperApp itself having
 * no activity on screen (the service must live on its own).
 *
 *   1. service runs, both strips are on screen, 24dp wide, <=160dp long
 *   2. a quick tap beside / above / below the strip reaches the app below (click delivered)
 *   3. a quick tap ON the strip opens nothing and does not crash (documented: that 24x160dp patch is ours)
 *   4. a quick sweep (no hold) opens nothing
 *   5. tap-hold-then-push INWARD from the strip opens the menu window; releasing closes it; the app below
 *      never got a click, was never backgrounded (= the system back gesture did not steal the touch)
 *   6. same on the right strip
 *
 * Run: ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=...EdgeOverlayGestureTest
 */
@RunWith(AndroidJUnit4::class)
class EdgeOverlayGestureTest {
    private val inst: Instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = inst.targetContext
    private val ua: UiAutomation = inst.uiAutomation
    private val dens get() = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = v * dens
    /** The app process cannot write /data/local/tmp (shell-owned): a write there threw inside runCatching and every
     *  screenshot was silently lost. Write where the app can, then hand the copy to the shell (as WebPagesRenderTest does). */
    private val out by lazy { File(ctx.getExternalFilesDir(null), "edge").apply { mkdirs() } }

    private fun sh(cmd: String): String {
        val pfd = ua.executeShellCommand(cmd)
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().readText()
    }

    @Before fun setUp() {
        sh("appops set ${ctx.packageName} SYSTEM_ALERT_WINDOW allow")
        sh("input keyevent KEYCODE_WAKEUP"); sh("wm dismiss-keyguard")
        // The app's own wireless-debugging bootstrap raises SystemUI's "Allow wireless debugging?" dialog on a fresh
        // emulator: it sits above everything, hides non-system overlays (HIDE_NON_SYSTEM_OVERLAY_WINDOWS) and eats
        // touches - the CI run that showed it had every edge gesture fail behind it. Switch it off and dismiss it.
        silenceWirelessDebugKeeper()
        clearPrompt()
        // The owner's setup: gesture navigation (back swipe from the screen edge).
        sh("cmd overlay enable com.android.internal.systemui.navbar.gestural")
        SystemClock.sleep(2500)
        Log.i(TAG, "navMode=${sh("settings get secure navigation_mode").trim()} overlayPerm=${Settings.canDrawOverlays(ctx)}")
        assertTrue("SYSTEM_ALERT_WINDOW not granted", Settings.canDrawOverlays(ctx))
        OneHandPrefs.setDebugVisible(ctx, false)
        OneHandController.enable(ctx)          // persists ON and startService() — with no SuperApp activity alive
        // The victim lives in the test package's own process: start it from here, read it back through logcat.
        sh("logcat -c")
        inst.context.startActivity(Intent(inst.context, VictimActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        waitFor("victim activity") { victimLog().contains("ready") }
        waitFor("overlay service + 2 strips laid out", 15_000) {
            val s = OneHandOverlayService.instance
            s != null && s.handleCount == 2 && s.handleViews.all { it.width > 0 && it.isAttachedToWindow }
        }
        SystemClock.sleep(1000)
        sh("logcat -c")   // count only what this test does
    }

    @get:org.junit.Rule val name = org.junit.rules.TestName()

    @After fun tearDown() {
        sh("logcat -d -v time > /data/local/tmp/log-${name.methodName}.txt")
        OneHandController.disable(ctx)
        SystemClock.sleep(300)
    }

    /** What the app below reported since setUp: one word per line ("ready", "down", "click", "stop"). */
    private fun victimLog(): List<String> = sh("logcat -d -s ${VictimActivity.TAG}:I").lines()
        .mapNotNull { Regex("I ${VictimActivity.TAG}\\s*: (\\w+)").find(it)?.groupValues?.get(1) }
    private fun victimCount(w: String) = victimLog().count { it == w }

    /** Everything needed to see WHY a gesture did not land: strips, victim events, the overlay windows' frames. */
    private fun diag(): String = "\n--- diag: screen=${screenW}x${ctx.resources.displayMetrics.heightPixels} dens=$dens strips=${strips()}" +
        "\nvictim=${victimLog()}" +
        "\nwindows:\n" + sh("dumpsys window windows").lines().filter {
            it.contains("Window{") || it.contains("mFrame") || it.contains("fl=") || it.contains("touchable region") || it.contains("mHasSurface")
        }.filter { true }.take(120).joinToString("\n") +
        "\nback-gesture: " + sh("dumpsys window | grep -i -m6 'exclusion\\|GestureExclusion'")

    private fun waitFor(what: String, ms: Long = 8_000, c: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + ms
        while (SystemClock.uptimeMillis() < end) { if (c()) return; SystemClock.sleep(100) }
        throw AssertionError("timed out waiting for $what" + diag())
    }

    private fun strips(): List<Rect> = OneHandOverlayService.instance!!.handleViews.map {
        val xy = IntArray(2); it.getLocationOnScreen(xy); Rect(xy[0], xy[1], xy[0] + it.width, xy[1] + it.height)
    }
    private val screenW get() = ctx.resources.displayMetrics.widthPixels
    private fun leftStrip() = strips().minByOrNull { it.left }!!
    private fun rightStrip() = strips().maxByOrNull { it.left }!!

    // ── injection ────────────────────────────────────────────────────────────────────────────────
    private var downT = 0L
    private fun ev(action: Int, x: Float, y: Float): MotionEvent {
        val e = MotionEvent.obtain(downT, SystemClock.uptimeMillis(), action, x, y, 0)
        e.source = InputDevice.SOURCE_TOUCHSCREEN
        return e
    }
    private fun inject(action: Int, x: Float, y: Float) {
        val e = ev(action, x, y); ua.injectInputEvent(e, true); e.recycle()
    }
    /**
     * The app's WirelessDebugKeeper re-raises SystemUI's wireless-debugging prompt on its own schedule: clear it
     * before every gesture. 2026-10-17 (run 39065726306): the old blind KEYCODE_BACK fired while the prompt was
     * still starting (its window listed, focus still on the victim), so BACK FINISHED THE VICTIM; the launcher came
     * to front and every later tap "did not reach the app below". BACK now goes only to a FOCUSED prompt, and the
     * victim is brought back to front (and its stop baselined) whenever the prompt dance disturbed it.
     */
    private var stopBase = 0
    private val KEEPER_CLASS = "com.diegonmarcos.superapp.system.WirelessDebugKeeper"

    /**
     * Run 39068681107 (94a8f68d2): the keeper is a PERIODIC worker in the app under test; it re-raised the prompt
     * mid-test, the prompt sat above the victim WITHOUT focus (so the focused-only BACK never fired) and its
     * HIDE_NON_SYSTEM_OVERLAY_WINDOWS hid both strips (isReadyForDisplay=false): 5 swipe/tap tests saw nothing.
     * Stop the source for the duration of the test instead of racing it.
     */
    private fun silenceWirelessDebugKeeper() {
        // #1733: since #1682 the keeper lives in libs:adb-shell-core, which only the ADB-owner build links (the SuperApp is
        // a client; the periodic worker runs in Cloud C3 Webserver). Reach it by NAME so this suite compiles in both builds,
        // and silence it only where it is present - with no keeper in this process there is nothing to race the test.
        val keeper = runCatching { Class.forName(KEEPER_CLASS) }.getOrNull() ?: return
        runCatching {
            val prefs = Class.forName("$KEEPER_CLASS\$Prefs")
            val holder = prefs.getField("INSTANCE").get(null)
            prefs.getMethod("setEnabled", android.content.Context::class.java, Boolean::class.javaPrimitiveType).invoke(holder, ctx, false)
        }
        runCatching {
            val wm = androidx.work.WorkManager.getInstance(ctx)
            val unique = keeper.getField("UNIQUE_NAME").get(null) as String
            wm.cancelUniqueWork(unique)
            wm.cancelAllWorkByTag(KEEPER_CLASS)
        }
    }
    private fun focus(): String = sh("dumpsys window | grep -m1 mCurrentFocus")
    private fun clearPrompt() {
        sh("settings put global adb_wifi_enabled 0")
        var touched = false
        val end = SystemClock.uptimeMillis() + 6_000
        while (SystemClock.uptimeMillis() < end) {
            if (!sh("dumpsys window windows").contains("WifiDebuggingActivity")) break
            touched = true
            // BACK whenever the victim does NOT hold focus (prompt focused, or focus in transit): never into the victim.
            if (!focus().contains("VictimActivity")) sh("input keyevent KEYCODE_BACK")
            SystemClock.sleep(300)
        }
        if (sh("dumpsys window windows").contains("WifiDebuggingActivity"))
            throw AssertionError("wireless-debugging prompt would not go away (it hides the overlay strips)" + diag())
        if (!sh("dumpsys activity activities | grep -m1 topResumedActivity").contains("VictimActivity")) {
            touched = true
            inst.context.startActivity(Intent(inst.context, VictimActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            waitFor("victim back in front") { sh("dumpsys activity activities | grep -m1 topResumedActivity").contains("VictimActivity") }
            SystemClock.sleep(400)
        }
        if (touched) stopBase = victimCount("stop")
    }
    private fun down(x: Float, y: Float) { clearPrompt(); downT = SystemClock.uptimeMillis(); inject(MotionEvent.ACTION_DOWN, x, y) }
    private fun move(x: Float, y: Float) = inject(MotionEvent.ACTION_MOVE, x, y)
    private fun up(x: Float, y: Float) = inject(MotionEvent.ACTION_UP, x, y)
    private fun tap(x: Float, y: Float) { down(x, y); SystemClock.sleep(60); up(x, y); SystemClock.sleep(400) }
    private fun shot(name: String) = runCatching {
        ua.takeScreenshot()?.let { b ->
            val f = File(out, "$name.png")
            f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 90, it) }
            sh("cp ${f.absolutePath} /data/local/tmp/${f.name}")
        }
    }

    private fun svc() = OneHandOverlayService.instance!!

    // ── tests ────────────────────────────────────────────────────────────────────────────────────
    @Test fun stripsAreHostedAndBounded() {
        val svc = svc()
        val l = leftStrip(); val r = rightStrip()
        Log.i(TAG, "left=$l right=$r screenW=$screenW dens=$dens")
        assertEquals(2, svc.handleCount)
        for (s in listOf(l, r)) {
            assertTrue("strip ${s.width()}px wide", s.width() <= dp(com.diegonmarcos.superapp.onehand.EdgeHandleWindow.USER_MAX_THICKNESS_DP).toInt() + 1 && s.width() >= dp(com.diegonmarcos.superapp.onehand.EdgeHandleWindow.MIN_HIT_DP).toInt() - 1)
            assertTrue("strip ${s.height()}px tall", s.height() <= dp(160).toInt() + 1)
        }
        assertTrue("left strip hugs the left edge", l.left <= dp(2).toInt())
        assertTrue("right strip hugs the right edge", r.right >= screenW - dp(2).toInt())
    }

    @Test fun quickTapsNearTheStripReachTheAppBelow() {
        val l = leftStrip()
        val probes = listOf(
            "right of the strip" to Pair(l.right + dp(6), l.exactCenterY()),
            "above the strip" to Pair(l.left + dp(4), l.top - dp(30)),
            "below the strip" to Pair(l.left + dp(4), l.bottom + dp(30)),
        )
        var expect = 0
        for ((what, p) in probes) {
            tap(p.first, p.second); expect++
            assertEquals("tap $what must reach the app below (clicks)" + diag(), expect, victimCount("click"))
        }
        val r = rightStrip()
        tap(r.left - dp(6), r.exactCenterY()); expect++
        assertEquals("tap left of the right strip reaches the app", expect, victimCount("click"))
        assertFalse("a tap must never open the menu", svc().menuShown)
    }

    @Test fun quickTapOnTheStripOpensNothing() {
        val l = leftStrip()
        tap(l.exactCenterX(), l.exactCenterY())
        assertFalse(svc().menuShown)
        assertFalse("app below must still be in front" + diag(), (victimCount("stop") > stopBase))
    }

    /** 2026-10-17: a plain inward swipe (no hold) OPENS the menu - the hold-only contract left it dead on Samsung. */
    @Test fun inwardSwipeWithoutHoldOpensTheMenu() {
        val l = leftStrip(); val x = l.exactCenterX(); val y = l.exactCenterY()
        down(x, y)
        for (i in 1..8) { move(x + dp(10) * i, y); SystemClock.sleep(12) }
        waitFor("menu window after a plain inward swipe", 2_000) { svc().menuShown }
        up(x + dp(80), y); SystemClock.sleep(300)
        assertFalse("menu closes on release", svc().menuShown)
        assertEquals("swipe must not click the app below", 0, victimCount("click"))
    }

    @Test fun sweepAlongTheEdgeOpensNothing() {
        val l = leftStrip(); val x = l.exactCenterX(); val y = l.exactCenterY()
        down(x, y)
        for (i in 1..8) { move(x, y + dp(10) * i); SystemClock.sleep(12) }
        assertFalse("a sweep along the edge must not open the menu", svc().menuShown)
        up(x, y + dp(80)); SystemClock.sleep(300)
        assertFalse(svc().menuShown)
    }

    private fun holdThenPush(edgeLeft: Boolean) {
        val s = if (edgeLeft) leftStrip() else rightStrip()
        val dir = if (edgeLeft) 1f else -1f
        val x = s.exactCenterX(); val y = s.exactCenterY()
        clearPrompt(); SystemClock.sleep(800)
        val stopsBefore = victimCount("stop")   // the prompt activity, if it came and went, stops the victim too: baseline it
        val hold = ViewConfiguration.getLongPressTimeout() + 500L   // above any of: system, build.json hold time
        down(x, y)
        SystemClock.sleep(hold)
        assertFalse("menu must not be open before the push", svc().menuShown)
        for (i in 1..12) { move(x + dir * dp(8) * i, y); SystemClock.sleep(16) }
        waitFor("menu window after tap-hold-and-push (${if (edgeLeft) "left" else "right"})", 3_000) { svc().menuShown }
        shot(if (edgeLeft) "edge-menu-left" else "edge-menu-right")
        Log.i(TAG, "windows:\n" + sh("dumpsys window windows | grep -i 'onehand\\|Window #' | head -20"))
        up(x + dir * dp(96), y)
        waitFor("menu window removed after release", 3_000) { !svc().menuShown }
        assertEquals("hold+push must not click the app below", 0, victimCount("click"))
        assertEquals("system back gesture stole the touch (app below was backgrounded)" + diag(), stopsBefore, victimCount("stop"))
    }

    @Test fun holdThenPushInwardOpensTheMenuFromTheLeftEdge() = holdThenPush(true)
    @Test fun holdThenPushInwardOpensTheMenuFromTheRightEdge() = holdThenPush(false)

    @Test fun menuStillOpensAfterATapAndAFailedSweep() {
        val l = leftStrip()
        tap(l.exactCenterX(), l.exactCenterY())
        val x = l.exactCenterX(); val y = l.exactCenterY()
        down(x, y); for (i in 1..4) { move(x + dp(12) * i, y); SystemClock.sleep(10) }; up(x + dp(48), y); SystemClock.sleep(300)
        holdThenPush(true)
    }

    private companion object { const val TAG = "EdgeOverlayTest" }
}

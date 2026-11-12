package com.diegonmarcos.cloudnav.sky

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression test for the reported "constellations is crashing" bug. Launches
 * ConstellationsActivity directly (exported=false doesn't block same-package
 * instrumentation), waits for the async celestial-data load, and simulates a
 * drag gesture on the Compose sky canvas (the same path used without a working
 * rotation sensor). Any crash here fails the CI job AND surfaces the real stack trace
 * in the already-uploaded logcat.txt artifact -- no physical device needed.
 */
@RunWith(AndroidJUnit4::class)
class ConstellationsRenderTest {

    /**
     * #1547 the catalog is runtime data (Cloud Store serves it through libs:fleet-data-client), never in the APK, and Cloud Store
     * is not on the CI emulator. test-cloud-nav.yml pushes the repo's data/fleet/app/com.diegonmarcos.cloudnav/celestial/ to
     * /data/local/tmp/fleetdata-celestial; this seeds the app's own last-known-good copy from it (read as the shell user, the
     * app cannot read /data/local/tmp), the same file FleetData falls back to when the provider is absent.
     */
    private fun seedCatalogFromRepo() {
        val ins = InstrumentationRegistry.getInstrumentation()
        val ctx = ins.targetContext
        val lkg = File(ctx.filesDir, "fleetdata-lkg").apply { mkdirs() }
        for (name in listOf("stars.6", "constellations.lines", "mw", "planets")) {
            val pfd = ins.uiAutomation.executeShellCommand("cat /data/local/tmp/fleetdata-celestial/$name.json")
            val bytes = android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
            if (bytes.isNotEmpty()) File(lkg, "app__${ctx.packageName}__celestial__$name.json").writeBytes(bytes)
        }
    }

    @Test fun constellations_loads_data_and_survives_interaction() {
        seedCatalogFromRepo()
        ActivityScenario.launch(ConstellationsActivity::class.java).use { scenario ->
            var sky: SkyState? = null
            val deadline = System.currentTimeMillis() + 30_000
            var starsLoaded = false
            while (System.currentTimeMillis() < deadline) {
                scenario.onActivity { act ->
                    sky = act.findSkyStateForTest()
                    starsLoaded = (sky?.stars?.isNotEmpty() == true)
                }
                android.util.Log.i("ConstellationsTest", "probe starsLoaded=$starsLoaded")
                if (starsLoaded) break
                Thread.sleep(500)
            }
            assertTrue("celestial data must finish loading (stars list still empty after 30s)", starsLoaded)
            assertTrue("expected several thousand stars, got ${sky?.stars?.size}", (sky?.stars?.size ?: 0) > 1000)
            assertTrue("expected all 88 IAU constellations worth of line segments, got ${sky?.constellationLines?.size}",
                (sky?.constellationLines?.size ?: 0) > 50)
            assertTrue("expected Sun + planets, got ${sky?.bodies?.size}", (sky?.bodies?.size ?: 0) >= 5)

            // Simulate the no-sensor drag/pinch path (same code a real device with
            // TYPE_ROTATION_VECTOR would drive via onSensorChanged) -- must not crash.
            scenario.onActivity { act ->
                val v = act.findViewById<android.view.View>(android.R.id.content)
                val now = System.currentTimeMillis()
                val down = android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_DOWN, 100f, 100f, 0)
                val move = android.view.MotionEvent.obtain(now, now + 16, android.view.MotionEvent.ACTION_MOVE, 250f, 180f, 0)
                val up = android.view.MotionEvent.obtain(now, now + 32, android.view.MotionEvent.ACTION_UP, 250f, 180f, 0)
                v.dispatchTouchEvent(down)
                v.dispatchTouchEvent(move)
                v.dispatchTouchEvent(up)
                down.recycle(); move.recycle(); up.recycle()
            }

            var finishing = false
            scenario.onActivity { act -> finishing = act.isFinishing || act.isDestroyed }
            assertFalse("activity must still be alive after data load + drag interaction (it crashed/finished)", finishing)
        }
    }
}

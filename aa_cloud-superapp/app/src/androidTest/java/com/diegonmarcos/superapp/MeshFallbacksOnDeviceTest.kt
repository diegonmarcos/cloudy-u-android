package com.diegonmarcos.superapp

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.diegonmarcos.superapp.network.MeshTransport
import com.diegonmarcos.superapp.network.mesh.AndroidMeshPort
import com.diegonmarcos.superapp.network.mesh.MeshDecl
import com.diegonmarcos.superapp.network.mesh.MeshHost
import com.diegonmarcos.superapp.network.mesh.MeshReport
import com.diegonmarcos.superapp.network.mesh.MeshStatusPage
import com.diegonmarcos.superapp.network.mesh.MeshStore
import com.diegonmarcos.superapp.network.mesh.lightLines
import com.diegonmarcos.superapp.ui.LauncherPalette
import com.diegonmarcos.superapp.uikit.kitComposeView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * #1798 The fallback tests and the reorganised Cloud Mesh > Status on a REAL emulator, through the real port (AndroidMeshPort:
 * the engine, the prefs, Cloud Account's provider, the emulator's network). Nothing is faked: Test fallbacks and the Cloud C3
 * mesh probe run their real code here, and the page is the production MeshStatusPage over that store. Evidence is written
 * to the app's external files (adb pull): mesh-fallbacks.txt (every step with its verdict and detail, the per-mesh table, the
 * lights) and mesh-status-emulator.png (the page). The CI emulator has neither the WireGuard engine nor Cloud Account nor
 * the mesh, so steps that need them are expected to say so (n/a or FAIL with the reason); what this proves is that every
 * step runs on Android, reports, and reaches the lights and the table, and that the relay key is looked up from the fleet.
 */
@RunWith(AndroidJUnit4::class)
class MeshFallbacksOnDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = inst.targetContext
    private val out by lazy { File(ctx.getExternalFilesDir(null), "meshfb").apply { mkdirs() } }

    private class Host : MeshHost {
        override fun requestConsent(intent: Intent, then: () -> Unit) {}
        override fun copy(label: String, text: String) {}
        override fun paste() = ""
        override fun openVpnSettings() {}
        override fun openAccount() {}
        override fun importConf() {}
        override fun exportConf() {}
        override fun exportProfiles() {}
        override fun openCustomTab(url: String) {}
    }

    private fun resumed(): Activity? {
        var a: Activity? = null
        inst.runOnMainSync {
            val m = ActivityLifecycleMonitorRegistry.getInstance()
            a = listOf(Stage.RESUMED, Stage.PAUSED, Stage.STARTED).firstNotNullOfOrNull { m.getActivitiesInStage(it).firstOrNull() }
        }
        return a
    }

    @Test fun fallbackTestsAndStatusRunOnTheDevice() {
        // the real store over the real port; actions run on this (test) thread, as the page's executor would run them
        val store = MeshStore(AndroidMeshPort(ctx), MeshDecl.baked, Host(), exec = { it.run() })
        store.tab = "status"
        store.poll()
        store.run("transport.test")
        store.run("mesh.probe")
        store.run("reach.run")
        store.poll()

        val ids = store.probes.map { it.id }
        val report = StringBuilder()
        report.appendLine("#1798 Test fallbacks on the emulator (${android.os.Build.MODEL}, API ${android.os.Build.VERSION.SDK_INT})")
        for (p in store.probes) report.appendLine("  ${when (p.ok) { true -> "PASS"; false -> "FAIL"; null -> "n/a " }} ${p.id} · ${p.detail}")
        val key = MeshTransport.relayKey(ctx)
        report.appendLine("relay key: ${if (key.value.isBlank()) "none" else "present (${key.value.length} chars)"} · source ${key.source}")
        val lights = lightLines(store.snapshot!!, store.meshProfile, store.reach, store.probes, store.probesAtMs, store.transport)
        report.appendLine("== lights")
        for (l in lights) report.appendLine("  ${l.id} ${l.ok} ${l.word} · ${l.detail}")
        report.appendLine("== status dump")
        report.appendLine(MeshReport.redact(store.reportText()))
        File(out, "mesh-fallbacks.txt").writeText(report.toString())
        Log.i("MeshFallbacksOnDevice", report.toString())

        // every Test fallbacks step ran and reported (the five ladder lines and the #1797 per-step lines)
        for (need in listOf("udp", "dns", "pinned", "doh", "relay", "relay:v4", "relay:v6", "relay_key")) assertTrue("step $need ran: $ids", need in ids)
        assertTrue("every step carries its detail", store.probes.all { it.detail.isNotBlank() })
        // the relay key was looked up from the fleet: with no paste, the source names Cloud Account
        if (key.value.isBlank()) assertTrue(key.source, "Cloud Account" in key.source)
        // the lights carry one per step and one per path, the table one row per declared mesh with the path column
        for (p in store.probes) assertTrue("light fb:${p.id}", lights.any { it.id == "fb:${p.id}" && it.ok == p.ok })
        for (p in listOf("direct", "pinned", "doh", "relay")) assertTrue("light path:$p", lights.any { it.id == "path:$p" })
        assertEquals(MeshDecl.baked.meshes.size, store.meshProfile!!.rows.size)
        assertTrue(store.meshProfile!!.rows.all { "path" in it.cells })

        // the page itself: the production MeshStatusPage over that store, drawn in the launcher's theme
        ctx.startActivity(Intent().setClassName(ctx.packageName, "${ctx.packageName}.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        val until = SystemClock.uptimeMillis() + 20_000
        while (resumed() == null && SystemClock.uptimeMillis() < until) SystemClock.sleep(250)
        val a = resumed() ?: error("MainActivity did not resume")
        inst.runOnMainSync { a.setContentView(a.kitComposeView(LauncherPalette.kit(a)) { MeshStatusPage(store) }) }
        inst.waitForIdleSync(); SystemClock.sleep(1_500); inst.waitForIdleSync()
        var bmp: Bitmap? = null
        inst.runOnMainSync {
            val v = a.window.decorView
            bmp = Bitmap.createBitmap(v.width.coerceAtLeast(1), v.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888).also { v.draw(Canvas(it)) }
        }
        val shot = bmp!!
        File(out, "mesh-status-emulator.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val seen = HashSet<Int>()
        for (x in 0 until shot.width step 9) for (y in 0 until shot.height step 9) seen += shot.getPixel(x, y)
        assertTrue("the page is drawn (${seen.size} colours)", seen.size > 8)
    }
}

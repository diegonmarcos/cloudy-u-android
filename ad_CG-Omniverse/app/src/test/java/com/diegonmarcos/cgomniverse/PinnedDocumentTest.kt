package com.diegonmarcos.cgomniverse

import com.diegonmarcos.cgomniverse.ui.Pinned
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #1662 a pinned world must load like a browser tab: its document is network-first (a stale pinned document named a replaced
 * bundle and the scene never started), a miss falls through to the network, the remembered CPU presentation is reset once,
 * and Page diagnostics lists every failed request with whether the pinned copy served it.
 */
class PinnedDocumentTest {
    private fun module(path: String): File = File(File("").absoluteFile.let { if (it.name == "app") it.parentFile else it }, path)
    private fun src(name: String) = module("app/src/main/java/com/diegonmarcos/cgomniverse/$name").readText()

    @Test fun documentsAreNetworkFirst() {
        assertTrue(Pinned.networkFirst(true, null))
        assertTrue(Pinned.networkFirst(false, "text/html,application/xhtml+xml,*/*;q=0.8"))
        assertFalse(Pinned.networkFirst(false, "*/*"))
        assertFalse(Pinned.networkFirst(false, null))
    }

    @Test fun servedUrlsAreRecorded() {
        Pinned.served("https://floor796.com/front.x.js")
        assertTrue(Pinned.wasServed("https://floor796.com/front.x.js"))
        assertFalse(Pinned.wasServed("https://floor796.com/other.js"))
    }

    @Test fun aMissFallsThroughToTheNetwork() {
        val p = src("ui/Pinned.kt")
        assertTrue(p.contains("getOrNull() ?: return null"))
        assertTrue(p.contains("if (r.data == null) return null"))
        assertTrue(src("ui/GalaxyPage.kt").contains("?: super.shouldInterceptRequest(view, request)"))
    }

    @Test fun cpuPresentationIsResetOnce() {
        val w = src("ui/Web.kt")
        assertTrue(w.contains("CPU_RESET_KEY = \"cpu.reset.1662\""))
        assertTrue(w.contains("resetCpuOnce(ctx); return prefs(ctx).getBoolean(\"cpu.\$worldId\", false)"))
    }

    @Test fun diagnosticsListFailedRequestsAndKeepControls() {
        val g = src("ui/GalaxyPage.kt")
        assertTrue(g.contains("Failed requests ("))
        assertTrue(g.contains("[pinned copy]"))
        listOf("Tags.WORLD_DIAG_SHEET", "Tags.WORLD_RENDER_GPU", "Console errors", "Pinned.intercept", "RenderProbe.presentation(cpu)").forEach { assertTrue(it, g.contains(it)) }
    }
}

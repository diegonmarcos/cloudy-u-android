package com.diegonmarcos.cgomniverse

import com.diegonmarcos.cloudlib.web.GpuCaps
import com.diegonmarcos.cloudlib.web.SiteGpuChoice
import com.diegonmarcos.cloudlib.web.WebGpuPlan
import com.diegonmarcos.cloudlib.web.WorldRoute
import com.diegonmarcos.cloudlib.web.WorldRouting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Capability detection, the verdict text and the routing decision for WebGPU worlds (pure, no Android). */
class WebGpuRoutingTest {
    private fun caps(webgpu: Boolean, adapter: Boolean, compat: Boolean = false) =
        GpuCaps(true, webgpu, adapter, emptyList(), "156.0", "Adreno", compatAdapter = compat, adapterInfo = if (adapter) "qualcomm/adreno" else null)

    @Test fun capabilityDetection() {
        assertTrue(caps(true, true).webgpuUsable)
        assertFalse(caps(true, false).webgpuUsable)
        assertFalse(caps(false, false).webgpuUsable)
        assertTrue(caps(true, false, compat = true).webgpuAny)
    }

    @Test fun autoRoutesOnlyAMeasuredMissingAdapterToCloudBrowser() {
        assertEquals(WorldRoute.CLOUD_BROWSER, WorldRouting.route(SiteGpuChoice.AUTO, true, caps(true, false)))
        assertEquals(WorldRoute.IN_APP, WorldRouting.route(SiteGpuChoice.AUTO, true, caps(true, true)))
        assertEquals(WorldRoute.IN_APP, WorldRouting.route(SiteGpuChoice.AUTO, false, caps(true, false)))
        assertEquals(WorldRoute.IN_APP, WorldRouting.route(SiteGpuChoice.AUTO, true, null))
    }

    @Test fun anExplicitChoiceWins() {
        assertEquals(WorldRoute.IN_APP, WorldRouting.route(SiteGpuChoice.IN_APP, true, caps(true, false)))
        assertEquals(WorldRoute.CLOUD_BROWSER, WorldRouting.route(SiteGpuChoice.CLOUD_BROWSER, false, caps(true, true)))
    }

    @Test fun choiceParsesAndDefaultsToAuto() {
        assertEquals(SiteGpuChoice.IN_APP, SiteGpuChoice.parse("IN_APP"))
        assertEquals(SiteGpuChoice.AUTO, SiteGpuChoice.parse("x"))
        assertEquals(SiteGpuChoice.AUTO, SiteGpuChoice.parse(null))
    }

    @Test fun flagFileIsHonouredOnlyOnADebuggableOs() {
        assertFalse(WebGpuPlan.flagFileHonoured(false))
        assertTrue(WebGpuPlan.flagFileHonoured(true))
    }

    @Test fun commandsCarryTheDeclaredFlags() {
        assertTrue(WebGpuPlan.fileContent().startsWith("_ --enable-unsafe-webgpu"))
        assertTrue(WebGpuPlan.applyCommand().contains(WebGpuPlan.COMMAND_LINE_FILE))
        assertTrue(WebGpuPlan.revertCommand().startsWith("rm -f "))
    }

    @Test fun verdictNamesTheExactReason() {
        assertEquals(WebGpuPlan.Status.READY, WebGpuPlan.diagnose(caps(true, true), false, false).status)
        assertEquals(WebGpuPlan.Status.NO_API, WebGpuPlan.diagnose(caps(false, false), false, false).status)
        assertEquals(WebGpuPlan.Status.COMPAT_ONLY, WebGpuPlan.diagnose(caps(true, false, true), true, true).status)
        val prod = WebGpuPlan.diagnose(caps(true, false), false, true)
        assertEquals(WebGpuPlan.Status.BLOCKED_OR_FLAG_IGNORED, prod.status)
        assertTrue(prod.text.contains("ignores the WebView flags file"))
        assertEquals(WebGpuPlan.Status.FLAG_APPLIED_NO_ADAPTER, WebGpuPlan.diagnose(caps(true, false), true, true).status)
    }

    @Test fun productionFlagsAreTheSubsetTheDevToolsUiOffers() {
        assertTrue(WebGpuPlan.FLAGS.containsAll(WebGpuPlan.PRODUCTION_FLAGS))
        assertEquals(listOf("--ignore-gpu-blocklist"), WebGpuPlan.PRODUCTION_FLAGS)
        assertTrue(WebGpuPlan.providerLine("com.google.android.webview.canary", "160").contains("160"))
        assertEquals(3, WebGpuPlan.OWNER_STEPS.size)
    }
}

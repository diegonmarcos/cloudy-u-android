package com.diegonmarcos.cloudbrowser.offline

import com.diegonmarcos.cloudlib.web.GpuCaps
import com.diegonmarcos.cloudlib.web.GpuPageModel
import com.diegonmarcos.cloudlib.web.GpuProbe
import com.diegonmarcos.cloudlib.web.WebGpuPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1518 the Configs > GPU page's model: what each row says, the exact reason for a null adapter, the owner steps. */
class GpuPageModelTest {
    private fun caps(webgpu: Boolean = true, adapter: Boolean = false, compat: Boolean = false, note: String? = null) = GpuCaps(
        webgl2 = true, webgpu = webgpu, adapter = adapter, features = if (adapter) listOf("float32-filterable", "depth-clip-control") else emptyList(),
        webViewVersion = "146.0.1", renderer = "Mali-G715", compatAdapter = compat, adapterInfo = if (adapter) "arm/valhall" else null,
        vendor = "ARM", glInfo = mapOf("MAX_TEXTURE_SIZE" to "8192", "VERSION" to "WebGL 2.0"), glExtensions = listOf("OES_texture_float", "EXT_color_buffer_float"),
        adapterNote = note,
    )

    private fun row(rows: List<com.diegonmarcos.cloudlib.web.GpuRow>, label: String) = rows.first { it.label == label }.value

    @Test fun `the page shows provider, version, renderer and vendor`() {
        val rows = GpuPageModel.rows("com.google.android.webview", "146.0.1", caps())
        assertEquals("com.google.android.webview 146.0.1", row(rows, "WebView provider"))
        assertEquals("Mali-G715", row(rows, "WebGL renderer"))
        assertEquals("ARM", row(rows, "WebGL vendor"))
        assertEquals("on", row(rows, "WebGL2"))
    }

    @Test fun `an unknown provider and an unfinished probe are said in words`() {
        assertEquals("unknown", GpuPageModel.providerRow(null, null).value)
        val rows = GpuPageModel.rows(null, null, null)
        assertEquals(2, rows.size)
        assertTrue(row(rows, "Probe").contains("measuring"))
        assertTrue(GpuPageModel.features(null).isEmpty())
    }

    @Test fun `a null adapter says exactly why`() {
        val blocked = row(GpuPageModel.rows("p", "1", caps(note = "requestAdapter() returned null")), "WebGPU adapter")
        assertTrue(blocked, blocked.startsWith("null: requestAdapter() returned null"))
        assertTrue("the GPU is named", blocked.contains("Mali-G715") && blocked.contains("allowlist"))
        val threw = row(GpuPageModel.rows("p", "1", caps(note = "requestAdapter() threw: NotAllowedError")), "WebGPU adapter")
        assertTrue(threw.contains("NotAllowedError"))
        val none = row(GpuPageModel.rows("p", "1", caps(webgpu = false)), "WebGPU adapter")
        assertTrue(none, none.contains("navigator.gpu is undefined"))
        val compat = row(GpuPageModel.rows("p", "1", caps(compat = true)), "WebGPU adapter")
        assertTrue(compat.contains("compatibility"))
    }

    @Test fun `an adapter is shown with its info and feature count`() {
        val v = row(GpuPageModel.rows("p", "1", caps(adapter = true)), "WebGPU adapter")
        assertEquals("available: arm/valhall (2 features)", v)
    }

    @Test fun `the feature list reads like chrome gpu`() {
        val f = GpuPageModel.features(caps(adapter = true))
        assertEquals("8192", f.first { it.label == "MAX_TEXTURE_SIZE" }.value)
        assertEquals("2: EXT_color_buffer_float, OES_texture_float", f.first { it.label == "WebGL extensions" }.value)
        assertEquals("depth-clip-control, float32-filterable", f.first { it.label == "WebGPU features" }.value)
        assertFalse(f.any { it.label == "WebGPU compatibility mode" })
    }

    @Test fun `the owner steps and the DevTools intent are the declared ones`() {
        assertEquals(WebGpuPlan.OWNER_STEPS, GpuPageModel.steps)
        assertTrue(GpuPageModel.steps.any { it.contains("ignore-gpu-blocklist") })
        assertEquals("com.android.webview.SHOW_DEV_UI", GpuPageModel.DEV_UI_ACTION)
        assertEquals("am start -a com.android.webview.SHOW_DEV_UI", GpuPageModel.DEV_UI_ADB)
    }

    @Test fun `the probe result is parsed with vendor, limits, extensions and the adapter note`() {
        val json = """{"webgl2":true,"webgpu":true,"adapter":false,"compat":false,"features":[],"renderer":"Mali-G715","info":null,
            "vendor":"ARM","gl":{"MAX_TEXTURE_SIZE":"8192"},"ext":["OES_texture_float"],"note":"requestAdapter() returned null"}"""
        val c = GpuProbe.parse(json, "146.0.1")
        assertEquals("ARM", c.vendor); assertEquals("8192", c.glInfo["MAX_TEXTURE_SIZE"]); assertEquals(listOf("OES_texture_float"), c.glExtensions)
        assertEquals("requestAdapter() returned null", c.adapterNote)
        assertFalse(c.webgpuUsable)
    }
}

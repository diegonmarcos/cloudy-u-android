package com.diegonmarcos.hardtechcad

import com.diegonmarcos.hardtechcad.ViewerHealth.Present
import com.diegonmarcos.superapp.cad.CadFormats
import com.diegonmarcos.superapp.cad.ModelManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #1658 the MCAD viewer renders, and says why when it does not: the on-screen verdict and the presentation fallback (ViewerHealth), the WebView's
 * GPU settings, a viewer surface that cannot collapse, the diagnostics wiring, and every declared model (the Drone included) having a bundled view
 * the viewer shows. A device render cannot run in CI; these hold the rules the render depends on, from the real sources and the real assets.
 */
class ViewerRenderTest {
    private val app = listOf(File("."), File("app")).first { File(it, "src/main/assets/cad/models.json").isFile }
    private val repo = generateSequence(app.absoluteFile) { it.parentFile }.first { File(it, "ab_cloud-libs-shared").isDirectory }
    private val libs = File(repo, "ab_cloud-libs-shared/libs")
    private fun src(path: String) = File(app, path).readText()

    private val bg = ViewerHealth.BACKGROUND
    private fun pixels(total: Int, drawn: Int, colour: Int = 0xFFC8CCD4.toInt()) = IntArray(total) { if (it < drawn) colour else bg }

    @Test fun theScreenShareCountsOnlyWhatIsNotBackground() {
        assertEquals(0.0, ViewerHealth.coverage(pixels(1000, 0)), 0.0)
        assertEquals(12.5, ViewerHealth.coverage(pixels(1000, 125)), 0.0)
        assertEquals(100.0, ViewerHealth.coverage(pixels(10, 10)), 0.0)
        // a pixel a few levels off the background (dither, the scaled copy) is still background
        assertEquals(0.0, ViewerHealth.coverage(pixels(1000, 500, 0xFF12151B.toInt())), 0.0)
        assertEquals(-1.0, ViewerHealth.coverage(IntArray(0)), 0.0)
    }

    @Test fun aModelInTheFramebufferAndNotOnTheScreenStepsThroughEveryPresentation() {
        // painted and on the screen: nothing changes
        assertNull(ViewerHealth.next(Present.WEBGL, 11.8, 10.9))
        // painted, the screen is empty: WebGL canvas -> 2D canvas -> an image the app draws -> nothing left to try
        assertEquals(Present.CANVAS2D, ViewerHealth.next(Present.WEBGL, 11.8, 0.0))
        assertEquals(Present.HOST, ViewerHealth.next(Present.CANVAS2D, 11.8, 0.2))
        assertNull(ViewerHealth.next(Present.HOST, 11.8, 0.0))
        assertTrue(ViewerHealth.lost(11.8, 0.0))
        // a screen that could not be measured is never taken for an empty one
        assertNull(ViewerHealth.next(Present.WEBGL, 11.8, -1.0))
        assertFalse(ViewerHealth.lost(11.8, -1.0))
        // a canvas the page itself found empty is the page's error, not a presentation failure
        assertNull(ViewerHealth.next(Present.WEBGL, 0.0, 0.0))
        assertEquals(listOf("webgl", "2d", "host"), Present.entries.map { it.js })
    }

    @Test fun aCollapsedSurfaceIsNotASizedOne() {
        assertFalse(ViewerHealth.sized(0, 300)); assertFalse(ViewerHealth.sized(1080, 0)); assertFalse(ViewerHealth.sized(1, 1))
        assertTrue(ViewerHealth.sized(1080, 900))
    }

    @Test fun theLineUnderTheViewerSaysWhatHappened() {
        val ok = ViewerHealth.line("shown", "shown", Present.WEBGL, 254076, 392, 300, 10.8, 10.5, "Mali-G715")
        for (needle in listOf("254076 triangles", "canvas 392x300", "painted 10.8%", "on screen 10.5%", "the WebGL canvas", "Mali-G715")) assertTrue("$needle in $ok", needle in ok)
        assertFalse("NOT VISIBLE" in ok)
        val lost = ViewerHealth.line("shown", "shown", Present.HOST, 1096, 392, 300, 18.2, 0.0, "")
        assertTrue(lost, "on screen 0.0% (NOT VISIBLE)" in lost && "an image drawn by the app" in lost)
        assertTrue("on screen not measured" in ViewerHealth.line("shown", "shown", Present.CANVAS2D, 1096, 392, 300, 18.2, -1.0, ""))
        assertEquals("Loading: importing the mesh loader", ViewerHealth.line("loading", "importing the mesh loader", Present.WEBGL, -1, 0, 0, -1.0, -1.0, ""))
        assertEquals("Loading…", ViewerHealth.line("loading", "", Present.WEBGL, -1, 0, 0, -1.0, -1.0, ""))
        assertEquals("Not rendered: WebGL context lost (viewer stage: framing the model)", ViewerHealth.line("failed", "framing the model", Present.WEBGL, -1, 0, 0, -1.0, -1.0, "", "WebGL context lost"))
    }

    @Test fun thePaintedButNotOnScreenModelIsTheFirstFailingStageOfTheReport() {
        val rec = LiveRec("drone", "Drone", "mesh", "glb", "u", "shown", emptyList(), shownMs = 5, triangles = 1096, paintedPct = 18.2, screenPct = 0.0, present = "host")
        val s = XraySnapshot("t", emptyList(), emptyList(), GlProbe(false, false, "", "", emptyList()), emptyList(), "", 0, emptyList(), listOf(rec))
        val st = XrayReport.stages(s).first { it.name == "6 drone load and draw" }
        assertEquals("FAIL", st.status)
        assertTrue(st.detail, "18.2%" in st.detail && "through host" in st.detail)
        val ok = XrayReport.stages(s.copy(live = listOf(rec.copy(screenPct = 17.0, present = "2d")))).first { it.name == "6 drone load and draw" }
        assertEquals("OK", ok.status)
        assertTrue(ok.detail, "17.0% on the screen through 2d" in ok.detail)
        assertTrue("\"screenPct\"" in XrayReport.json(s) && "\"present\"" in XrayReport.json(s))
        assertTrue("on screen 0.0% through host" in XrayReport.markdown(s))
        CadDiag.clear(); CadDiag.begin("drone", "Drone", "mesh", "glb", "u"); CadDiag.screen("drone", 9.5, "2d")
        val r = CadDiag.snapshot().single()
        assertEquals(9.5, r.screenPct, 0.0); assertEquals("2d", r.present)
        assertTrue(r.events.last(), r.events.last().endsWith("on screen 9.5% through 2d"))
        CadDiag.clear()
    }

    @Test fun everyDeclaredModelHasABundledViewTheViewerShows() {
        val models = ModelManifest.parse(File(app, "src/main/assets/cad/models.json").readText())
        assertTrue(models.map { it.id }.containsAll(listOf("one-lilo", "campidoglio", "drone")))
        for (m in models) {
            val plan = CadFormats.plan(m)
            assertTrue("${m.id} is not shown: $plan", plan is CadFormats.Plan.Show)
            plan as CadFormats.Plan.Show
            assertTrue("${m.id} is drawn from its derived view", plan.derived)
            assertEquals("${m.id} viewer lib", CadFormats.LIB_MESH, plan.lib)
            assertEquals("${m.id} view format", "glb", plan.format)
            assertTrue("${m.id} view is bundled in the APK", plan.asset.isNotBlank())
            val r = File(app, "src/main/assets/" + plan.asset).inputStream().use { GlbInspector.inspect(it) }
            assertTrue("${m.id}: ${r.error}", r.ok && r.triangles > 0)
        }
        // the Drone: a procedural (.py) source, shown through the GLB derived from it, exactly as the IGES boat is
        val drone = models.first { it.id == "drone" }
        assertTrue(drone.path.endsWith(".py"))
        assertEquals("cad/models/drone.view.glb", (CadFormats.plan(drone) as CadFormats.Plan.Show).asset)
    }

    @Test fun theWebViewIsAGpuOneAndItsSurfaceCannotCollapse() {
        val viewer = src("src/main/java/com/diegonmarcos/hardtechcad/ui/ModelViewer.kt")
        // the fleet's GPU profile, never a hand-configured view
        assertTrue("CloudWeb.create(c, WebProfile.WEB_WORLD)" in viewer)
        assertFalse("LAYER_TYPE_SOFTWARE" in viewer)
        val profile = File(libs, "web-core/src/main/java/com/diegonmarcos/cloudlib/web/WebProfile.kt").readText()
        assertTrue("WEB_WORLD runs JavaScript", Regex("""\n\s+WEB_WORLD\(true, true,""").containsMatchIn(profile))
        val web = File(libs, "web-core/src/main/java/com/diegonmarcos/cloudlib/web/CloudWeb.kt").readText()
        assertTrue("wv.setLayerType(View.LAYER_TYPE_NONE, null)" in web)
        assertFalse("LAYER_TYPE_SOFTWARE" in web)
        assertTrue("javaScriptEnabled = profile.javaScript" in web)
        // hardware acceleration is the platform default: nothing in the manifest or the theme turns it off
        assertFalse(Regex("""hardwareAccelerated\s*=\s*"false"""").containsMatchIn(src("src/main/AndroidManifest.xml")))
        // a fixed, non-zero height with the WebView filling it, and no clip around it (#1560)
        assertTrue("private const val VIEWER_HEIGHT_DP = 300" in viewer)
        val box = viewer.lines().single { ".testTag(\"viewer\")" in it }
        // #1664 the viewer is now as tall as the page gives it (most of the screen, or full screen), never lower than VIEWER_HEIGHT_DP
        assertTrue(box, "Modifier.fillMaxWidth().height(height.coerceAtLeast(VIEWER_HEIGHT_DP.dp))" in box && ".clip(" !in box)
        assertTrue(Regex("""wv\.loadUrl\(VIEWER_URL\)\s+web = wv\s+}\s+},\s+modifier = Modifier\.fillMaxSize\(\),""").containsMatchIn(viewer))
        // the page: the stage and its canvases fill the window; a canvas that has no size yet is waited for, then reported
        val dir = File(libs, "cad-core/src/main/assets/cad")
        val html = File(dir, "viewer.html").readText()
        val js = File(dir, "viewer.js").readText()
        assertTrue("html,body{margin:0;height:100%" in html && "canvas{display:block;width:100%;height:100%}" in html && "#stage{position:absolute;left:0;top:0;width:100%;height:100%}" in html)
        assertTrue("<div id=\"stage\"></div>" in html)
        for (needle in listOf("await waitSized()", "the viewer has no size", "renderer.domElement.width >= 16 && renderer.domElement.height >= 16", "new ResizeObserver(resize).observe(document.body)"))
            assertTrue("viewer.js lacks $needle", needle in js)
    }

    @Test fun aBlankRenderReportsWhy() {
        val viewer = src("src/main/java/com/diegonmarcos/hardtechcad/ui/ModelViewer.kt")
        val js = File(libs, "cad-core/src/main/assets/cad/viewer.js").readText()
        // the page's console reaches logcat and the diagnostics ring through the fleet chrome client
        val chrome = File(libs, "web-core/src/main/java/com/diegonmarcos/cloudlib/web/CloudChromeClient.java").readText()
        assertTrue("onConsoleMessage(ConsoleMessage m)" in chrome && "PageConsole.INSTANCE.record(" in chrome)
        assertTrue("wv.webChromeClient = CloudChromeClient(" in File(libs, "web-core/src/main/java/com/diegonmarcos/cloudlib/web/CloudWeb.kt").readText())
        // the screen is measured (PixelCopy of the window), recorded, and drives the presentation the page is asked for
        for (needle in listOf("PixelCopy.request(window, src, bmp", "ViewerHealth.coverage(", "CadDiag.screen(m.id, s, p.js)", "ViewerHealth.next(p, pt.pct, s)",
            "window.cadPresent&&window.cadPresent('\${n.js}')", "window.cadFrameShot&&window.cadFrameShot(\$last)", "testTag(\"viewer_host_frame\")"))
            assertTrue("ModelViewer.kt lacks $needle", needle in viewer)
        for (needle in listOf("window.cadPresent = (mode) =>", "window.cadFrameShot = (since) =>", "willReadFrequently: true", "ctx2.drawImage(c, 0, 0)", "renderer.render(scene, camera); blit(); D.frames++"))
            assertTrue("viewer.js lacks $needle", needle in js)
        // a visible state for every outcome: loading, failed (with the reason), never loading for ever, and the line under the viewer
        val strings = src("src/main/res/values/strings.xml")
        assertTrue("<string name=\"viewer_loading\">" in strings && "<string name=\"viewer_failed\">" in strings && "<string name=\"viewer_fetching\">" in strings)
        for (needle in listOf("ViewState.Loading -> stringResource(R.string.viewer_loading, m.name)", "is ViewState.Failed -> stringResource(R.string.viewer_failed, s.why)",
            "still loading after \$LOAD_TIMEOUT_S s", "the viewer page did not become ready in 8 s", "testTag(\"viewer_render\")", "ViewerHealth.line(", "val onEvent by rememberUpdatedState"))
            assertTrue("ModelViewer.kt lacks $needle", needle in viewer)
        // rule 16: the controls and the texts the viewer had are all still there
        for (needle in listOf("R.string.viewer_fit", "R.string.viewer_wire", "R.string.viewer_gestures", "R.string.viewer_model", "R.string.viewer_derived", "window.cadFit&&window.cadFit()", "window.cadWireframe&&window.cadWireframe(\$on)",
            "testTag(\"viewer_fit\")", "testTag(\"viewer_wire\")", "testTag(\"viewer_status\")"))
            assertTrue("ModelViewer.kt lost $needle", needle in viewer)
        val library = src("src/main/java/com/diegonmarcos/hardtechcad/ui/LibraryPage.kt")
        for (needle in listOf("R.string.lib_core_title", "CoreTable(libs, lib)", "R.string.lib_formats_title", "CadLibraries.modelsOf(lib, f, it)")) assertTrue("LibraryPage.kt lost $needle", needle in library)
        val models = src("src/main/java/com/diegonmarcos/hardtechcad/ui/ModelsPage.kt")
        for (needle in listOf("R.string.models_filter_model", "R.string.models_filter_extension", "R.string.models_filter_software", "testTag(\"models_table\")")) assertTrue("ModelsPage.kt lost $needle", needle in models)
    }
}

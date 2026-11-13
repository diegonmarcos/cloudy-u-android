package com.diegonmarcos.cgomniverse

import com.diegonmarcos.cloudlib.web.WebProfile
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #1657 a Galaxy world that stays grey says why. The render check is pure ([RenderProbe]): what the page drew, what the screen
 * shows, and the verdict naming the failed stage. The host side is held from the sources: the WEB_WORLD settings a scene needs,
 * the content area's real size, the console capture, the visible load / no-render state, and every control of the tab bar.
 * The real render of a world needs a phone: CI has neither the device nor the site.
 */
class RenderProbeTest {
    private fun module(path: String): File = File(File("").absoluteFile.let { if (it.name == "app") it.parentFile else it }, path)
    private fun source(path: String): String = module(path).readText()

    private fun page(
        vw: Int = 412, vh: Int = 760, vis: String = "visible", raf: Int = 60, canvases: Int = 0, drawn: Int = 0, blank: Int = 0,
        videos: Int = 0, vready: Int = 0, vframes: Int = 0, verr: Int = 0,
    ): RenderProbe.Page = RenderProbe.parse(
        JSONObject.quote(
            JSONObject().put("vw", vw).put("vh", vh).put("vis", vis).put("raf", raf).put("timer", true).put("io", true)
                .put("canvases", canvases).put("drawn", drawn).put("blank", blank).put("unreadable", 0).put("cw", vw).put("ch", vh)
                .put("videos", videos).put("vready", vready).put("vframes", vframes).put("verr", verr).put("imgs", 3).put("ibroken", 1).toString(),
        ),
    )!!

    private val flat = RenderProbe.screen(IntArray(RenderProbe.SAMPLE * RenderProbe.SAMPLE) { 0xFF333333.toInt() })!!
    private val painted = RenderProbe.screen(IntArray(RenderProbe.SAMPLE * RenderProbe.SAMPLE) { 0xFF000000.toInt() or (it * 7919) })!!
    private val late = RenderProbe.GRACE_MS + 1

    @Test fun thePageAnswerIsParsedAsEvaluateJavascriptReturnsIt() {
        val p = page(canvases = 2, drawn = 1, blank = 1, videos = 6, vready = 4, vframes = 120, verr = 1)
        assertEquals(412, p.viewportW); assertEquals(760, p.viewportH); assertEquals("visible", p.visibility); assertEquals(60, p.frames)
        assertEquals(2, p.canvases); assertEquals(1, p.drawn); assertEquals(1, p.blank)
        assertEquals(6, p.videos); assertEquals(4, p.videosReady); assertEquals(120, p.videoFrames); assertEquals(1, p.videoErrors)
        assertEquals(3, p.images); assertEquals(1, p.imagesBroken)
        assertTrue(p.timer && p.observer && p.hasPixels)
        assertFalse(page(canvases = 1, blank = 1).hasPixels)
        assertEquals(7, p.rows().size)   // #1711 + "Canvas contexts created"; the six before it are unchanged
        assertEquals(listOf("Page viewport", "Animation frames in 1 s", "Timers / IntersectionObserver", "Canvases", "Canvas contexts created", "Videos", "Images"), p.rows().map { it.first })
        assertNull(RenderProbe.parse(null)); assertNull(RenderProbe.parse("null")); assertNull(RenderProbe.parse("\"not json\"")); assertNull(RenderProbe.parse("\"{}\""))
    }

    @Test fun theScreenSampleTellsAFlatColourFromAPicture() {
        assertTrue(flat.flat); assertEquals("#303030", flat.hex()); assertEquals(1, flat.colours)
        assertFalse(painted.flat); assertTrue(painted.colours > 100)
        // dithering and a few stray pixels are still a flat screen; a tenth of the area drawn is not
        val n = RenderProbe.SAMPLE * RenderProbe.SAMPLE
        assertTrue(RenderProbe.screen(IntArray(n) { if (it < n / 200) 0xFFFFFFFF.toInt() else 0xFF333333.toInt() + (it % 2) })!!.flat)
        assertFalse(RenderProbe.screen(IntArray(n) { if (it < n / 10) 0xFFFFFFFF.toInt() else 0xFF333333.toInt() })!!.flat)
        assertNull(RenderProbe.screen(IntArray(0)))
    }

    @Test fun aContentAreaWithoutSizeIsTheFirstReason() {
        for ((w, h) in listOf(1080 to 0, 0 to 1800, 0 to 0)) {
            val v = RenderProbe.verdict(w, h, page(canvases = 1, drawn = 1), painted, late)
            assertEquals(RenderProbe.State.NOT_RENDERED, v.state)
            assertTrue(v.reason, v.reason.contains("no size ($w x $h px)"))
        }
        assertEquals(RenderProbe.State.RENDERED, RenderProbe.verdict(1080, 1800, page(canvases = 1, drawn = 1), painted, late).state)
    }

    @Test fun pixelsOnTheScreenAreARenderWhateverThePageSays() {
        assertEquals(RenderProbe.State.RENDERED, RenderProbe.verdict(1080, 1800, null, painted, -1).state)
        assertEquals(RenderProbe.State.RENDERED, RenderProbe.verdict(1080, 1800, page(raf = 0), painted, late).state)
    }

    @Test fun aFlatScreenIsLoadingOnlyInsideTheGrace() {
        assertEquals(RenderProbe.State.LOADING, RenderProbe.verdict(1080, 1800, null, flat, -1).state)
        assertEquals(RenderProbe.State.LOADING, RenderProbe.verdict(1080, 1800, page(), flat, RenderProbe.GRACE_MS - 1).state)
        assertEquals(RenderProbe.State.NOT_RENDERED, RenderProbe.verdict(1080, 1800, page(), flat, RenderProbe.GRACE_MS).state)
    }

    @Test fun eachFailedStageIsNamed() {
        fun reason(p: RenderProbe.Page?, s: RenderProbe.Screen? = flat): String =
            RenderProbe.verdict(1080, 1800, p, s, late).also { assertEquals(RenderProbe.State.NOT_RENDERED, it.state) }.reason
        assertTrue(reason(null).contains("did not answer"))
        assertTrue(reason(page(vh = 0)).contains("empty viewport (412 x 0 css px)"))
        assertTrue(reason(page(raf = 0, vis = "hidden")).contains("no animation frames (it reports itself hidden)"))
        assertTrue(reason(page(videos = 6, vready = 2, verr = 1)).contains("6 video tiles, none decoded a frame (2 with data, 1 errors)"))
        assertTrue(reason(page(canvases = 1, blank = 1)).contains("1 canvases exist but nothing was drawn"))
        assertTrue(reason(page()).contains("no canvas and no video"))
        // the page holds pixels and the screen does not: the fault is below the page, and the verdict says so
        val shown = reason(page(canvases = 1, drawn = 1, videos = 6, vframes = 300))
        assertTrue(shown, shown.contains("the page drew (1 canvases with pixels, 300 video frames decoded) but the screen shows one flat colour #303030"))
        assertTrue(shown.contains("not showing its canvas / video layers") && shown.contains("ignore-gpu-blocklist"))
    }

    /**
     * #1711 a scene on a canvas the page's own list cannot reach (Messenger: three.js in a closed shadow root) is invisible to the
     * page's canvas list. The GPU canvases attached to the page say a scene exists; whether it shows is the screen's word.
     */
    @Test fun aSceneOutsideTheDocumentIsNotCalledNeverStarted() {
        fun raw(attached: Int, gl: Int = 2, d2: Int = 1) = JSONObject.quote(JSONObject().put("vw", 412).put("vh", 760).put("vis", "visible").put("raf", 60)
            .put("timer", true).put("io", true).put("canvases", 0).put("gl", gl).put("gpu", 0).put("d2", d2).put("glc", attached).toString())
        val p = RenderProbe.parse(raw(attached = 1))!!
        assertEquals(2, p.glContexts); assertEquals(0, p.gpuContexts); assertEquals(1, p.contexts2d); assertEquals(1, p.gpuCanvases)
        assertEquals(0, p.canvases); assertFalse(p.hasPixels); assertTrue(p.hasScene)
        val row = p.rows().toMap()["Canvas contexts created"]!!
        assertTrue(row, row.startsWith("2 WebGL, 0 WebGPU, 1 2D") && row.contains("1 GPU canvases attached to the page"))
        // the screen shows it: rendered, whatever the page's canvas list says
        assertEquals(RenderProbe.State.RENDERED, RenderProbe.verdict(1080, 1800, p, painted, late).state)
        // a flat screen after the grace: the scene runs but does not reach the screen (never "no canvas ... never started")
        val v = RenderProbe.verdict(1080, 1800, p, flat, late)
        assertEquals(RenderProbe.State.NOT_RENDERED, v.state)
        assertTrue(v.reason, v.reason.contains("runs a GPU scene (1 attached canvases outside the page's canvas list)") && v.reason.contains("one flat colour #303030"))
        assertFalse(v.reason.contains("never started"))
        assertTrue(v.text(), v.text().contains("1 GPU canvases attached to the page"))
        // the screen cannot be sampled: the attached GPU canvas is the scene, as a canvas that reads back drawn is; inside the grace it is still loading
        val unsampled = RenderProbe.verdict(1080, 1800, p, null, late)
        assertEquals(RenderProbe.State.RENDERED, unsampled.state); assertTrue(unsampled.reason, unsampled.reason.contains("the screen could not be sampled"))
        assertEquals(RenderProbe.State.LOADING, RenderProbe.verdict(1080, 1800, p, null, 0).state)
        // a context alone is not a scene: a page makes one on a throwaway canvas only to see whether WebGL exists. Measured on the
        // emulator while floor796 waits for its data (the app's own capability check still counted then): 1 WebGL and 4 2D contexts, no canvas attached
        val probeOnly = RenderProbe.parse(raw(attached = 0, gl = 1, d2 = 4))!!
        assertEquals(1, probeOnly.glContexts); assertEquals(0, probeOnly.gpuCanvases); assertFalse(probeOnly.hasScene)
        assertTrue(RenderProbe.verdict(1080, 1800, probeOnly, flat, late).reason.contains("no canvas and no video"))
        assertEquals(RenderProbe.State.NOT_RENDERED, RenderProbe.verdict(1080, 1800, probeOnly, null, late).state)
        assertFalse(RenderProbe.verdict(1080, 1800, probeOnly, flat, late).text().contains("GPU canvases attached"))
        // a page from before the count (no gl / gpu / d2 / glc in its answer) reads as before
        assertEquals(0, page().gpuCanvases); assertFalse(page().hasScene); assertTrue(page(canvases = 1).hasScene); assertTrue(page(videos = 1).hasScene)
        assertTrue(RenderProbe.verdict(1080, 1800, page(), flat, late).reason.contains("no canvas and no video"))
        // the count is taken at document start (each canvas once), the GPU canvases are remembered weakly, the page's own call passes through
        // untouched, READ reports them, and this app's own checks are not counted as the page's
        listOf("window.__cloudCtx", "HTMLCanvasElement.prototype.getContext", "g.apply(this,arguments)", "t==='webgpu'", "/webgl/.test(t)", "return r",
            "new WeakSet()", "!s.has(this)", "!n.off", "window.WeakRef", "new WeakRef(this)", "n.c.length<64").forEach { assertTrue(it, RenderProbe.CONTEXT_JS.contains(it)) }
        listOf("gl:(window.__cloudCtx||{}).gl||0", "gpu:(window.__cloudCtx||{}).gpu||0", "d2:(window.__cloudCtx||{}).d2||0", "glc:0", ".deref()", "E.isConnected",
            "if(N)N.off=1;", "if(N)N.off=0;").forEach { assertTrue(it, RenderProbe.READ.contains(it)) }
        assertEquals("(function(){var n=window.__cloudCtx;if(n)n.off=1;try{X()}finally{if(n)n.off=0}})()", RenderProbe.own("X()"))
        assertEquals(2, Regex("evaluateJavascript\\(RenderProbe\\.own\\(PageDiag\\.SCRIPT\\), null\\)").findAll(source("app/src/main/java/com/diegonmarcos/cgomniverse/ui/GalaxyPage.kt")).count())
        assertFalse(source("app/src/main/java/com/diegonmarcos/cgomniverse/ui/GalaxyPage.kt").contains("evaluateJavascript(PageDiag.SCRIPT"))
        assertTrue(source("app/src/main/java/com/diegonmarcos/cgomniverse/ui/Web.kt").contains("addDocumentStartJavaScript(wv, RenderProbe.CONTEXT_JS"))
        assertTrue(source("app/src/main/java/com/diegonmarcos/cgomniverse/ui/GalaxyPage.kt").contains("Web.contextProbe(wv)"))
    }

    /**
     * #1711 a world the screen already showed in this load is never loaded again, and gets no banner, for a later flat or unreadable
     * moment: a game's loading screen between two scenes (Messenger: white), a renderer busy compiling (Cyber City Orion was thrown
     * back from 21% to 0% on the emulator). A world that was never on the screen keeps the ladder and the banner.
     */
    @Test fun aWorldAlreadySeenIsNeverLoadedAgainForAFlatMoment() {
        val busy = RenderProbe.verdict(1080, 1800, null, null, late)                          // the page did not answer, the screen could not be sampled
        val flatLater = RenderProbe.verdict(1080, 1800, page(canvases = 1, blank = 1), flat, late)
        listOf(busy, flatLater).forEach { v ->
            assertEquals(RenderProbe.State.NOT_RENDERED, v.state)
            assertTrue(RenderProbe.stepToCpu(v, cpu = false, viewW = 1080, viewH = 1800, seen = false))
            assertFalse(RenderProbe.stepToCpu(v, cpu = false, viewW = 1080, viewH = 1800, seen = true))
            assertTrue(RenderProbe.alarm(v, seen = false)); assertFalse(RenderProbe.alarm(v, seen = true))
        }
        assertFalse(RenderProbe.alarm(null, false))
        assertFalse(RenderProbe.alarm(RenderProbe.verdict(1080, 1800, page(), painted, late), false))    // rendered
        assertFalse(RenderProbe.alarm(RenderProbe.verdict(1080, 1800, page(), flat, 0), false))          // still loading
        val g = source("app/src/main/java/com/diegonmarcos/cgomniverse/ui/GalaxyPage.kt")
        listOf(
            "if (v.state == RenderProbe.State.RENDERED) seenOnScreen = true", "RenderProbe.stepToCpu(v, cpu, viewSize.width, viewSize.height, seenOnScreen)",
            "rv != null && RenderProbe.alarm(rv, seenOnScreen) && !renderHidden",
            // a new load, and a new document inside the same load, start unseen
            "render = null; renderPage = null; renderHidden = false; seenOnScreen = false", "seenOnScreen = false; committedAt = System.currentTimeMillis()",
        ).forEach { assertTrue("GalaxyPage lacks $it", g.contains(it)) }
    }

    @Test fun aPageWithPixelsAndNoScreenSampleCountsAsRendered() {
        assertEquals(RenderProbe.State.RENDERED, RenderProbe.verdict(1080, 1800, page(canvases = 1, drawn = 1), null, 0).state)
        assertEquals(RenderProbe.State.NOT_RENDERED, RenderProbe.verdict(1080, 1800, page(canvases = 1, blank = 1), null, late).state)
    }

    @Test fun aWorldNotOnTheScreenIsLoadedOnceInTheCpuPresentation() {
        val none = RenderProbe.verdict(1080, 1800, page(videos = 6, vframes = 300), flat, late)
        assertTrue(RenderProbe.stepToCpu(none, cpu = false, viewW = 1080, viewH = 1800))
        assertFalse("the ladder has one step", RenderProbe.stepToCpu(none, cpu = true, viewW = 1080, viewH = 1800))
        assertFalse("no size is not a presentation problem", RenderProbe.stepToCpu(RenderProbe.verdict(1080, 0, page(), flat, late), false, 1080, 0))
        assertFalse(RenderProbe.stepToCpu(RenderProbe.verdict(1080, 1800, page(), flat, 0), false, 1080, 1800))
        assertFalse(RenderProbe.stepToCpu(RenderProbe.verdict(1080, 1800, page(), painted, late), false, 1080, 1800))
        listOf("HTMLCanvasElement.prototype.getContext", "t==='2d'", "willReadFrequently:true", "g.call(this,t,a)").forEach { assertTrue(it, RenderProbe.CPU_CANVAS_JS.contains(it)) }
        assertTrue(RenderProbe.presentation(true).startsWith("CPU canvas")); assertTrue(RenderProbe.presentation(false).startsWith("GPU layers"))
        assertTrue(source("app/src/main/java/com/diegonmarcos/cgomniverse/ui/Web.kt").contains("addDocumentStartJavaScript(wv, RenderProbe.CPU_CANVAS_JS"))
    }

    /** floor796 (#1694): its canvas engine (the plain address, as in Cloud-Browser) in both presentations; a declared cpu query still wins. */
    @Test fun theCpuPresentationLoadsItsDeclaredAddress() {
        val f = Store.parse(source("data/store.json")).first { it.id == "floor796" }
        assertEquals("", f.launchQuery); assertNull(f.launchQueryCpu)
        assertEquals(f.url, Store.launchUrl(f))
        assertEquals(f.url, Store.launchUrl(f, cpu = true))
        val tiles = f.copy(launchQuery = "old-render", launchQueryCpu = "")
        assertTrue(Store.launchUrl(tiles).endsWith("?old-render")); assertEquals(f.url, Store.launchUrl(tiles, cpu = true))
        val other = f.copy(launchQueryCpu = null)
        assertEquals(Store.launchUrl(other), Store.launchUrl(other, cpu = true))
        assertEquals(f.url + "?soft", Store.launchUrl(f.copy(launchQueryCpu = "soft"), cpu = true))
        assertNull(Store.parse(source("data/store.json")).first { it.id != "floor796" }.launchQueryCpu)
    }

    @Test fun theVerdictCarriesTheFactsAndTheConsoleErrors() {
        val v = RenderProbe.verdict(1080, 1800, page(canvases = 1, blank = 1), flat, late, listOf("[ERROR] a", "[ERROR] TypeError: x (front.js:12)"), 4)
        val t = v.text()
        assertTrue(t, t.startsWith("not rendered: 1 canvases exist"))
        listOf("content area 1080 x 1800 px", "screen: one flat colour #303030", "page viewport 412 x 760, visible, 60 frames/s", "4 failed requests",
            "2 console errors, last: [ERROR] TypeError: x (front.js:12)").forEach { assertTrue("verdict lacks $it: $t", t.contains(it)) }
        OmniXray.clearForTest()
        OmniXray.worldRender("floor796", t)
        assertEquals(t, OmniXray.worlds().single().render)
    }

    @Test fun theScriptsMeasureFramesCanvasesAndVideosInThePage() {
        listOf("requestAnimationFrame", "IntersectionObserver", "setTimeout", "__cloudRender").forEach { assertTrue(it, RenderProbe.SCRIPT.contains(it)) }
        listOf("innerHeight", "visibilityState", "querySelectorAll('canvas')", "getImageData", "querySelectorAll('video')", "getVideoPlaybackQuality",
            "JSON.stringify").forEach { assertTrue(it, RenderProbe.READ.contains(it)) }
        // the app's banner must stay above the sampled part of the area, or it would read as the world's own pixels
        assertTrue(RenderProbe.BANNER_MAX < RenderProbe.SAMPLE_TOP)
    }

    @Test fun aWorldViewHasTheSettingsASceneNeeds() {
        val p = WebProfile.WEB_WORLD
        assertTrue(p.javaScript); assertTrue(p.storage); assertTrue(p.remoteImages); assertTrue(p.autoplay); assertTrue(p.thirdPartyCookies)
        val web = source("../ab_cloud-libs-shared/libs/web-core/src/main/java/com/diegonmarcos/cloudlib/web/CloudWeb.kt")
        listOf("javaScriptEnabled = profile.javaScript", "domStorageEnabled = profile.storage", "mediaPlaybackRequiresUserGesture = !profile.autoplay",
            "blockNetworkImage = !profile.remoteImages", "mixedContentMode = profile.mixedContent", "wv.setLayerType(View.LAYER_TYPE_NONE, null)")
            .forEach { assertTrue("CloudWeb lacks $it", web.contains(it)) }
        assertFalse("a world view must not be forced onto a software layer", web.contains("LAYER_TYPE_SOFTWARE, null"))
        assertTrue(source("app/src/main/AndroidManifest.xml").contains("android:hardwareAccelerated=\"true\""))
        val chrome = source("../ab_cloud-libs-shared/libs/web-core/src/main/java/com/diegonmarcos/cloudlib/web/CloudChromeClient.java")
        assertTrue(chrome.contains("onConsoleMessage") && chrome.contains("PageConsole.INSTANCE.record"))
    }

    @Test fun galaxyGivesTheWorldARealAreaAndWiresTheRenderCheck() {
        val g = source("app/src/main/java/com/diegonmarcos/cgomniverse/ui/GalaxyPage.kt")
        listOf(
            "CloudWeb.create(c, WebProfile.WEB_WORLD, desktop)", "object : CloudChromeClient(",
            // the world fills what the bars leave, and its measured size reaches the verdict
            "WorldView(cur, Modifier.weight(1f).fillMaxWidth())", "Box(Modifier.weight(1f).fillMaxWidth().onSizeChanged { viewSize = it })",
            "modifier = Modifier.fillMaxSize().testTag(Tags.WORLD_WEB)", "RenderProbe.verdict(viewSize.width, viewSize.height,",
            "RenderProbe.SCRIPT", "RenderProbe.READ", "Web.screenSample(", "PageConsole.errors()", "OmniXray.worldRender(w.id,",
            "key(w.id, generation, desktop, cpu)", "if (cpu) Web.cpuCanvas(wv)", "RenderProbe.stepToCpu(v, cpu, viewSize.width, viewSize.height, seenOnScreen)",
            "Web.setCpuPresent(ctx, w.id, true)", "Tags.WORLD_RENDER_GPU",
            "Tags.WORLD_RENDER_LOADING", "Tags.WORLD_RENDER_NONE", "R.string.world_render_loading", "R.string.world_render_none", "\"Render check\"",
        ).forEach { assertTrue("GalaxyPage lacks $it", g.contains(it)) }
        assertTrue(source("app/src/main/java/com/diegonmarcos/cgomniverse/ui/Web.kt").contains("PixelCopy.request(act.window"))
        assertNotNull(Regex("\"world \" \\+ r\\.id \\+ \" render check\"").find(source("app/src/main/java/com/diegonmarcos/cgomniverse/OmniXray.kt")))
    }

    /** Rule 16: the tab's controls and cards are all still there (the site's own About / Subscribe / Updates and en | ru are the page's). */
    @Test fun everyControlOfTheWorldTabIsKept() {
        val g = source("app/src/main/java/com/diegonmarcos/cgomniverse/ui/GalaxyPage.kt")
        listOf(
            "Tags.WORLD_DESKTOP" to "Icons.Filled.DesktopWindows", "Tags.WORLD_DIAG" to "Icons.Filled.BugReport", "Tags.WORLD_STATS" to "Icons.Filled.Speed",
            "Tags.WORLD_RELOAD" to "Icons.Filled.Refresh", "Tags.WORLD_BROWSER" to "Icons.Filled.OpenInBrowser", "Tags.WORLD_VR" to "Icons.Filled.ViewInAr",
        ).forEach { (tag, icon) -> assertTrue("$tag / $icon", g.contains("testTag($tag)") && g.contains(icon)) }
        listOf("Tags.GALAXY_TABS", "Tags.GALAXY_ADD", "Tags.WORLD_STATS_HUD", "Tags.WORLD_GPU", "Tags.WORLD_OFFLINE", "Tags.WORLD_OFFLINE_WAYBACK",
            "Tags.WORLD_FALLBACK", "Tags.WORLD_DIAG_SHEET", "Store.launchUrl(w, cpu)", "Pinned.intercept(", "PageDiag.SCRIPT", "\"Console errors (")
            .forEach { assertTrue("GalaxyPage lost $it", g.contains(it)) }
    }

    /** #1694 a heavy world declares a longer wait: still loading inside it, failed after it, and never shorter than the default. */
    @Test fun aWorldsOwnGraceExtendsTheLoadingWindow() {
        val g = 240_000L
        assertEquals(RenderProbe.State.LOADING, RenderProbe.verdict(1080, 1800, page(), flat, g - 1, graceMs = g).state)
        assertEquals(RenderProbe.State.NOT_RENDERED, RenderProbe.verdict(1080, 1800, page(), flat, g, graceMs = g).state)
        assertEquals(RenderProbe.State.NOT_RENDERED, RenderProbe.verdict(1080, 1800, page(), flat, RenderProbe.GRACE_MS, graceMs = 1_000L).state)
        assertEquals(RenderProbe.State.LOADING, RenderProbe.verdict(1080, 1800, page(), flat, RenderProbe.GRACE_MS - 1, graceMs = 1_000L).state)
    }
}

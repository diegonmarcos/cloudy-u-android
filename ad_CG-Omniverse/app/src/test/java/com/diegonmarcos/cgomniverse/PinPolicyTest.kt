package com.diegonmarcos.cgomniverse

import com.diegonmarcos.cgomniverse.ui.Pinned
import com.diegonmarcos.cloudlib.web.PinLog
import com.diegonmarcos.cloudlib.web.PinPolicy
import com.diegonmarcos.cloudlib.web.PinPolicy.Action.PASS
import com.diegonmarcos.cloudlib.web.PinPolicy.Action.PASS_AND_RECORD
import com.diegonmarcos.cloudlib.web.PinPolicy.Action.SERVE
import com.diegonmarcos.cloudlib.web.PinPolicy.Action.SERVE_NEWEST
import com.diegonmarcos.cloudlib.web.PinPolicy.Source.COPY
import com.diegonmarcos.cloudlib.web.PinPolicy.Source.LIVE
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #1711 the pinned copy never stands between a live page and the network. floor796 stayed blank on the phone (its top bar over a
 * flat #101010 page, 0 canvases, 0 failed requests): its first data request, data/matrix.json?r=<random>, is a new address on
 * every load, so the copy could never hold it and the intercept fetched it itself inside the WebView's request; while that
 * fetch had no answer the scene could not start. The rule ([PinPolicy], pure) and the places that apply it are held here.
 */
class PinPolicyTest {
    private fun module(path: String): File = File(File("").absoluteFile.let { if (it.name == "app") it.parentFile else it }, path)
    private fun app(name: String) = module("app/src/main/java/com/diegonmarcos/cgomniverse/$name").readText()
    private fun lib(name: String) = module("../ab_cloud-libs-shared/libs/web-core/src/main/java/com/diegonmarcos/cloudlib/web/$name").readText()

    /** Galaxy: documents live. */
    private fun galaxy(document: Boolean, refresh: Boolean = false, stored: Boolean = false, storedPath: Boolean = false, source: PinPolicy.Source = LIVE) =
        PinPolicy.decide(source, document, true, refresh, stored, storedPath)

    /** The floor796 case: what the copy does not hold is loaded by the WebView itself and stored afterwards. */
    @Test fun aRequestTheCopyDoesNotHoldGoesStraightToTheNetwork() {
        assertEquals(PASS_AND_RECORD, galaxy(document = false))                                // data/matrix.json?r=0.123: never stored
        assertEquals(PASS_AND_RECORD, galaxy(document = false, storedPath = true))             // an older query of it is stored: still live
        assertEquals(PASS_AND_RECORD, galaxy(document = true, stored = true))                  // #1662 a document is live
        assertEquals(PASS_AND_RECORD, galaxy(document = true))
        assertEquals(PASS_AND_RECORD, galaxy(document = false, stored = true, refresh = true)) // an explicit reload refetches
        assertEquals(SERVE, galaxy(document = false, stored = true))                           // a stored asset stays cache-first
        // Cloud-Browser keeps its pinned documents cache-first; what it does not hold is live there too
        assertEquals(SERVE, PinPolicy.decide(LIVE, true, false, false, true, false))
        assertEquals(PASS_AND_RECORD, PinPolicy.decide(LIVE, true, false, false, false, false))
        assertEquals(PASS_AND_RECORD, PinPolicy.decide(LIVE, false, false, false, false, true))
    }

    /** The live site does not answer: the world is shown from the copy, which answers a never-stored query with the newest one it holds. */
    @Test fun thePinnedCopyAnswersWhenTheLiveSiteDoesNot() {
        assertEquals(SERVE, galaxy(document = true, stored = true, source = COPY))
        assertEquals(SERVE, galaxy(document = false, stored = true, source = COPY))
        assertEquals(SERVE_NEWEST, galaxy(document = false, storedPath = true, source = COPY))  // data/matrix.json?r=<a new random>
        assertEquals(PASS, galaxy(document = false, source = COPY))                             // nothing to answer with
        assertEquals(SERVE, galaxy(document = false, stored = true, refresh = true, source = COPY))
        assertEquals(COPY, Pinned.source(true)); assertEquals(LIVE, Pinned.source(false))
    }

    @Test fun aDocumentIsTheMainFrameOrAnythingAskingForHtml() {
        assertTrue(PinPolicy.isDocument(true, null))
        assertTrue(PinPolicy.isDocument(false, "text/html,application/xhtml+xml,*/*;q=0.8"))
        assertFalse(PinPolicy.isDocument(false, "*/*"))
        assertFalse(PinPolicy.isDocument(false, null))
        assertEquals(PinPolicy.isDocument(false, "TEXT/HTML"), Pinned.networkFirst(false, "TEXT/HTML"))
    }

    /** Nothing is fetched inside the WebView's request any more: the intercept decides and queues, one background thread stores. */
    @Test fun theInterceptNeverFetchesInsideTheRequest() {
        val a = lib("SiteMirrorAndroid.kt")
        val intercept = a.substringAfter("    fun intercept(").substringBefore("    private fun header(")
        assertTrue(intercept.length > 400)
        listOf("http.fetch", "openConnection", "HttpURLConnection", ".put(").forEach { assertFalse("the intercept still calls $it", intercept.contains(it)) }
        listOf("PinPolicy.decide(source, document, liveDocuments, refresh, stored, storedPath)", "PinRecorder.record(ctx, host, url, document, refresh)",
            "PinLog.add(PinLog.LIVE", "return null").forEach { assertTrue("the intercept lacks $it", intercept.contains(it)) }
        val recorder = a.substringAfter("object PinRecorder").substringBefore("class SiteMirrorRunner")
        listOf("SiteMirrorStore.http.fetch(", "Thread({ drain(app) }, \"pin-recorder\")", "PinPolicy.record(", "c.put(job.host, job.url, 200,")
            .forEach { assertTrue("the recorder lacks $it", recorder.contains(it)) }
        // Galaxy hands the intercept its source (live or the copy) and never fetches by itself either
        val p = app("ui/Pinned.kt")
        assertTrue(p.contains("SiteMirrorStore.intercept(ctx, listOf(PinnedSites.keyOf(host)), req, refresh, liveDocuments = true, source = source(fromCopy))"))
        assertFalse(p.contains("http.fetch")); assertFalse(p.contains("openConnection"))
    }

    /** What the background recorder fetches: everything on a reload, a document and a cache-defeating address once an hour, a stored file never, a new file now. */
    @Test fun theCopyIsWrittenInTheBackgroundByRule() {
        val now = 10_000_000L; val fresh = now - 60_000L; val old = now - PinPolicy.RECORD_AGAIN_MS
        assertTrue(PinPolicy.record(true, false, false, fresh, fresh, now))                 // reload: always
        assertTrue(PinPolicy.record(false, false, false, null, null, now))                   // a file the copy lacks
        assertFalse(PinPolicy.record(false, false, false, fresh, fresh, now))                // a stored file: the copy does not expire
        assertFalse(PinPolicy.record(false, false, false, 0L, 0L, now))
        assertTrue(PinPolicy.record(false, true, false, null, null, now))                    // a document never stored
        assertFalse(PinPolicy.record(false, true, false, fresh, fresh, now))                 // a document stored a minute ago
        assertTrue(PinPolicy.record(false, true, false, old, old, now))                      // a document stored an hour ago
        assertTrue(PinPolicy.record(false, false, true, null, null, now))                    // matrix.json?r=..., nothing of the path stored
        assertFalse(PinPolicy.record(false, false, true, null, fresh, now))                  // ... a query of the path stored a minute ago
        assertTrue(PinPolicy.record(false, false, true, null, old, now))                     // ... an hour ago
        assertTrue(PinPolicy.record(false, false, true, null, 0L, now))                      // ... by a build from before #1711 (no time)
        assertFalse(PinPolicy.record(false, false, true, fresh, fresh, now))                 // favicon.ico?10 stored exactly
    }

    @Test fun aBareOriginIsTheDocumentWithItsSlash() {
        assertEquals("https://floor796.com/", PinPolicy.documentKey("https://floor796.com"))
        assertEquals("https://floor796.com/", PinPolicy.documentKey("https://floor796.com/"))
        assertEquals("https://floor796.com/?old-render", PinPolicy.documentKey("https://floor796.com?old-render"))
        assertEquals("https://floor796.com/?old-render", PinPolicy.documentKey("https://floor796.com/?old-render"))
        assertEquals("https://x.example/a/b?c=1", PinPolicy.documentKey(" https://x.example/a/b?c=1#frag "))
        assertEquals("not a url", PinPolicy.documentKey("not a url"))
    }

    @Test fun theDiagnosticsLineSaysWhatPinOfflineDoes() {
        assertEquals("off", Pinned.status(false, false, ""))
        val live = Pinned.status(true, false, "")
        assertTrue(live, live.startsWith("on (documents network-first, assets from the pinned copy") && live.contains("stored in the background"))
        val copy = Pinned.status(true, true, "the live site did not answer in 8 s")
        assertTrue(copy, copy.startsWith("on, showing the pinned copy (the live site did not answer in 8 s)") && copy.contains("Reload asks the live site again"))
        assertTrue(Pinned.status(true, true, "").contains("the live site did not answer"))
    }

    @Test fun whatTheInterceptDidIsLoggedPerSite() {
        PinLog.clear()
        PinLog.add(PinLog.SERVED, "https://floor796.com/front.css", 3, "19301 bytes", now = 1)
        PinLog.add(PinLog.LIVE, "https://floor796.com/data/matrix.json?r=0.5", 0, "not in the copy yet", now = 2)
        PinLog.add(PinLog.RECORDED, "https://floor796.com/data/matrix.json?r=0.5", 412, "67287 bytes", now = 3)
        PinLog.add(PinLog.RECORD_FAILED, "https://other.example/x", -1, "no answer", now = 4)
        assertEquals(3, PinLog.of("floor796.com").size); assertEquals(1, PinLog.of("other.example").size)
        assertEquals("served from the copy in 3 ms (19301 bytes): https://floor796.com/front.css", Pinned.log("floor796.com").first())
        assertEquals("left to the network in 0 ms (not in the copy yet): https://floor796.com/data/matrix.json?r=0.5", Pinned.log("floor796.com")[1])
        assertEquals("not stored (no answer): https://other.example/x", PinLog.of("other.example").single().line())
        assertEquals("1 served from the copy, 1 left to the network, 1 stored", Pinned.logSummary("floor796.com"))
        repeat(PinLog.CAPACITY + 30) { PinLog.add(PinLog.LIVE, "https://floor796.com/n$it", 0) }
        assertEquals(PinLog.CAPACITY, PinLog.snapshot().size)
        assertEquals(40, Pinned.log("floor796.com").size)
        PinLog.clear(); assertTrue(Pinned.log("floor796.com").isEmpty()); assertEquals("", Pinned.logSummary("floor796.com"))
    }

    /** Galaxy: the live document that does not arrive is replaced by the pinned copy, and the sheet says so. */
    @Test fun galaxyShowsThePinnedCopyWhenTheLiveDocumentDoesNotArrive() {
        assertEquals(8_000L, PinPolicy.LIVE_WAIT_MS)
        val g = app("ui/GalaxyPage.kt")
        listOf(
            "Pinned.intercept(c, host, request, refreshing, fromCopy)", "delay(PinPolicy.LIVE_WAIT_MS)", "LaunchedEffect(w.id, loadSeq)",
            "if (committed || fromCopy || error != null || offline) return@LaunchedEffect", "Pinned.hasDocument(ctx, host, url)",
            "fromCopy = true; finished = false", "wv.stopLoading(); wv.loadUrl(url)",
            // a main-frame failure of the world's own document (offline, no route) goes to the copy instead of the error card
            "PinPolicy.documentKey(request.url.toString()) == PinPolicy.documentKey(copyUrl) && Pinned.hasDocument(c, host, copyUrl)",
            "view.post { view.loadUrl(copyUrl) }",
            // the arrival of the live document is what cancels the fallback; every load the WebView starts is watched
            "committed = false; startedUrl = url; loadSeq++", "canBack = view.canGoBack(); arrived()", "override fun onPageCommitVisible(",
            "val arrived = { if (!committed) { committed = true; committedAt = System.currentTimeMillis() }; Unit }",
            // an explicit reload asks the live site again
            "val reload = { refreshing = true; fromCopy = false; copyWhy = \"\";",
            "\"Pin offline: \" + Pinned.status(pinned, fromCopy, copyWhy)", "\"Pin offline log (\${pinLog.size}): \"", "\" · pinned copy\"",
        ).forEach { assertTrue("GalaxyPage lacks $it", g.contains(it)) }
    }

    /**
     * The live document arrived but its first data request has no answer: the page shows its top bar over a flat page and holds
     * no canvas, for as long as it is left alone (what the phone showed for floor796, with 0 failed requests and 0 console
     * errors). After [Pinned.SCENE_WAIT_MS] the pinned copy is shown instead; a page that has its scene is never touched.
     */
    @Test fun aLivePageThatStartsNoSceneIsShownFromThePinnedCopy() {
        fun page(canvases: Int = 0, videos: Int = 0, gl: Int = 0, gpu: Int = 0, d2: Int = 0): RenderProbe.Page = RenderProbe.parse(JSONObject.quote(
            JSONObject().put("vw", 501).put("vh", 799).put("vis", "visible").put("raf", 60).put("canvases", canvases).put("videos", videos)
                .put("gl", gl).put("gpu", gpu).put("d2", d2).toString()))!!
        val wait = Pinned.SCENE_WAIT_MS
        assertEquals(45_000L, wait)
        // floor796 with data/matrix.json held, measured in Chromium: 0 canvases, 0 videos, and three 2D contexts it made on the way
        val stuck = page(d2 = 3)
        assertTrue(Pinned.sceneOverdue(wait, false, stuck))
        assertTrue(Pinned.sceneOverdue(wait + 60_000L, false, stuck))
        assertFalse(Pinned.sceneOverdue(wait - 1L, false, stuck))                          // still inside the wait
        assertFalse(Pinned.sceneOverdue(wait, true, stuck))                                // the screen shows the world
        assertFalse(Pinned.sceneOverdue(wait, false, null))                                // the page did not answer the check: nothing is concluded
        // a page that has its scene stays with the live site, however long its first frame takes
        assertFalse(Pinned.sceneOverdue(wait * 4, false, page(canvases = 1, d2 = 11)))     // floor796 once its data arrived
        assertFalse(Pinned.sceneOverdue(wait * 4, false, page(videos = 2)))
        assertFalse(Pinned.sceneOverdue(wait * 4, false, page(gl = 1)))                    // a canvas in a closed shadow root (Messenger)
        assertFalse(Pinned.sceneOverdue(wait * 4, false, page(gpu = 1)))
        // Galaxy asks once per load, only for a pinned world on its live page, and only when the copy holds the world's document
        val g = app("ui/GalaxyPage.kt")
        listOf(
            "if (pinned && !fromCopy && !copyAsked && committed && Pinned.sceneOverdue(System.currentTimeMillis() - committedAt, v.state == RenderProbe.State.RENDERED, pg)) {",
            "copyAsked = true",
            "if (PinPolicy.documentKey(startedUrl) == PinPolicy.documentKey(url) && withContext(Dispatchers.IO) { Pinned.hasDocument(ctx, host, url) }) {",
            "copyWhy = \"the live page started no scene in \${Pinned.SCENE_WAIT_MS / 1000} s\"",
            "fromCopy = true; finished = false; finishedAt = 0L",
        ).forEach { assertTrue("GalaxyPage lacks $it", g.contains(it)) }
        // the copy keeps the world's own render wait: the same page decodes the same tiles
        assertTrue(g.contains("(rec?.subErrors?.size ?: 0) + (rec?.httpErrors?.size ?: 0), w.renderGraceMs)"))
    }
}

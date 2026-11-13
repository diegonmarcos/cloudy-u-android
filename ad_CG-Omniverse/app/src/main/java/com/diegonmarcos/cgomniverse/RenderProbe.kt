package com.diegonmarcos.cgomniverse

import org.json.JSONObject
import org.json.JSONTokener

/**
 * #1657 why a Galaxy world shows nothing. Two measurements, neither a proxy: what the PAGE drew (in the page: animation frames,
 * canvases read back, video frames decoded, the viewport it was given) and what the SCREEN shows (the host samples the pixels of
 * the world's area; [screen]). [verdict] names the stage that failed, so a grey world says why. Pure: the JVM suite holds every
 * rule; Galaxy only runs [SCRIPT] / [READ] in the page, copies the pixels and draws the verdict.
 */
object RenderProbe {
    /** A world is still loading for this long after its main frame finished; a flat screen before that is not a failure. */
    const val GRACE_MS = 20_000L
    const val POLL_MS = 2_500L
    /** The slow re-check once a world is on screen (a render can still die: context lost, renderer gone). */
    const val POLL_RENDERED_MS = 10_000L
    /** How long [SCRIPT] counts before [READ]. */
    const val COUNT_MS = 1_000L
    /** The screen sample is SAMPLE x SAMPLE pixels of the world's area below [SAMPLE_TOP] of its height (the app's own overlays sit above). */
    const val SAMPLE = 48
    const val SAMPLE_TOP = 0.45f
    /** The no-render banner takes at most this share of the area's height, so it never reaches the sample and reads as "content". */
    const val BANNER_MAX = 0.36f
    /** One quantised colour covering at least this share of the sample is a flat screen. */
    const val FLAT_SHARE = 0.99f

    /**
     * Starts the heartbeat in the page (idempotent, bounded to 120 frames per call): animation frames, a timer and an
     * IntersectionObserver, the three things a scene's loop hangs on. No bridge is added to a foreign page.
     */
    const val SCRIPT = "(function(){var r=window.__cloudRender=window.__cloudRender||{io:false};r.raf=0;r.t=0;var n=0;" +
        "function f(){r.raf++;if(++n<120)requestAnimationFrame(f)}requestAnimationFrame(f);setTimeout(function(){r.t=1},50);" +
        "if(!r.o){try{r.o=new IntersectionObserver(function(){r.io=true});r.o.observe(document.documentElement)}catch(e){}}})();"

    /** Reads what the page drew ([SCRIPT] first, [COUNT_MS] earlier). A canvas is read back through a 16 px copy; a tainted one is "unreadable". */
    const val READ = "(function(){var r=window.__cloudRender||{};var o={vw:window.innerWidth,vh:window.innerHeight,vis:document.visibilityState," +
        "raf:r.raf||0,timer:!!r.t,io:!!r.io,canvases:0,drawn:0,blank:0,unreadable:0,cw:0,ch:0,videos:0,vready:0,vframes:0,verr:0,imgs:0,ibroken:0," +
        "gl:(window.__cloudCtx||{}).gl||0,gpu:(window.__cloudCtx||{}).gpu||0,d2:(window.__cloudCtx||{}).d2||0,glc:0};" +
        "try{var W=(window.__cloudCtx||{}).c||[];for(var q=0;q<W.length;q++){var E=W[q].deref();if(E&&E.isConnected)o.glc++}}catch(e){}" +
        "var N=window.__cloudCtx;if(N)N.off=1;var cs=document.querySelectorAll('canvas');o.canvases=cs.length;" +
        "for(var i=0;i<cs.length&&i<12;i++){var c=cs[i];if(c.clientWidth*c.clientHeight>o.cw*o.ch){o.cw=c.clientWidth;o.ch=c.clientHeight}" +
        "try{var t=document.createElement('canvas');t.width=t.height=16;var x=t.getContext('2d',{willReadFrequently:true});x.drawImage(c,0,0,16,16);" +
        "var d=x.getImageData(0,0,16,16).data,k=0;for(var j=4;j<d.length;j+=4){if(d[j]!==d[0]||d[j+1]!==d[1]||d[j+2]!==d[2]||d[j+3]!==d[3]){k=1;break}}" +
        "if(k)o.drawn++;else o.blank++}catch(e){o.unreadable++}}if(N)N.off=0;" +
        "var vs=document.querySelectorAll('video');o.videos=vs.length;" +
        "for(i=0;i<vs.length;i++){var v=vs[i];if(v.readyState>=2)o.vready++;if(v.error)o.verr++;" +
        "try{o.vframes+=v.getVideoPlaybackQuality().totalVideoFrames}catch(e){}}" +
        "var im=document.images;o.imgs=im.length;for(i=0;i<im.length;i++){if(im[i].complete&&!im[i].naturalWidth)o.ibroken++}" +
        "return JSON.stringify(o)})()"

    /**
     * #1711 run at document start in every world: counts the canvases the page gives a context, by kind (WebGL, WebGPU, 2D; each
     * canvas once), and remembers (weakly, so nothing is kept alive) the ones that got a WebGL / WebGPU context. A scene whose
     * canvas the page's own list cannot reach (a closed shadow root: Messenger's three.js canvas) is invisible to [READ]'s canvas
     * list, so without this a world that is rendering would read "0 canvases" and, on a flat screen, "its scene never started".
     * [READ] reports how many of the remembered canvases are attached to the page now: a context alone is not a scene (a page
     * makes one on a throwaway canvas only to see whether WebGL exists). This app's own checks ([READ]'s read-back canvases, the
     * capability check run through [own]) switch the count off while they run: measured on the emulator before that, floor796
     * with no scene at all read 1 WebGL context, the capability check's. The page's own call is passed through untouched.
     */
    const val CONTEXT_JS = "(function(){if(window.__cloudCtx)return;var n=window.__cloudCtx={gl:0,gpu:0,d2:0,c:[],off:0};var s=new WeakSet();" +
        "var g=HTMLCanvasElement.prototype.getContext;" +
        "HTMLCanvasElement.prototype.getContext=function(t){var r=g.apply(this,arguments);try{if(r&&!n.off&&!s.has(this)){s.add(this);" +
        "if(t==='2d')n.d2++;else if(t==='webgpu')n.gpu++;else if(/webgl/.test(t))n.gl++;" +
        "if((t==='webgpu'||/webgl/.test(t))&&window.WeakRef&&n.c.length<64)n.c.push(new WeakRef(this))}}catch(e){}return r}})();"

    /** #1711 wraps one of this app's own page checks ([script], an expression statement) so the canvas contexts it makes are not counted as the page's ([CONTEXT_JS]). */
    fun own(script: String): String = "(function(){var n=window.__cloudCtx;if(n)n.off=1;try{" + script + "}finally{if(n)n.off=0}})()"

    /**
     * The CPU presentation, run at document start: every 2D canvas context is CPU-backed (willReadFrequently), so the page's
     * frames reach the compositor as a plain bitmap, the way an image does, instead of as a GPU layer.
     */
    const val CPU_CANVAS_JS = "(function(){var g=HTMLCanvasElement.prototype.getContext;HTMLCanvasElement.prototype.getContext=function(t,a){" +
        "if(t==='2d'){a=Object.assign({},a||{},{willReadFrequently:true})}return g.call(this,t,a)};window.__cloudCpuCanvas=true})();"

    /**
     * The presentation ladder: a world that did not reach the screen as the page draws it (GPU layers) is loaded once more in
     * the CPU presentation; one that fails there too is reported. A content area without size is not a presentation problem.
     * #1711 [seen] = the screen already showed this world in this load: its layers do reach the screen, so a later flat or
     * unreadable moment (a game's loading screen between two scenes, a renderer busy compiling) is the page's own state and the
     * world is never loaded again for it (measured on the emulator: Cyber City Orion was thrown back from 21% to 0%).
     */
    fun stepToCpu(v: Verdict, cpu: Boolean, viewW: Int, viewH: Int, seen: Boolean = false): Boolean =
        v.state == State.NOT_RENDERED && !cpu && !seen && viewW > 0 && viewH > 0

    /** #1711 the "is not rendering" banner is for a world that has not been on the screen in this load ([seen] false); the verdict itself stays in Page diagnostics. */
    fun alarm(v: Verdict?, seen: Boolean): Boolean = v != null && v.state == State.NOT_RENDERED && !seen

    fun presentation(cpu: Boolean): String = if (cpu) "CPU canvas (the GPU layers did not reach the screen)" else "GPU layers (as the page draws)"

    /** What the page answered to [READ]. */
    data class Page(
        val viewportW: Int, val viewportH: Int, val visibility: String,
        val frames: Int, val timer: Boolean, val observer: Boolean,
        val canvases: Int, val drawn: Int, val blank: Int, val unreadable: Int, val canvasW: Int, val canvasH: Int,
        val videos: Int, val videosReady: Int, val videoFrames: Int, val videoErrors: Int,
        val images: Int, val imagesBroken: Int,
        /** #1711 the canvases the page gave a context ([CONTEXT_JS]), wherever they are: WebGL, WebGPU, 2D. */
        val glContexts: Int = 0, val gpuContexts: Int = 0, val contexts2d: Int = 0,
        /** #1711 the canvases holding a WebGL / WebGPU context that are attached to the page now, in the document or in any shadow root. */
        val gpuCanvases: Int = 0,
    ) {
        /** The page itself holds pixels: a canvas that reads back drawn, or a video that decoded a frame. */
        val hasPixels: Boolean get() = drawn > 0 || videoFrames > 0

        /** #1711 the page holds a scene: a canvas or a video in the document, or a GPU canvas attached anywhere (a closed shadow root). */
        val hasScene: Boolean get() = canvases > 0 || videos > 0 || gpuCanvases > 0

        fun rows(): List<Pair<String, String>> = listOf(
            "Page viewport" to "$viewportW x $viewportH css px, $visibility",
            "Animation frames in ${COUNT_MS / 1000} s" to frames.toString(),
            "Timers / IntersectionObserver" to (if (timer) "running" else "not firing") + " / " + (if (observer) "firing" else "not firing"),
            "Canvases" to "$canvases ($drawn drawn, $blank blank, $unreadable unreadable; largest $canvasW x $canvasH css px)",
            "Canvas contexts created" to "$glContexts WebGL, $gpuContexts WebGPU, $contexts2d 2D (by the page, counted from its start); " +
                "$gpuCanvases GPU canvases attached to the page (shadow roots included)",
            "Videos" to "$videos ($videosReady with data, $videoFrames frames decoded, $videoErrors errors)",
            "Images" to "$images ($imagesBroken broken)",
        )
    }

    /** [raw] is what evaluateJavascript returned for [READ]: a JSON string, itself quoted. Null when the page did not answer. */
    fun parse(raw: String?): Page? {
        val inner = raw?.takeIf { it.isNotEmpty() && it != "null" }?.let { r ->
            runCatching { if (r.startsWith("\"")) JSONTokener(r).nextValue() as String else r }.getOrNull()
        } ?: return null
        val o = runCatching { JSONObject(inner) }.getOrNull() ?: return null
        if (!o.has("vw")) return null
        return Page(
            o.optInt("vw"), o.optInt("vh"), o.optString("vis"), o.optInt("raf"), o.optBoolean("timer"), o.optBoolean("io"),
            o.optInt("canvases"), o.optInt("drawn"), o.optInt("blank"), o.optInt("unreadable"), o.optInt("cw"), o.optInt("ch"),
            o.optInt("videos"), o.optInt("vready"), o.optInt("vframes"), o.optInt("verr"), o.optInt("imgs"), o.optInt("ibroken"),
            o.optInt("gl"), o.optInt("gpu"), o.optInt("d2"), o.optInt("glc"),
        )
    }

    /** The pixels the screen really shows in the world's area: how many colours, and the one that dominates. */
    data class Screen(val colours: Int, val dominant: Int, val share: Float) {
        val flat: Boolean get() = share >= FLAT_SHARE
        fun hex(): String = "#%06x".format(dominant and 0xFFFFFF)
        fun text(): String = if (flat) "one flat colour ${hex()} (${(share * 100).toInt()}% of the sample)" else "$colours colours"
    }

    /** [pixels] = ARGB values copied from the screen. Colours are compared at 4 bits per channel, so dithering is not "content". */
    fun screen(pixels: IntArray): Screen? {
        if (pixels.isEmpty()) return null
        val counts = HashMap<Int, Int>()
        for (p in pixels) { val q = p and 0xF0F0F0; counts[q] = (counts[q] ?: 0) + 1 }
        val top = counts.maxByOrNull { it.value }!!
        return Screen(counts.size, top.key, top.value.toFloat() / pixels.size)
    }

    enum class State { LOADING, RENDERED, NOT_RENDERED }

    data class Verdict(val state: State, val reason: String, val details: List<String> = emptyList()) {
        fun text(): String = state.name.lowercase().replace('_', ' ') + ": " + reason + details.joinToString("") { "; $it" }
    }

    /**
     * The stage that failed, first match wins: the content area has no size; the screen shows pixels (rendered); still inside
     * the load grace; the page never answered; its viewport is empty; it produces no frames; it drew but the screen is flat (the
     * WebView is not compositing its canvas / video layers); its videos decoded nothing; its canvases are blank; it has no
     * scene at all. [viewW] x [viewH] = the measured size of the world's area in px; [sinceFinishedMs] < 0 = the main frame has
     * not finished; [graceMs] = a world's own longer wait (never shorter than [GRACE_MS]); [errors] = the page's console errors; [failedRequests] = its failed subresource and HTTP >= 400 requests.
     */
    fun verdict(viewW: Int, viewH: Int, page: Page?, screen: Screen?, sinceFinishedMs: Long, errors: List<String> = emptyList(), failedRequests: Int = 0, graceMs: Long = 0L): Verdict {
        val grace = maxOf(GRACE_MS, graceMs)
        val facts = ArrayList<String>()
        facts += "content area $viewW x $viewH px"
        screen?.let { facts += "screen: " + it.text() }
        page?.let { p ->
            facts += "page viewport ${p.viewportW} x ${p.viewportH}, ${p.visibility}, ${p.frames} frames/s"
            facts += "${p.canvases} canvases (${p.drawn} drawn, ${p.blank} blank), ${p.videos} videos (${p.videoFrames} frames decoded, ${p.videoErrors} errors)"
            if (p.gpuCanvases > 0) facts += "${p.gpuCanvases} GPU canvases attached to the page"
        }
        if (failedRequests > 0) facts += "$failedRequests failed requests"
        if (errors.isNotEmpty()) facts += "${errors.size} console errors, last: " + errors.last().take(200)
        fun no(reason: String) = Verdict(State.NOT_RENDERED, reason, facts)
        if (viewW <= 0 || viewH <= 0) return no("the world's content area has no size ($viewW x $viewH px), so the page is laid out collapsed")
        if (screen != null && !screen.flat) return Verdict(State.RENDERED, "the screen shows the world (${screen.colours} colours)", facts)
        if (sinceFinishedMs < 0) return Verdict(State.LOADING, "loading the page", facts)
        if (sinceFinishedMs < grace && !(screen == null && page?.hasPixels == true)) return Verdict(State.LOADING, "waiting for the first frame", facts)
        if (page == null) return no("the page did not answer the render check (its script is blocked or the renderer is busy)")
        if (page.viewportW <= 0 || page.viewportH <= 0) return no("the page was given an empty viewport (${page.viewportW} x ${page.viewportH} css px)")
        if (page.frames == 0) return no("the page produces no animation frames (it reports itself ${page.visibility}), so its scene loop never runs")
        if (page.hasPixels) {
            return if (screen == null) Verdict(State.RENDERED, "the page drew (the screen could not be sampled)", facts)
            else no("the page drew (${page.drawn} canvases with pixels, ${page.videoFrames} video frames decoded) but the screen shows ${screen.text()}: " +
                "this WebView is not showing its canvas / video layers (a GPU or WebView fault on this device; reset any WebView DevTools flag such as ignore-gpu-blocklist and restart the app)")
        }
        // #1711 a GPU canvas cannot be read back from the page (a WebGL canvas reads blank once it is composited; one in a closed
        // shadow root is not even in the page's canvas list): when the screen cannot be sampled either, the attached GPU canvas is the scene
        if (screen == null && page.gpuCanvases > 0) {
            return Verdict(State.RENDERED, "the page runs a GPU scene (${page.gpuCanvases} attached GPU canvases; the screen could not be sampled)", facts)
        }
        if (page.videos > 0) return no("${page.videos} video tiles, none decoded a frame (${page.videosReady} with data, ${page.videoErrors} errors)")
        if (page.canvases > 0) return no("${page.canvases} canvases exist but nothing was drawn into them (${page.blank} blank, ${page.unreadable} unreadable)")
        // #1711 a scene on a canvas the page's own list cannot reach (a closed shadow root): it started, and the screen says it is not showing
        if (page.gpuCanvases > 0) {
            return no("the page runs a GPU scene (${page.gpuCanvases} attached canvases outside the page's canvas list) but the screen shows " +
                (screen?.text() ?: "nothing that could be sampled") + ": the scene is not reaching the screen")
        }
        return no("the page has no canvas and no video: its scene never started")
    }
}

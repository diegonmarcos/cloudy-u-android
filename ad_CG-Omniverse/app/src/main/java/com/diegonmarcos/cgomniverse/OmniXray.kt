package com.diegonmarcos.cgomniverse

import org.json.JSONArray
import org.json.JSONObject

/**
 * #1631 X-Ray App: the REAL state of the 3D render pipeline, so that when nothing renders the page names the stage that failed.
 *
 * Pure (org.json only, no android.* and no logging) so the JVM suite holds the parsing, the verdict and the exports. The live
 * values come from three places, none of them a proxy:
 *  - the Avatar page itself: assets/avatar/index.html installs a probe BEFORE three.js runs (a wrap of canvas.getContext, the WebGL
 *    draw calls, requestAnimationFrame, fetch, window errors and console) and pushes a snapshot through the OmniDiag bridge;
 *  - the WebView callbacks of the Avatar page and of each Galaxy world (load, errors, HTTP errors, render process gone);
 *  - [OmniProbe]: a native EGL context, the WebView package, the bundled assets read from the APK and the GLB header decoded.
 */
object OmniXray {
    enum class State(val tag: String) { OK("OK"), FAIL("FAIL"), WARN("WARN"), UNKNOWN("--") }

    data class Row(val key: String, val value: String)
    data class Section(val id: String, val title: String, val rows: List<Row>)
    data class Stage(val id: String, val label: String, val state: State, val detail: String)
    data class Event(val t: Long, val src: String, val msg: String)

    data class WebViewInfo(val pkg: String, val version: String, val code: Long)
    data class Egl(val ok: Boolean, val renderer: String, val vendor: String, val version: String, val maxTexture: Int, val error: String)
    data class AssetCheck(val path: String, val found: Boolean, val bytes: Long, val note: String)
    data class WebGl(val webgl2: Boolean, val renderer: String?, val vendor: String?, val webViewVersion: String?, val info: Map<String, String>)

    /** What [OmniProbe] measured natively (null parts = not measured yet). */
    data class Probe(
        val at: Long,
        val webView: WebViewInfo?,
        val egl: Egl?,
        val webgl: WebGl?,
        val assets: List<AssetCheck>,
        val glb: Glb.Report?,
        val modelUrl: String,
        val modelAsset: String,
        val assetUrl: String,
    )

    /** One Galaxy world's lifecycle as the app's WebView callbacks saw it. */
    data class WorldRec(
        val id: String, val url: String, val started: Long, val finished: Long, val progress: Int,
        val mainError: String, val httpErrors: List<String>, val subErrors: List<String>, val renderGone: Int, val diag: String,
        /** #1657 the render check's verdict ([RenderProbe.Verdict.text]): what the page drew and what the screen shows. */
        val render: String = "",
    )

    /** What the Avatar WebView's callbacks and the cgc bridge saw (the page's own snapshot is [page]). */
    data class Avatar(
        val started: Long, val finished: Long, val mainError: String, val subErrors: List<String>, val httpErrors: List<String>,
        val renderGone: Int, val cgc: List<String>, val cgcError: String, val cgcLoaded: Boolean, val cgcReady: Boolean,
        val page: JSONObject?, val pageAt: Long,
    )

    /** Everything the page, the verdict and both exports are built from. */
    data class Report(
        val app: String, val version: String, val sha: String, val capturedAt: String,
        val stages: List<Stage>, val verdict: String, val sections: List<Section>,
    )

    // ── the mutable store the WebView callbacks write to (process wide; the Config page reads it) ──────────────────────────

    private val lock = Any()
    private const val CAP = 60
    private val events = ArrayDeque<Event>()
    private var aStarted = 0L; private var aFinished = 0L; private var aMainError = ""; private var aRenderGone = 0
    private val aSub = ArrayDeque<String>(); private val aHttp = ArrayDeque<String>(); private val aCgc = ArrayDeque<String>()
    private var aCgcError = ""; private var aCgcLoaded = false; private var aCgcReady = false
    private var aPage: JSONObject? = null; private var aPageAt = 0L
    private val worlds = LinkedHashMap<String, WorldRec>()

    private fun <T> ArrayDeque<T>.cap(x: T) { addLast(x); while (size > CAP) removeFirst() }

    fun event(src: String, msg: String, now: Long = System.currentTimeMillis()) = synchronized(lock) { events.cap(Event(now, src, msg.take(400))) }

    fun avatarStarted(url: String, now: Long = System.currentTimeMillis()) = synchronized(lock) {
        aStarted = now; aFinished = 0; aMainError = ""; aCgcLoaded = false; aCgcReady = false; aCgcError = ""; aPage = null; aPageAt = 0
        events.cap(Event(now, "avatar", "page started " + url))
    }

    fun avatarFinished(url: String, now: Long = System.currentTimeMillis()) = synchronized(lock) {
        aFinished = now; events.cap(Event(now, "avatar", "page finished " + url))
    }

    fun avatarError(url: String, mainFrame: Boolean, code: Int, desc: String, now: Long = System.currentTimeMillis()) = synchronized(lock) {
        val line = "$code $desc <- $url"
        if (mainFrame) aMainError = line else aSub.cap(line)
        events.cap(Event(now, "avatar", (if (mainFrame) "main-frame error " else "subresource error ") + line))
    }

    fun avatarHttp(url: String, mainFrame: Boolean, status: Int, now: Long = System.currentTimeMillis()) = synchronized(lock) {
        val line = "HTTP $status <- $url"
        aHttp.cap(line)
        if (mainFrame && status >= 400) aMainError = line
        events.cap(Event(now, "avatar", line))
    }

    fun avatarRenderGone(crashed: Boolean, now: Long = System.currentTimeMillis()) = synchronized(lock) {
        aRenderGone++; events.cap(Event(now, "avatar", "render process gone (" + (if (crashed) "crashed" else "killed by the system") + ")"))
    }

    /** A libs:cgc-3d bridge event ({type: ready|loaded|size|error|...}). */
    fun cgcEvent(o: JSONObject, now: Long = System.currentTimeMillis()) = synchronized(lock) {
        val type = o.optString("type")
        when (type) {
            "ready" -> aCgcReady = true
            "loaded" -> { aCgcLoaded = true; aCgcError = "" }
            "error" -> aCgcError = o.optString("where") + ": " + o.optString("message")
        }
        val line = o.toString().take(300)
        aCgc.cap(line); events.cap(Event(now, "cgc", line))
    }

    /** The page's own snapshot (window.__omni.snap(), pushed once a second and on every error). */
    fun pageReport(json: String, now: Long = System.currentTimeMillis()) {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return
        synchronized(lock) { aPage = o; aPageAt = now }
    }

    private fun world(id: String, url: String? = null, f: (WorldRec) -> WorldRec) {
        val cur = worlds[id] ?: WorldRec(id, url.orEmpty(), 0, 0, 0, "", emptyList(), emptyList(), 0, "")
        worlds[id] = f(cur).let { if (url != null && url.isNotEmpty()) it.copy(url = url) else it }
    }

    fun worldStarted(id: String, url: String, now: Long = System.currentTimeMillis()) = synchronized(lock) {
        world(id, url) { it.copy(started = now, finished = 0, mainError = "", progress = 0) }
        events.cap(Event(now, "world:$id", "page started $url"))
    }

    fun worldFinished(id: String, url: String, now: Long = System.currentTimeMillis()) = synchronized(lock) {
        world(id, url) { it.copy(finished = now, progress = 100) }; events.cap(Event(now, "world:$id", "page finished $url"))
    }

    fun worldProgress(id: String, p: Int) = synchronized(lock) { world(id) { it.copy(progress = p) } }

    fun worldError(id: String, url: String, mainFrame: Boolean, code: Int, desc: String, now: Long = System.currentTimeMillis()) = synchronized(lock) {
        val line = "$code $desc <- $url"
        world(id) { if (mainFrame) it.copy(mainError = line) else it.copy(subErrors = (it.subErrors + line).takeLast(20)) }
        events.cap(Event(now, "world:$id", (if (mainFrame) "main-frame error " else "subresource error ") + line))
    }

    fun worldHttp(id: String, url: String, mainFrame: Boolean, status: Int, now: Long = System.currentTimeMillis()) = synchronized(lock) {
        val line = "HTTP $status <- $url"
        world(id) { it.copy(httpErrors = (it.httpErrors + line).takeLast(20), mainError = if (mainFrame && status >= 400) line else it.mainError) }
        events.cap(Event(now, "world:$id", line))
    }

    fun worldRenderGone(id: String, now: Long = System.currentTimeMillis()) = synchronized(lock) {
        world(id) { it.copy(renderGone = it.renderGone + 1) }; events.cap(Event(now, "world:$id", "render process gone"))
    }

    fun worldDiag(id: String, text: String) = synchronized(lock) { world(id) { it.copy(diag = text) } }

    fun worldRender(id: String, text: String) = synchronized(lock) { world(id) { it.copy(render = text) } }

    fun avatar(): Avatar = synchronized(lock) {
        Avatar(aStarted, aFinished, aMainError, aSub.toList(), aHttp.toList(), aRenderGone, aCgc.toList(), aCgcError, aCgcLoaded, aCgcReady, aPage, aPageAt)
    }

    fun worlds(): List<WorldRec> = synchronized(lock) { worlds.values.toList() }

    fun events(): List<Event> = synchronized(lock) { events.toList() }

    /** Test seam: forget everything recorded. */
    fun clearForTest() = synchronized(lock) {
        events.clear(); aStarted = 0; aFinished = 0; aMainError = ""; aRenderGone = 0; aSub.clear(); aHttp.clear(); aCgc.clear()
        aCgcError = ""; aCgcLoaded = false; aCgcReady = false; aPage = null; aPageAt = 0; worlds.clear()
    }

    // ── the verdict: seven stages, in the order the pipeline runs them ──────────────────────────────────────────────────

    val SOFTWARE_GL = Regex("swiftshader|llvmpipe|softpipe|software|basic render|microsoft basic", RegexOption.IGNORE_CASE)

    fun isSoftware(renderer: String?, vendor: String?): Boolean = SOFTWARE_GL.containsMatchIn((renderer ?: "") + " " + (vendor ?: ""))

    private fun JSONObject.arr(k: String): List<JSONObject> = optJSONArray(k)?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } } ?: emptyList()

    private fun isModel(url: String): Boolean = Regex("\\.(glb|gltf|vrm)(\\?|$)", RegexOption.IGNORE_CASE).containsMatchIn(url)

    fun stages(p: Probe?, a: Avatar): List<Stage> {
        val out = ArrayList<Stage>(7)
        val page = a.page
        val ctxs = page?.arr("ctx") ?: emptyList()
        val marks = page?.optJSONObject("marks")
        val reqs = page?.arr("req") ?: emptyList()

        // 1 WebView
        out += when {
            p == null -> Stage("webview", "WebView", State.UNKNOWN, "not probed yet")
            p.webView == null || p.webView.pkg.isBlank() -> Stage("webview", "WebView", State.FAIL, "no Android System WebView package is installed or enabled: no page can render")
            else -> Stage("webview", "WebView", State.OK, p.webView.pkg + " " + p.webView.version)
        }

        // 2 Avatar page load
        out += when {
            a.started == 0L -> Stage("page", "Avatar page", State.UNKNOWN, "the Avatar page was not opened in this process yet: open the Avatar tab, then come back")
            a.mainError.isNotEmpty() -> Stage("page", "Avatar page", State.FAIL, "main frame failed: " + a.mainError)
            a.renderGone > 0 -> Stage("page", "Avatar page", State.FAIL, "the WebView render process went away " + a.renderGone + " time(s)")
            a.finished == 0L -> Stage("page", "Avatar page", State.WARN, "still loading (started, not finished)")
            page == null -> Stage("page", "Avatar page", State.FAIL, "page finished but its probe never reported: scripts blocked, or assets/avatar/index.html did not run")
            marks != null && !marks.has("cgc_module") && page.optLong("t") > 4000 ->
                Stage("page", "Avatar page", State.FAIL, "page loaded but the cgc.js / three.js module did not load (import failed): " + firstError(page))
            marks != null && !marks.has("cgc_module") -> Stage("page", "Avatar page", State.WARN, "page loaded, modules still importing")
            else -> Stage("page", "Avatar page", State.OK, "loaded, modules imported" + marks?.optInt("cgc_module", -1)?.takeIf { it >= 0 }?.let { " at $it ms" }.orEmpty())
        }

        // 3 WebGL context
        val okCtx = ctxs.filter { it.optBoolean("ok") }
        val lost = page?.arr("lost") ?: emptyList()
        val lastLost = lost.lastOrNull()?.optString("kind")
        out += when {
            page == null -> Stage("webgl", "WebGL context", State.UNKNOWN, p?.webgl?.let { "probe view: WebGL2 " + (if (it.webgl2) "on" else "OFF") + ", " + (it.renderer ?: "no renderer") } ?: "no page report yet")
            ctxs.isEmpty() && marks?.has("boot_called") == true -> Stage("webgl", "WebGL context", State.FAIL, "three.js never asked for a WebGL context (boot ran, no canvas.getContext('webgl*') call)")
            ctxs.isEmpty() -> Stage("webgl", "WebGL context", State.UNKNOWN, "no context requested yet")
            okCtx.isEmpty() -> Stage("webgl", "WebGL context", State.FAIL, "context creation FAILED (" + ctxs.joinToString { it.optString("type") + ": " + it.optString("err").ifEmpty { "returned null" } } + ")")
            lastLost == "lost" -> Stage("webgl", "WebGL context", State.FAIL, "context LOST and not restored (" + lost.size + " event(s))")
            okCtx.last().optBoolean("software") -> Stage("webgl", "WebGL context", State.WARN, "SOFTWARE fallback (no GPU): " + okCtx.last().optString("renderer"))
            else -> Stage("webgl", "WebGL context", State.OK, okCtx.last().optString("type") + " hardware: " + okCtx.last().optString("renderer"))
        }

        // 4 asset fetch
        val modelAssetFound = p?.assets?.firstOrNull { it.path == p.modelAsset }
        val modelReq = reqs.lastOrNull { isModel(it.optString("url")) }
        out += when {
            p != null && p.modelAsset.isNotEmpty() && modelAssetFound != null && !modelAssetFound.found ->
                Stage("asset", "Asset fetch", State.FAIL, "the model is not in the APK: " + p.modelAsset + " (" + modelAssetFound.note + ")")
            modelReq != null && (modelReq.has("err") || (modelReq.has("status") && !modelReq.optBoolean("ok"))) ->
                Stage("asset", "Asset fetch", State.FAIL, "model fetch failed: " + modelReq.optString("url") + " -> " + modelReq.optString("err").ifEmpty { "HTTP " + modelReq.optInt("status") })
            modelReq != null && modelReq.has("status") && modelReq.optLong("bytes", 1) == 0L ->
                Stage("asset", "Asset fetch", State.FAIL, "model fetched with 0 bytes: " + modelReq.optString("url"))
            modelReq != null && modelReq.has("status") ->
                Stage("asset", "Asset fetch", State.OK, modelReq.optString("url") + " HTTP " + modelReq.optInt("status") + modelReq.optLong("bytes", -1).takeIf { it >= 0 }?.let { ", $it bytes" }.orEmpty())
            a.httpErrors.any { isModel(it) } || a.subErrors.any { isModel(it) } ->
                Stage("asset", "Asset fetch", State.FAIL, (a.httpErrors + a.subErrors).first { isModel(it) })
            modelReq != null -> Stage("asset", "Asset fetch", State.WARN, "model request sent, no answer yet: " + modelReq.optString("url"))
            marks?.has("boot_called") == true -> Stage("asset", "Asset fetch", State.WARN, "boot ran but no model request was seen")
            else -> Stage("asset", "Asset fetch", State.UNKNOWN, "no model request yet")
        }

        // 5 GLB decode
        val g = p?.glb
        out += when {
            g != null && !g.valid -> Stage("decode", "GLB decode", State.FAIL, "the bundled model is not a valid GLB: " + g.error)
            a.cgcError.startsWith("load") -> Stage("decode", "GLB decode", State.FAIL, "three.js rejected the model: " + a.cgcError)
            a.cgcLoaded -> Stage("decode", "GLB decode", State.OK, "cgc.js reported the model loaded" + (g?.let { "; " + it.summary() }.orEmpty()))
            g != null && g.needsDecoder.isNotEmpty() -> Stage("decode", "GLB decode", State.WARN, "model needs " + g.needsDecoder.joinToString() + "; not loaded yet")
            modelReq != null && modelReq.has("status") -> Stage("decode", "GLB decode", State.WARN, "fetched but cgc.js has not reported it loaded (decode pending or stuck)")
            else -> Stage("decode", "GLB decode", State.UNKNOWN, (g?.let { "file header ok: " + it.summary() + "; " } ?: "") + "not decoded yet")
        }

        // 6 canvas surface
        val cv = page?.arr("canvases")?.firstOrNull()
        out += when {
            page == null -> Stage("surface", "Canvas surface", State.UNKNOWN, "no page report yet")
            cv == null -> Stage("surface", "Canvas surface", State.FAIL, "no <canvas> element in the page")
            cv.optInt("w") <= 0 || cv.optInt("h") <= 0 -> Stage("surface", "Canvas surface", State.FAIL, "canvas drawing buffer is " + cv.optInt("w") + "x" + cv.optInt("h"))
            cv.optInt("cw") <= 0 || cv.optInt("ch") <= 0 -> Stage("surface", "Canvas surface", State.FAIL, "canvas CSS box is " + cv.optInt("cw") + "x" + cv.optInt("ch") + " (clipped away)")
            cv.optString("display") == "none" || cv.optString("visibility") == "hidden" || cv.optDouble("opacity", 1.0) == 0.0 ->
                Stage("surface", "Canvas surface", State.FAIL, "canvas is hidden (display " + cv.optString("display") + ", visibility " + cv.optString("visibility") + ", opacity " + cv.optString("opacity") + ")")
            page.optString("vis") != "visible" -> Stage("surface", "Canvas surface", State.WARN, "page visibility is " + page.optString("vis"))
            else -> Stage("surface", "Canvas surface", State.OK, "buffer " + cv.optInt("w") + "x" + cv.optInt("h") + ", box " + cv.optInt("cw") + "x" + cv.optInt("ch") + ", visible")
        }

        // 7 draw
        val draws = page?.optLong("draws", 0) ?: 0
        val frames = page?.optLong("frames", 0) ?: 0
        out += when {
            page == null -> Stage("draw", "Draw", State.UNKNOWN, "no page report yet")
            frames == 0L -> Stage("draw", "Draw", State.FAIL, "requestAnimationFrame never ran (page hidden or throttled, or the loop never started)" + lastErrorSuffix(page))
            draws == 0L -> Stage("draw", "Draw", State.FAIL, "$frames frame(s) ran but no WebGL draw call was issued" + lastErrorSuffix(page))
            else -> Stage("draw", "Draw", State.OK, "$draws draw call(s), $frames frame(s), first draw " + page.optLong("firstDraw", -1) + " ms after page start" + lastErrorSuffix(page))
        }
        return out
    }

    private fun lastErrorSuffix(page: JSONObject): String = page.arr("err").lastOrNull()?.let { "; last JS error: " + it.optString("where") + ": " + it.optString("msg") }.orEmpty()

    private fun firstError(page: JSONObject): String = page.arr("err").firstOrNull()?.let { it.optString("where") + ": " + it.optString("msg") } ?: "no error reported"

    /** One line naming the failed stage (the first FAIL), else the first WARN, else all-OK, else what has not run. */
    fun verdict(stages: List<Stage>): String {
        stages.firstOrNull { it.state == State.FAIL }?.let { return "FAILED at stage \"" + it.label + "\": " + it.detail }
        stages.firstOrNull { it.state == State.WARN }?.let { return "WARNING at stage \"" + it.label + "\": " + it.detail }
        if (stages.all { it.state == State.OK }) return "All 7 stages OK: the model is being drawn."
        val un = stages.firstOrNull { it.state == State.UNKNOWN }
        return "No failure seen; stage \"" + (un?.label ?: "?") + "\" has not run yet: " + (un?.detail ?: "")
    }

    // ── the report: ONE model for the page, the Markdown and the JSON (so the three can never disagree) ───────────────────

    private fun ts(t: Long): String = if (t <= 0L) "never" else java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date(t))

    fun report(
        app: String, version: String, sha: String, capturedAt: String, p: Probe?, a: Avatar, w: List<WorldRec>,
        consoleErrors: List<String>, evs: List<Event>, device: List<Row>,
    ): Report {
        val st = stages(p, a)
        val s = ArrayList<Section>()
        s += Section("stages", "Pipeline stages", st.map { Row(it.label, "[" + it.state.tag + "] " + it.detail) })
        s += Section("device", "Device", device)
        s += Section("webview", "WebView", listOf(
            Row("package", p?.webView?.pkg?.ifEmpty { "none" } ?: "not probed"),
            Row("version", p?.webView?.version ?: "?"),
            Row("version code", p?.webView?.code?.toString() ?: "?"),
        ))
        s += Section("egl", "Native GPU (EGL / GLES, this process)", listOf(
            Row("context created", p?.egl?.ok?.toString() ?: "not probed"),
            Row("renderer", p?.egl?.renderer ?: "?"),
            Row("vendor", p?.egl?.vendor ?: "?"),
            Row("GLES version", p?.egl?.version ?: "?"),
            Row("max texture size", p?.egl?.maxTexture?.takeIf { it > 0 }?.toString() ?: "?"),
            Row("software renderer", p?.egl?.let { isSoftware(it.renderer, it.vendor).toString() } ?: "?"),
            Row("error", p?.egl?.error?.ifEmpty { "none" } ?: "?"),
        ))
        val wg = p?.webgl
        s += Section("webgl_probe", "WebView WebGL (detached probe page)", listOf(
            Row("WebGL2 context created", wg?.webgl2?.toString() ?: "not probed"),
            Row("renderer", wg?.renderer ?: "?"),
            Row("vendor", wg?.vendor ?: "?"),
            Row("software renderer", wg?.let { isSoftware(it.renderer, it.vendor).toString() } ?: "?"),
        ) + (wg?.info?.map { Row("gl " + it.key, it.value) } ?: emptyList()))
        val page = a.page
        val ctxRows = (page?.arr("ctx") ?: emptyList()).mapIndexed { i, c ->
            Row("context #" + (i + 1) + " " + c.optString("type"),
                (if (c.optBoolean("ok")) "created" else "FAILED " + c.optString("err")) + " at " + c.optLong("t") + " ms" +
                    (if (c.has("renderer")) "; renderer " + c.optString("renderer") + "; vendor " + c.optString("vendor") + "; " + c.optString("version") +
                        "; max texture " + c.optInt("maxTex") + "; software " + c.optBoolean("software") else ""))
        }
        val lostRows = (page?.arr("lost") ?: emptyList()).map { Row("context event", ctxEvent(it)) }
        val cv = page?.arr("canvases") ?: emptyList()
        s += Section("avatar", "Avatar page (live, from the page itself)", listOf(
            Row("page started", ts(a.started)), Row("page finished", ts(a.finished)), Row("main-frame error", a.mainError.ifEmpty { "none" }),
            Row("render process gone", a.renderGone.toString()),
            Row("last page report", if (a.pageAt > 0) ts(a.pageAt) else "never"),
            Row("page uptime (ms)", page?.optLong("t")?.toString() ?: "?"),
            Row("user agent", page?.optString("ua")?.ifEmpty { "?" } ?: "?"),
            Row("devicePixelRatio", page?.optDouble("dpr")?.toString() ?: "?"),
            Row("viewport", page?.optJSONArray("inner")?.let { it.optInt(0).toString() + "x" + it.optInt(1) } ?: "?"),
            Row("visibility", page?.optString("vis")?.ifEmpty { "?" } ?: "?"),
            Row("frames drawn (rAF)", page?.optLong("frames")?.toString() ?: "?"),
            Row("draw calls", page?.optLong("draws")?.toString() ?: "?"),
            Row("first frame (ms)", page?.optLong("firstRaf", -1)?.takeIf { it >= 0 }?.toString() ?: "none"),
            Row("first draw (ms)", page?.optLong("firstDraw", -1)?.takeIf { it >= 0 }?.toString() ?: "none"),
            Row("marks", page?.optJSONObject("marks")?.let { m -> m.keys().asSequence().joinToString { it + "@" + m.optInt(it) } }?.ifEmpty { "none" } ?: "?"),
        ) + ctxRows + lostRows + cv.mapIndexed { i, c ->
            Row("canvas #" + (i + 1), "buffer " + c.optInt("w") + "x" + c.optInt("h") + ", box " + c.optInt("cw") + "x" + c.optInt("ch") +
                ", rect " + (c.optJSONArray("rect")?.let { r -> (0 until r.length()).joinToString(",") { r.optInt(it).toString() } } ?: "?") +
                ", display " + c.optString("display") + ", visibility " + c.optString("visibility") + ", opacity " + c.optString("opacity") + ", in DOM " + c.optBoolean("inDom"))
        })
        val assetRows = (p?.assets ?: emptyList()).map {
            Row(it.path, (if (it.found) "found, " + it.bytes + " bytes" else "NOT FOUND") + (if (it.note.isNotEmpty()) "; " + it.note else ""))
        }
        val g = p?.glb
        s += Section("assets", "Bundled assets (read from the APK)", listOf(
            Row("asset base URL", p?.assetUrl ?: "?"),
            Row("model URL (declared)", p?.modelUrl?.ifEmpty { "none declared" } ?: "?"),
            Row("model asset path (resolved)", p?.modelAsset?.ifEmpty { "?" } ?: "?"),
        ) + assetRows + listOf(
            Row("GLB header", g?.let { if (it.valid) "valid: " + it.summary() else "INVALID: " + it.error } ?: "not checked"),
            Row("GLB extensions required", g?.extensionsRequired?.joinToString()?.ifEmpty { "none" } ?: "?"),
            Row("GLB extensions used", g?.extensionsUsed?.joinToString()?.ifEmpty { "none" } ?: "?"),
        ))
        val reqRows = (page?.arr("req") ?: emptyList()).map { r ->
            Row(r.optString("via") + " " + r.optString("url").takeLast(90),
                (if (r.has("err")) "ERROR " + r.optString("err") else if (r.has("status")) "HTTP " + r.optInt("status") + (if (r.has("bytes")) ", " + r.optLong("bytes") + " bytes" else if (r.has("len")) ", content-length " + r.optString("len") else "") + (if (r.has("magic")) ", magic " + r.optString("magic") else "")
                else "pending") + " (" + r.optLong("t") + " ms" + (if (r.has("done")) " -> " + r.optLong("done") + " ms" else "") + ")")
        }
        s += Section("requests", "Page requests (fetch, as three.js made them)", reqRows.ifEmpty { listOf(Row("requests", "none seen")) })
        val jsErr = (page?.arr("err") ?: emptyList()).map { Row("js " + it.optString("where") + " @" + it.optLong("t") + " ms", it.optString("msg")) }
        val con = (page?.arr("con") ?: emptyList()).map { Row("console " + it.optString("lvl") + " @" + it.optLong("t") + " ms", it.optString("msg")) }
        s += Section("errors", "Errors and console", (jsErr + con + consoleErrors.map { Row("webview console", it) } +
            a.subErrors.map { Row("subresource error", it) } + a.httpErrors.map { Row("http error", it) }).ifEmpty { listOf(Row("errors", "none")) })
        s += Section("cgc", "cgc.js bridge events (ready, loaded, size, error)", listOf(
            Row("ready", a.cgcReady.toString()), Row("model loaded", a.cgcLoaded.toString()), Row("load error", a.cgcError.ifEmpty { "none" }),
        ) + a.cgc.takeLast(15).map { Row("event", it) })
        val wRows = w.flatMap { r ->
            listOf(
                Row("world " + r.id, r.url + "; started " + ts(r.started) + ", finished " + ts(r.finished) + ", progress " + r.progress + "%"),
                Row("world " + r.id + " main-frame error", r.mainError.ifEmpty { "none" }),
                Row("world " + r.id + " render process gone", r.renderGone.toString()),
                Row("world " + r.id + " page diagnostics", r.diag.ifEmpty { "not measured" }),
                Row("world " + r.id + " render check", r.render.ifEmpty { "not measured" }),
            ) + r.httpErrors.map { Row("world " + r.id + " http error", it) } + r.subErrors.map { Row("world " + r.id + " subresource error", it) }
        }
        s += Section("worlds", "Galaxy worlds (WebView callbacks)", wRows.ifEmpty { listOf(Row("worlds", "no world opened in this process yet")) })
        s += Section("timeline", "Timeline", evs.takeLast(40).map { Row(ts(it.t) + " " + it.src, it.msg) }.ifEmpty { listOf(Row("events", "none")) })
        return Report(app, version, sha, capturedAt, st, verdict(st), s)
    }

    private fun ctxEvent(o: JSONObject): String = o.optString("kind") + " at " + o.optLong("t") + " ms"

    fun markdown(r: Report): String = buildString {
        append("# X-Ray App (3D render) - ").append(r.app).append('\n')
        append("- version: `").append(r.version).append("` build `").append(r.sha.ifBlank { "local" }).append("`\n")
        append("- captured: ").append(r.capturedAt).append('\n')
        append("- verdict: ").append(r.verdict).append("\n\n")
        r.sections.forEachIndexed { i, s ->
            if (i > 0) append("\n\n")
            append("## ").append(s.title).append('\n')
            append(s.rows.joinToString("\n") { "- " + it.key + ": " + it.value })
        }
        append('\n')
    }

    fun json(r: Report): String = JSONObject()
        .put("app", r.app).put("version", r.version).put("sha", r.sha).put("captured", r.capturedAt).put("verdict", r.verdict)
        .put("stages", JSONArray(r.stages.map { JSONObject().put("id", it.id).put("label", it.label).put("state", it.state.name).put("detail", it.detail) }))
        .put("sections", JSONArray(r.sections.map { s ->
            JSONObject().put("id", s.id).put("title", s.title)
                .put("rows", JSONArray(s.rows.map { JSONObject().put("key", it.key).put("value", it.value) }))
        })).toString(2)
}

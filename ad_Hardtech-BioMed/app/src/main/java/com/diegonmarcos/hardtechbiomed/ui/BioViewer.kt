package com.diegonmarcos.hardtechbiomed.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import com.diegonmarcos.cloudlib.web.CloudWeb
import com.diegonmarcos.cloudlib.web.CloudWebClient
import com.diegonmarcos.cloudlib.web.WebProfile
import com.diegonmarcos.hardtechbiomed.BioParse
import com.diegonmarcos.hardtechbiomed.BuildConfig
import com.diegonmarcos.hardtechbiomed.ViewerHealth
import com.diegonmarcos.superapp.cad.ModelCache
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume

private val VIEWER_URL = CloudWeb.assetUrl("cad/viewer.html")
private const val MODELS_PATH = "/models/"
/** The viewer's fixed height: the WebView fills it, so its surface is never a collapsed (zero-height) one. */
private const val VIEWER_HEIGHT_DP = 340
private const val SCREEN_STRIP_DP = 28
private const val SCREEN_INSET_DP = 4
private const val LOAD_TIMEOUT_S = 40
private const val PREFS = "biomed_viewer"
private const val PREF_PRESENT = "present"
private const val PREF_BUILD = "present_build"

private data class Paint(val pct: Double, val w: Int, val h: Int, val gl: String)

/** What a pick on the stage reports: the node's name, its label and its body system (the glTF extras the model carries). */
data class Picked(val name: String, val label: String, val system: String)

/**
 * The handle of one viewer: the scene drives it (highlight, focus, layers, camera) once [shown]; the viewer fills [web] and flips [shown].
 * Every call is a page function of libs:cad-core's viewer (assets/cad/viewer.js), run on the main thread.
 */
@Stable
class BioViewerController {
    internal var web: WebView? = null
    var shown by mutableStateOf(false)
        internal set
    /** The last state line of the viewer, for the screen under it. */
    var status by mutableStateOf("")
        internal set
    /** Bumped by every highlight: the viewer then writes a canvas snapshot of the new state (the emulator proof pulls it). */
    var snapKey by mutableStateOf(0)
        private set
    private fun run(js: String) { web?.post { web?.evaluateJavascript(js, null) } }
    fun highlight(spec: List<String>, isolate: Boolean = false) { snapKey++; run(BioParse.highlightJs(spec, isolate)) }
    fun focus(spec: List<String>) = run(BioParse.focusJs(spec))
    fun show(spec: List<String>, on: Boolean) = run(BioParse.showJs(spec, on))
    fun fit() = run("window.cadFit&&window.cadFit()")
    fun reset() = run("window.cadReset&&window.cadReset()")
    fun view(name: String) = run("window.cadView&&window.cadView('$name')")
    fun wire(on: Boolean) = run("window.cadWireframe&&window.cadWireframe($on)")
}

private fun activityOf(c: Context): Activity? {
    var x = c
    while (x is ContextWrapper) { if (x is Activity) return x; x = x.baseContext }
    return null
}

/** The share of the viewer's rectangle ON THE SCREEN that is not background (a PixelCopy of the window); -1 when it cannot be measured. */
private suspend fun screenPct(wv: WebView): Double {
    val window = activityOf(wv.context)?.window ?: return -1.0
    val vis = Rect()
    if (!wv.isShown || !ViewerHealth.sized(wv.width, wv.height) || !wv.getGlobalVisibleRect(vis) || vis.width() != wv.width || vis.height() != wv.height) return -1.0
    val loc = IntArray(2).also { wv.getLocationInWindow(it) }
    val d = wv.resources.displayMetrics.density
    val inset = (SCREEN_INSET_DP * d).toInt()
    val src = Rect(loc[0] + inset, loc[1] + inset, loc[0] + wv.width - inset, loc[1] + wv.height - (SCREEN_STRIP_DP * d).toInt())
    if (!ViewerHealth.sized(src.width() / 4, src.height() / 4)) return -1.0
    val bmp = Bitmap.createBitmap(src.width() / 4, src.height() / 4, Bitmap.Config.ARGB_8888)
    return suspendCancellableCoroutine { k ->
        runCatching {
            PixelCopy.request(window, src, bmp, { r ->
                val pct = if (r == PixelCopy.SUCCESS) ViewerHealth.coverage(IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }) else -1.0
                bmp.recycle()
                if (k.isActive) k.resume(Math.round(pct * 10) / 10.0)
            }, Handler(Looper.getMainLooper()))
        }.onFailure { if (k.isActive) k.resume(-1.0) }
    }
}

private sealed class ViewState {
    data object Fetching : ViewState()
    data object Loading : ViewState()
    data object Shown : ViewState()
    data class Failed(val why: String) : ViewState()
}

/**
 * The 3D stage of one model: libs:cad-core's viewer page (three.js) in a WebView, the model fetched ONCE at run time into [dir] from [BuildConfig.MODELS_BASE]
 * (verified by its sha256; never baked into the APK) and drawn by libs:cad-mesh. The WebView presentation fallback of Hardtech-CAD (#1658/#1664) is the
 * same here: a model painted but not on the screen steps to the 2D canvas, then to an app-drawn frame. [onPick] gets the part under a tap.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BioViewer(
    controller: BioViewerController, name: String, path: String, sha256: String, dir: File, modifier: Modifier = Modifier,
    onPick: (Picked?) -> Unit = {}, onShown: () -> Unit = {},
) {
    val p = LocalKitPalette.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    val build = BuildConfig.VERSION_CODE.toString() + "-" + BuildConfig.GIT_SHORT_SHA
    var state by remember(path) { mutableStateOf<ViewState>(ViewState.Fetching) }
    var ready by remember { mutableStateOf(false) }
    var web by remember { mutableStateOf<WebView?>(null) }
    var file by remember(path) { mutableStateOf<File?>(null) }
    var triangles by remember(path) { mutableStateOf(-1L) }
    var paint by remember(path) { mutableStateOf<Paint?>(null) }
    var screen by remember(path) { mutableStateOf(-1.0) }
    var stage by remember(path) { mutableStateOf("") }
    var progress by remember(path) { mutableStateOf("") }
    var lastProgress by remember(path) { mutableStateOf(0L) }
    var present by remember { mutableStateOf(ViewerHealth.start(prefs.getString(PREF_PRESENT, null), prefs.getString(PREF_BUILD, null), build)) }
    var lastGesture by remember { mutableStateOf(0L) }
    var hostFrame by remember { mutableStateOf<ImageBitmap?>(null) }
    val onEvent by rememberUpdatedState { e: JSONObject, _: String ->
        when (e.optString("type")) {
            "ready" -> {
                ready = true
                val pr = present
                if (pr != ViewerHealth.Present.WEBGL) web?.let { w -> w.post { w.evaluateJavascript("window.cadPresent&&window.cadPresent('${pr.js}')", null) } }
                Unit
            }
            "shown" -> { state = ViewState.Shown; triangles = e.optLong("triangles", -1); controller.shown = true; onShown() }
            "painted" -> paint = Paint(e.optDouble("pct", -1.0), e.optInt("w"), e.optInt("h"), e.optString("gl"))
            "progress" -> { progress = e.optString("text"); lastProgress = System.currentTimeMillis() }
            "error" -> state = ViewState.Failed(e.optString("message"))
            else -> Unit
        }
        Unit
    }
    val bridge = remember {
        object {
            @JavascriptInterface
            fun post(json: String) {
                android.util.Log.i("BioHost", json)
                val e = runCatching { JSONObject(json) }.getOrNull() ?: return
                onEvent(e, json)
            }
        }
    }
    fun pollStage() {
        val w = web ?: return
        w.evaluateJavascript("JSON.stringify(window.cadDiag&&window.cadDiag())") { v -> decodeJs(v)?.let { stage = runCatching { JSONObject(it).optString("stage") }.getOrDefault("") } }
    }
    LaunchedEffect(path) {
        controller.shown = false
        file = runCatching { withContext(Dispatchers.IO) { ModelCache.fetch(BuildConfig.MODELS_BASE, dir, path, "glb", sha256) } }
            .onFailure { state = ViewState.Failed(it.message ?: it.javaClass.simpleName) }.getOrNull()
        if (file != null) state = ViewState.Loading
    }
    LaunchedEffect(ready, file) {
        val f = file ?: return@LaunchedEffect
        if (!ready) return@LaunchedEffect
        val arg = JSONObject().put("lib", "mesh").put("url", MODELS_PATH + f.name).put("format", "glb")
        web?.evaluateJavascript("window.cadOpen&&window.cadOpen($arg)", null)
        kotlinx.coroutines.delay(20000)
        // The proof pulls this PNG (cache/cad-snapshot-<id>.png) with run-as: a WebView screenshot does not capture WebGL on an emulator.
        web?.evaluateJavascript("window.cadSnapshot&&window.cadSnapshot()") { v ->
            runCatching {
                val b64 = v.orEmpty().trim('"').substringAfter("base64,", "")
                if (b64.isNotEmpty()) File(dir.parentFile, "cad-snapshot-" + name + ".png").writeBytes(android.util.Base64.decode(b64, android.util.Base64.DEFAULT))
            }
        }
        kotlinx.coroutines.delay((LOAD_TIMEOUT_S - 20) * 1000L)
        while (state == ViewState.Loading && System.currentTimeMillis() - lastProgress < LOAD_TIMEOUT_S * 1000L) kotlinx.coroutines.delay(1000)
        if (state == ViewState.Loading) state = ViewState.Failed("still loading after $LOAD_TIMEOUT_S s" + if (stage.isNotBlank()) " (viewer stage: $stage)" else "")
    }
    LaunchedEffect(path, state) { while (state == ViewState.Loading) { kotlinx.coroutines.delay(1000); pollStage() } }
    // the canvas after a highlight (a topic, a pick, or cleared): written next to the first snapshot as cad-snapshot-<model>-topic.png
    LaunchedEffect(controller.snapKey) {
        if (controller.snapKey == 0 || !controller.shown) return@LaunchedEffect
        kotlinx.coroutines.delay(3000)
        web?.evaluateJavascript("window.cadSnapshot&&window.cadSnapshot()") { v ->
            runCatching {
                val b64 = v.orEmpty().trim('"').substringAfter("base64,", "")
                if (b64.isNotEmpty()) File(dir.parentFile, "cad-snapshot-" + name + "-topic.png").writeBytes(android.util.Base64.decode(b64, android.util.Base64.DEFAULT))
            }
        }
    }
    // the page painted the model into its framebuffer: now measure what the SCREEN shows of it, and step to the next presentation when it shows none
    LaunchedEffect(path, paint) {
        val pt = paint ?: return@LaunchedEffect
        var cur = present
        while (true) {
            kotlinx.coroutines.delay(450)
            val w = web ?: return@LaunchedEffect
            var s = screenPct(w)
            if (ViewerHealth.lost(pt.pct, s)) { kotlinx.coroutines.delay(700); s = screenPct(w) }
            screen = s
            val n = ViewerHealth.next(cur, pt.pct, s)
            if (n == null) {
                if (ViewerHealth.lost(pt.pct, s)) state = ViewState.Failed("the model is rendered (${pt.pct}% of the ${pt.w}x${pt.h} canvas) but nothing of it reaches the screen, through any presentation")
                break
            }
            cur = n; present = n
            prefs.edit().putString(PREF_PRESENT, n.js).putString(PREF_BUILD, build).apply()
            w.evaluateJavascript("window.cadPresent&&window.cadPresent('${n.js}')", null)
        }
    }
    LaunchedEffect(present, web) {
        if (present != ViewerHealth.Present.HOST) { hostFrame = null; return@LaunchedEffect }
        var last = -1
        while (true) {
            web?.evaluateJavascript("window.cadFrameShot&&window.cadFrameShot($last)") { v ->
                val shot = decodeJs(v) ?: return@evaluateJavascript
                runCatching {
                    val bytes = android.util.Base64.decode(shot.substringAfter("base64,"), android.util.Base64.DEFAULT)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { hostFrame = it.asImageBitmap(); last = shot.substringBefore('|').toInt() }
                }
            }
            kotlinx.coroutines.delay(ViewerHealth.pullDelayMs(System.currentTimeMillis() - lastGesture))
        }
    }
    LaunchedEffect(path) {
        kotlinx.coroutines.delay(10000)
        if (!ready && state !is ViewState.Shown) state = ViewState.Failed("the viewer page did not become ready in 10 s")
    }
    DisposableEffect(Unit) { onDispose { controller.web = null; controller.shown = false; web?.destroy(); web = null } }
    val line = when (val s = state) {
        ViewState.Shown -> ViewerHealth.line("shown", stage, present, triangles, paint?.w ?: 0, paint?.h ?: 0, paint?.pct ?: -1.0, screen, paint?.gl.orEmpty())
        is ViewState.Failed -> ViewerHealth.line("failed", stage, present, triangles, 0, 0, -1.0, screen, "", s.why)
        else -> ViewerHealth.line("loading", stage, present, triangles, 0, 0, -1.0, screen, "")
    }
    androidx.compose.runtime.SideEffect { controller.status = line }

    Box(modifier.fillMaxWidth().height(VIEWER_HEIGHT_DP.dp).background(Color(0xFF0E1116)).border(1.dp, p.hairline, RoundedCornerShape(14.dp)).testTag("viewer")) {
        AndroidView(
            factory = { c ->
                val loader = WebViewAssetLoader.Builder()
                    .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(c))
                    .addPathHandler(MODELS_PATH, WebViewAssetLoader.InternalStoragePathHandler(c, dir.apply { mkdirs() }))
                    .build()
                CloudWeb.create(c, WebProfile.WEB_WORLD).also { wv ->
                    wv.addJavascriptInterface(bridge, "CadHost")
                    wv.webViewClient = object : CloudWebClient(WebProfile.WEB_WORLD, loader) {
                        override fun onRecovered(dead: WebView, fresh: WebView?) {
                            fresh?.addJavascriptInterface(bridge, "CadHost"); web = fresh; controller.web = fresh; ready = false; present = ViewerHealth.Present.WEBGL
                        }
                    }
                    wv.loadUrl(VIEWER_URL)
                    web = wv; controller.web = wv
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        hostFrame?.let { Image(it, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize().testTag("viewer_host_frame")) }
        // THE touch surface (as Hardtech-CAD's): Compose takes every gesture and forwards it to the page's camera. One finger orbits, a pinch
        // zooms, two fingers pan, a double tap fits, a single tap picks the part under the finger.
        Box(Modifier.fillMaxSize().testTag("viewer_touch")
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { web?.evaluateJavascript("window.cadFit&&window.cadFit()", null) },
                    onTap = { o ->
                        val w = size.width.coerceAtLeast(1); val h = size.height.coerceAtLeast(1)
                        web?.evaluateJavascript("window.cadPick&&window.cadPick(${o.x / w},${o.y / h})") { v ->
                            val j = decodeJs(v)?.let { runCatching { JSONObject(it) }.getOrNull() }
                            onPick(j?.let { Picked(it.optString("name"), it.optString("label"), it.optString("system")) })
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                val d = density
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var last: Offset? = null
                    var lastCount = 0
                    do {
                        val ev = awaitPointerEvent()
                        val down = ev.changes.filter { it.pressed }
                        val c = down.size
                        val moved = ev.changes.any { it.positionChange() != Offset.Zero }
                        if (c > 0 && moved) {
                            val centroid = ev.calculateCentroid(useCurrent = true)
                            val delta = if (c == 1) down[0].positionChange() else if (last != null && lastCount == c) centroid - last!! else Offset.Zero
                            val zoom = if (c >= 2) ev.calculateZoom() else 1f
                            val calls = ViewerHealth.gesture(c, delta.x / d, delta.y / d, zoom)
                            if (calls.isNotEmpty()) { web?.evaluateJavascript(calls.joinToString(";"), null); lastGesture = System.currentTimeMillis() }
                            ev.changes.forEach { if (it.positionChange() != Offset.Zero) it.consume() }
                        }
                        last = if (c > 0) ev.calculateCentroid(useCurrent = true) else null
                        lastCount = c
                    } while (ev.changes.any { it.pressed })
                }
            })
        val overlay = when (val s = state) {
            ViewState.Fetching -> "Fetching $name…"
            ViewState.Loading -> progress.ifBlank { "Loading $name…" }
            ViewState.Shown -> null
            is ViewState.Failed -> "Could not show this model: ${s.why}"
        }
        if (overlay != null) Text(overlay, color = if (state is ViewState.Failed) p.warn else p.textSecondary, fontSize = BioType.body, textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center).padding(20.dp).testTag("viewer_status"))
    }
}

/** The string a WebView's evaluateJavascript hands back for a JS string result: a JSON string literal; null for "null" / blank. */
internal fun decodeJs(v: String?): String? {
    if (v.isNullOrBlank() || v == "null") return null
    return runCatching { org.json.JSONTokener(v).nextValue() as? String }.getOrNull()?.takeIf { it.isNotBlank() && it != "null" }
}

@Composable
internal fun BioRenderLine(controller: BioViewerController) {
    val p = LocalKitPalette.current
    Text(controller.status, color = p.textSecondary.copy(alpha = 0.75f), fontSize = BioType.caption, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("viewer_render"))
}

package com.diegonmarcos.hardtechcad.ui

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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import com.diegonmarcos.cloudlib.web.CloudWeb
import com.diegonmarcos.cloudlib.web.CloudWebClient
import com.diegonmarcos.cloudlib.web.WebProfile
import com.diegonmarcos.hardtechcad.BuildConfig
import com.diegonmarcos.hardtechcad.CadDiag
import com.diegonmarcos.hardtechcad.R
import com.diegonmarcos.hardtechcad.ViewerHealth
import com.diegonmarcos.superapp.cad.CadFormats
import com.diegonmarcos.superapp.cad.Model
import com.diegonmarcos.superapp.cad.ModelCache
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import org.json.JSONObject
import java.io.File

private val VIEWER_URL = CloudWeb.assetUrl("cad/viewer.html")
private const val MODELS_PATH = "/models/"
private const val ASSETS_PATH = "/assets/"
/** #1658 the viewer's fixed height: the WebView fills it, so its surface is never a collapsed (zero-height) one. */
private const val VIEWER_HEIGHT_DP = 300
/** #1658 the on-screen measurement leaves out the page's own status line (bottom) and the frame's border (edges). */
private const val SCREEN_STRIP_DP = 28
private const val SCREEN_INSET_DP = 4
/** #1658 a model that neither shows nor fails within this long after cadOpen is reported, with the page's stage. */
private const val LOAD_TIMEOUT_S = 30
/** #1664 where the presentation the viewer had to step to is remembered, with the build it was measured on. */
private const val PREFS = "cad_viewer"
private const val PREF_PRESENT = "present"
private const val PREF_BUILD = "present_build"

/** #1658 what the page's 'painted' event read back from the WebGL framebuffer; [seq] makes every event a new value. */
private data class Paint(val pct: Double, val w: Int, val h: Int, val gl: String, val seq: Long)

private fun activityOf(c: Context): Activity? {
    var x = c
    while (x is ContextWrapper) { if (x is Activity) return x; x = x.baseContext }
    return null
}

/**
 * #1658 the share of the viewer's rectangle ON THE SCREEN that is not background: a PixelCopy of the window, which holds what the compositor
 * really presented (the WebView's GL layer included). -1 when it cannot be measured (no window, the viewer not fully visible, the copy failed).
 */
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
    data class StoreOnly(val reason: String) : ViewState()
}

/** The viewer: plans [m] through libs:cad-core (derived view, original, or store-only), fetches it once into [dir], shows it. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun ModelViewer(m: Model, dir: File, height: Dp = VIEWER_HEIGHT_DP.dp, full: Boolean = false, onFull: (() -> Unit)? = null) {
    val p = LocalKitPalette.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // #1664 a fallback presentation is remembered for THIS build only: an update starts again on the GPU path (ViewerHealth.start)
    val prefs = remember { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    val build = BuildConfig.VERSION_CODE.toString() + "-" + BuildConfig.GIT_SHORT_SHA
    val plan = remember(m) { CadFormats.plan(m) }
    var state by remember(m) { mutableStateOf<ViewState>(if (plan is CadFormats.Plan.StoreOnly) ViewState.StoreOnly(plan.reason) else ViewState.Fetching) }
    var ready by remember { mutableStateOf(false) }
    var web by remember { mutableStateOf<WebView?>(null) }
    var file by remember(m) { mutableStateOf<File?>(null) }
    // #1658 what the page reported for THIS model and what the screen really shows of it; the presentation belongs to the page (the WebView), not the model
    var triangles by remember(m) { mutableStateOf(-1L) }
    var paint by remember(m) { mutableStateOf<Paint?>(null) }
    var screen by remember(m) { mutableStateOf(-1.0) }
    var stage by remember(m) { mutableStateOf("") }
    var present by remember { mutableStateOf(ViewerHealth.start(prefs.getString(PREF_PRESENT, null), prefs.getString(PREF_BUILD, null), build)) }
    var lastGesture by remember { mutableStateOf(0L) }
    var hostFrame by remember { mutableStateOf<ImageBitmap?>(null) }
    // #1631 every event of the real viewer page is recorded for the X-Ray App page. #1658 the handler is the CURRENT composition's: the bridge object
    // lives as long as the WebView, and one that wrote the first model's state left every later model without its shown / error state.
    val onEvent by rememberUpdatedState { e: JSONObject, json: String ->
        when (e.optString("type")) {
            "ready" -> {
                ready = true; CadDiag.event(m.id, "viewer ready")
                // the bridge runs on the JavaBridge thread: the WebView is driven from the main thread
                val pr = present
                if (pr != ViewerHealth.Present.WEBGL) web?.let { w -> w.post { w.evaluateJavascript("window.cadPresent&&window.cadPresent('${pr.js}')", null) } }
                Unit
            }
            "shown" -> { state = ViewState.Shown; triangles = e.optLong("triangles", -1); CadDiag.shown(m.id, e.optLong("triangles", -1)) }
            "painted" -> {
                paint = Paint(e.optDouble("pct", -1.0), e.optInt("w"), e.optInt("h"), e.optString("gl"), System.nanoTime())
                CadDiag.painted(m.id, e.optDouble("pct", -1.0), e.optInt("w"), e.optInt("h"), e.optString("gl"))
            }
            "error" -> { state = ViewState.Failed(e.optString("message")); CadDiag.failed(m.id, e.optString("message")) }
            else -> CadDiag.event(m.id, json)
        }
    }
    val bridge = remember {
        object {
            @JavascriptInterface
            fun post(json: String) {
                android.util.Log.i("CadHost", json)
                val e = runCatching { JSONObject(json) }.getOrNull() ?: return
                onEvent(e, json)
            }
        }
    }
    // #1631 read the viewer page's own diagnostics (window.cadDiag) and the WebView's real surface, into the X-Ray record
    fun pollDiag(id: String) {
        val w = web ?: return
        CadDiag.view(id, w.width, w.height, w.isShown, w.isHardwareAccelerated)
        w.evaluateJavascript("JSON.stringify(window.cadDiag&&window.cadDiag())") { v ->
            decodeJs(v)?.let { CadDiag.js(id, it); stage = runCatching { JSONObject(it).optString("stage") }.getOrDefault("") }
        }
    }
    // #1542 a view bundled in the APK (Plan.Show.asset) is drawn from the app's own assets: no download, no LFS, nothing to fail on the network
    val bundled = (plan as? CadFormats.Plan.Show)?.asset.orEmpty()
    LaunchedEffect(m) {
        val show = plan as? CadFormats.Plan.Show ?: return@LaunchedEffect
        if (bundled.isNotEmpty()) { state = ViewState.Loading; return@LaunchedEffect }
        file = runCatching { withContext(Dispatchers.IO) { ModelCache.fetch(BuildConfig.MODELS_BASE, dir, show.path, show.format, show.sha256) } }
            .onFailure { state = ViewState.Failed(it.message ?: it.javaClass.simpleName); CadDiag.begin(m.id, m.name, show.lib, show.format, MODELS_PATH + show.path); CadDiag.failed(m.id, "fetch: " + (it.message ?: it.javaClass.simpleName)) }.getOrNull()
        if (file != null) state = ViewState.Loading
    }
    LaunchedEffect(ready, file, bundled) {
        val show = plan as? CadFormats.Plan.Show ?: return@LaunchedEffect
        val f = file
        if (bundled.isEmpty() && f == null) return@LaunchedEffect
        if (!ready) return@LaunchedEffect
        val url = if (bundled.isNotEmpty()) ASSETS_PATH + bundled else MODELS_PATH + f!!.name
        val arg = JSONObject().put("lib", show.lib).put("url", url).put("format", show.format)
        CadDiag.begin(m.id, m.name, show.lib, show.format, url)
        web?.evaluateJavascript("window.cadOpen&&window.cadOpen($arg)") { r -> if (r == "null") CadDiag.event(m.id, "window.cadOpen is not defined: the viewer module did not run") }
        android.util.Log.i("CadHost", "cadOpen " + arg)
        kotlinx.coroutines.delay(2500); pollDiag(m.id)
        kotlinx.coroutines.delay(17500); pollDiag(m.id)
        web?.evaluateJavascript("document.getElementById('s')&&document.getElementById('s').textContent") { android.util.Log.i("CadHost", "status=" + it); CadDiag.event(m.id, "page status " + it) }
        // The proof pulls this PNG (cache/cad-snapshot-<id>.png) with run-as: a WebView screenshot does not capture WebGL on an emulator.
        web?.evaluateJavascript("window.cadSnapshot&&window.cadSnapshot()") { v ->
            runCatching {
                val b64 = v.orEmpty().trim('"').substringAfter("base64,", "")
                if (b64.isNotEmpty()) File(dir.parentFile, "cad-snapshot-" + m.id + ".png").writeBytes(android.util.Base64.decode(b64, android.util.Base64.DEFAULT))
            }
        }
        // #1658 a load that neither shows nor fails is not left as a silent empty stage: it is reported with the stage the page stopped at
        kotlinx.coroutines.delay((LOAD_TIMEOUT_S - 20) * 1000L)
        if (state == ViewState.Loading) {
            val msg = "still loading after $LOAD_TIMEOUT_S s" + (if (stage.isNotBlank()) " (viewer stage: $stage)" else "")
            CadDiag.failed(m.id, msg); state = ViewState.Failed(msg)
        }
    }
    // #1658 while a model loads, the page's own stage is read every second: the line under the viewer says where the load is
    LaunchedEffect(m, state) { while (state == ViewState.Loading) { kotlinx.coroutines.delay(1000); pollDiag(m.id) } }
    // #1658 the page painted the model into its framebuffer: now measure what the SCREEN shows of it. A model that is painted and not on the screen
    // is a WebView presentation failure the page cannot see; the viewer steps to the next presentation (2D canvas, then an image the app draws)
    // and measures again. A model no presentation brings to the screen is reported, never left as a silent empty stage.
    LaunchedEffect(m, paint) {
        val pt = paint ?: return@LaunchedEffect
        var p = present
        while (true) {
            kotlinx.coroutines.delay(450)
            val w = web ?: return@LaunchedEffect
            var s = screenPct(w)
            if (ViewerHealth.lost(pt.pct, s)) { kotlinx.coroutines.delay(700); s = screenPct(w) }
            screen = s; CadDiag.screen(m.id, s, p.js)
            val n = ViewerHealth.next(p, pt.pct, s)
            if (n == null) {
                if (ViewerHealth.lost(pt.pct, s)) {
                    val msg = "the model is rendered (${pt.pct}% of the ${pt.w}x${pt.h} canvas) but nothing of it reaches the screen, through any presentation"
                    CadDiag.failed(m.id, msg); state = ViewState.Failed(msg)
                }
                break
            }
            p = n; present = n
            prefs.edit().putString(PREF_PRESENT, n.js).putString(PREF_BUILD, build).apply()
            w.evaluateJavascript("window.cadPresent&&window.cadPresent('${n.js}')", null)
        }
    }
    // #1658 the last presentation: the app pulls each new frame of the page (a JPEG of the WebGL framebuffer) and draws it over the WebView itself
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
    // #1631 a viewer page that never becomes ready (its module died: three.js missing, no WebGL context) used to leave a silent spinner; say so, with the page's own error
    LaunchedEffect(m) {
        val show = plan as? CadFormats.Plan.Show ?: return@LaunchedEffect
        kotlinx.coroutines.delay(8000)
        if (ready) return@LaunchedEffect
        if (CadDiag.snapshot().none { it.id == m.id }) CadDiag.begin(m.id, m.name, show.lib, show.format, "(not opened: the viewer was never ready)")
        pollDiag(m.id)
        val why = CadDiag.snapshot().firstOrNull { it.id == m.id }?.js?.let { runCatching { JSONObject(it).optString("lastError") }.getOrNull() }.orEmpty()
        val msg = "the viewer page did not become ready in 8 s" + if (why.isNotBlank()) ": $why" else " (see Configs > X-Ray App)"
        CadDiag.failed(m.id, msg)
        if (state !is ViewState.Shown) state = ViewState.Failed(msg)
    }
    DisposableEffect(Unit) { onDispose { web?.destroy(); web = null } }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // #1560 no rounded clip around the WebView: the page's GL surface is drawn by the WebView's hardware functor, which a non-rectangular
        // Compose clip layer can drop (the only fleet WebGL world inside one; CG-Omniverse and Cloud Nav draw it unclipped). Rounded border only.
        Box(Modifier.fillMaxWidth().height(height.coerceAtLeast(VIEWER_HEIGHT_DP.dp)).background(Color(0xFF0E1116)).border(1.dp, p.hairline, RoundedCornerShape(CadType.corner)).testTag("viewer")) {
            if (plan is CadFormats.Plan.Show) AndroidView(
                factory = { c ->
                    val loader = WebViewAssetLoader.Builder()
                        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(c))
                        .addPathHandler(MODELS_PATH, WebViewAssetLoader.InternalStoragePathHandler(c, dir.apply { mkdirs() }))
                        .build()
                    CloudWeb.create(c, WebProfile.WEB_WORLD).also { wv ->
                        wv.addJavascriptInterface(bridge, "CadHost")
                        wv.webViewClient = object : CloudWebClient(WebProfile.WEB_WORLD, loader) {
                            override fun onRecovered(dead: WebView, fresh: WebView?) {
                                fresh?.addJavascriptInterface(bridge, "CadHost"); web = fresh; ready = false; present = ViewerHealth.Present.WEBGL
                            }
                        }
                        wv.loadUrl(VIEWER_URL)
                        web = wv
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            hostFrame?.let { Image(it, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize().testTag("viewer_host_frame")) }
            // #1664 THE touch surface of the viewer, over the WebView and over the app-drawn frame alike: Compose takes every gesture here (so no parent
            // scroll steals it and no presentation depends on touch reaching the WebView) and forwards it to the page's camera, which renders a new
            // frame; the 2D canvas copies it and, in 'host', the frame pull speeds up while the finger moves. One finger orbits, a pinch zooms, two
            // fingers pan, a double tap fits.
            if (plan is CadFormats.Plan.Show) Box(Modifier.fillMaxSize().testTag("viewer_touch")
                .pointerInput(Unit) { detectTapGestures(onDoubleTap = { web?.evaluateJavascript("window.cadFit&&window.cadFit()", null) }) }
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
                ViewState.Fetching -> stringResource(R.string.viewer_fetching, m.name)
                // #1658 a visible loading state: an empty stage with no word on it read as a broken viewer
                ViewState.Loading -> stringResource(R.string.viewer_loading, m.name)
                ViewState.Shown -> null
                is ViewState.Failed -> stringResource(R.string.viewer_failed, s.why)
                is ViewState.StoreOnly -> s.reason
            }
            if (overlay != null) Text(overlay, color = if (state is ViewState.Fetching || state is ViewState.Loading) p.textSecondary else p.warn, fontSize = CadType.body, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(20.dp).testTag("viewer_status"))
        }
        if (plan is CadFormats.Plan.Show) ViewerControls(
            onFit = { web?.evaluateJavascript("window.cadFit&&window.cadFit()", null) },
            onWire = { on -> web?.evaluateJavascript("window.cadWireframe&&window.cadWireframe($on)", null) },
            onView = { v -> web?.evaluateJavascript("window.cadView&&window.cadView('$v')", null) },
            onReset = { web?.evaluateJavascript("window.cadReset&&window.cadReset()", null) },
            onBackground = { light -> web?.evaluateJavascript("window.cadBackground&&window.cadBackground('${if (light) "light" else "dark"}')", null) },
            full = full, onFull = onFull,
        )
        if (!full) Text(stringResource(R.string.viewer_model, m.name, m.format.uppercase(), m.licence), color = p.textPrimary, fontSize = CadType.label)
        if (!full && plan is CadFormats.Plan.Show && plan.derived)
            Text(stringResource(R.string.viewer_derived, plan.format.uppercase(), m.format.uppercase()), color = p.textSecondary, fontSize = CadType.caption)
        // #1658 the render state in words, always on the page: what was rendered, how it is presented and what the screen shows, or where it stopped
        var diagOpen by remember { mutableStateOf(false) }
        val name = when (state) { ViewState.Shown -> "shown"; is ViewState.Failed -> "failed"; is ViewState.StoreOnly -> "store-only"; else -> "loading" }
        Text(ViewerHealth.line(name, stage, present, triangles, paint?.w ?: 0, paint?.h ?: 0, paint?.pct ?: -1.0, screen, paint?.gl.orEmpty(), (state as? ViewState.Failed)?.why.orEmpty()),
            color = if (state is ViewState.Failed) p.warn else p.textSecondary.copy(alpha = 0.75f), fontSize = CadType.caption, maxLines = if (diagOpen || state is ViewState.Failed) 8 else 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.clickable { diagOpen = !diagOpen }.testTag("viewer_render"))
    }
}

/**
 * #1542 the viewer's buttons: fit the model to the view and the wireframe toggle (orbit, zoom and pan are the touch gestures of the stage).
 * #1664 plus the view presets (front / side / top / iso), reset, the background toggle and full screen, as readable kit-palette chips.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ViewerControls(onFit: () -> Unit, onWire: (Boolean) -> Unit, onView: (String) -> Unit, onReset: () -> Unit, onBackground: (Boolean) -> Unit,
                           full: Boolean, onFull: (() -> Unit)?) {
    val p = LocalKitPalette.current
    var wire by remember { mutableStateOf(false) }
    var light by remember { mutableStateOf(false) }
    var view by remember { mutableStateOf("iso") }
    Column(verticalArrangement = Arrangement.spacedBy(CadType.tight)) {
        // the presets: one segmented row
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(CadType.corner)).border(1.dp, p.hairline, RoundedCornerShape(CadType.corner)).testTag("viewer_presets")) {
            for ((id, label) in listOf("front" to R.string.viewer_front, "side" to R.string.viewer_side, "top" to R.string.viewer_top, "iso" to R.string.viewer_iso)) {
                val on = id == view
                Box(Modifier.weight(1f).background(if (on) p.surfaceSelected else p.surface).clickable { view = id; onView(id) }.padding(vertical = 10.dp).testTag("viewer_view:$id"),
                    contentAlignment = Alignment.Center) {
                    Text(stringResource(label), color = if (on) p.accent else p.textPrimary, fontSize = CadType.label, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium)
                }
            }
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CadType.tight), verticalArrangement = Arrangement.spacedBy(CadType.tight)) {
            ControlChip(stringResource(R.string.viewer_fit), false, Modifier.testTag("viewer_fit"), onFit)
            ControlChip(stringResource(R.string.viewer_reset), false, Modifier.testTag("viewer_reset")) { view = "iso"; onReset() }
            ControlChip(stringResource(R.string.viewer_wire), wire, Modifier.testTag("viewer_wire")) { wire = !wire; onWire(wire) }
            ControlChip(stringResource(R.string.viewer_background), light, Modifier.testTag("viewer_background")) { light = !light; onBackground(light) }
            if (onFull != null) ControlChip(stringResource(if (full) R.string.viewer_full_exit else R.string.viewer_full), full, Modifier.testTag("viewer_full"), onFull)
        }
        Text(stringResource(R.string.viewer_gestures), color = p.textSecondary, fontSize = CadType.caption)
    }
}

@Composable
private fun ControlChip(label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val p = LocalKitPalette.current
    val shape = RoundedCornerShape(50)
    Text(label, color = if (on) p.tileInk else p.textPrimary, fontSize = CadType.label, fontWeight = FontWeight.Medium,
        modifier = modifier.clip(shape).background(if (on) p.accent else p.surfaceSelected).border(1.dp, if (on) p.accent else p.hairline, shape)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp))
}

/** The string a WebView's evaluateJavascript hands back for a JS string result: a JSON string literal; null for "null" / blank. */
internal fun decodeJs(v: String?): String? {
    if (v.isNullOrBlank() || v == "null") return null
    return runCatching { org.json.JSONTokener(v).nextValue() as? String }.getOrNull()?.takeIf { it.isNotBlank() && it != "null" }
}

/**
 * #1631 The real viewer page with NO model: the X-Ray App page shows it so the WebGL context, the three.js module and the page's own
 * diagnostics are measured on this device even when no model has been opened. It records under [CadDiag.PROBE_ID] and re-reads
 * window.cadDiag() every few seconds. Same page, same WebView profile and same asset handler as [ModelViewer].
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun ViewerProbe(dir: File, modifier: Modifier = Modifier) {
    var web by remember { mutableStateOf<WebView?>(null) }
    val bridge = remember {
        object {
            @JavascriptInterface
            fun post(json: String) {
                android.util.Log.i("CadHost", "probe " + json)
                val e = runCatching { JSONObject(json) }.getOrNull() ?: return
                if (e.optString("type") == "error") CadDiag.failed(CadDiag.PROBE_ID, e.optString("message")) else CadDiag.event(CadDiag.PROBE_ID, json)
            }
        }
    }
    LaunchedEffect(web) {
        val w = web ?: return@LaunchedEffect
        CadDiag.begin(CadDiag.PROBE_ID, "viewer page without a model", "", "", VIEWER_URL)
        while (true) {
            kotlinx.coroutines.delay(1500)
            CadDiag.view(CadDiag.PROBE_ID, w.width, w.height, w.isShown, w.isHardwareAccelerated)
            w.evaluateJavascript("JSON.stringify(window.cadDiag&&window.cadDiag())") { v -> decodeJs(v)?.let { CadDiag.js(CadDiag.PROBE_ID, it) } }
            kotlinx.coroutines.delay(1500)
        }
    }
    DisposableEffect(Unit) { onDispose { web?.destroy(); web = null } }
    AndroidView(
        factory = { c ->
            val loader = WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(c))
                .addPathHandler(MODELS_PATH, WebViewAssetLoader.InternalStoragePathHandler(c, dir.apply { mkdirs() }))
                .build()
            CloudWeb.create(c, WebProfile.WEB_WORLD).also { wv ->
                wv.addJavascriptInterface(bridge, "CadHost")
                wv.webViewClient = object : CloudWebClient(WebProfile.WEB_WORLD, loader) {
                    override fun onRecovered(dead: WebView, fresh: WebView?) { fresh?.addJavascriptInterface(bridge, "CadHost"); web = fresh }
                }
                wv.loadUrl(VIEWER_URL)
                web = wv
            }
        },
        modifier = modifier,
    )
}

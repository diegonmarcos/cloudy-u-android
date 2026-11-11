package com.diegonmarcos.hardtechcad.ui

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.GLES20
import android.os.Build
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.uikit.KitStoreRow
import com.diegonmarcos.hardtechcad.BuildConfig
import com.diegonmarcos.hardtechcad.CadDiag
import com.diegonmarcos.hardtechcad.GlProbe
import com.diegonmarcos.hardtechcad.GlbInspector
import com.diegonmarcos.hardtechcad.ModelAsset
import com.diegonmarcos.hardtechcad.XrayReport
import com.diegonmarcos.hardtechcad.XraySnapshot
import com.diegonmarcos.superapp.cad.CadFormats
import com.diegonmarcos.superapp.cad.Model
import com.diegonmarcos.superapp.cad.ModelManifest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Test tags of the X-Ray App page. */
object XrayTags {
    const val PAGE = "cad_xray_page"
    const val VERDICT = "cad_xray_verdict"
    const val COPY_MD = "cad_xray_copy_md"
    const val COPY_JSON = "cad_xray_copy_json"
    const val SHARE = "cad_xray_share"
    fun model(id: String) = "cad_xray_model:$id"
}

/**
 * #1631 The EGL / GLES probe: a real pbuffer context created now on this device's own GPU stack (not a request, not a proxy). It reports each
 * stage it passed or the stage it stopped at with eglGetError, the strings of the context it made current (vendor, renderer, version, GLSL),
 * the limits, and whether a clear + glReadPixels round-trips.
 */
object EglProbe {
    private val SOFTWARE = listOf("swiftshader", "llvmpipe", "softpipe", "software", "lavapipe", "basic render")

    fun run(): GlProbe {
        val f = mutableListOf<Pair<String, String>>()
        var stage = "eglGetDisplay"
        var dpy = EGL14.EGL_NO_DISPLAY
        var surf = EGL14.EGL_NO_SURFACE
        var ctx = EGL14.EGL_NO_CONTEXT
        fun fail(err: String): GlProbe = GlProbe(true, false, stage, err + " (eglGetError 0x" + Integer.toHexString(EGL14.eglGetError()) + ")", f.toList())
        try {
            dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (dpy == EGL14.EGL_NO_DISPLAY) return fail("no EGL display")
            stage = "eglInitialize"
            val ver = IntArray(2)
            if (!EGL14.eglInitialize(dpy, ver, 0, ver, 1)) return fail("eglInitialize returned false")
            f += "EGL version" to "${ver[0]}.${ver[1]}"
            f += "EGL vendor" to EGL14.eglQueryString(dpy, EGL14.EGL_VENDOR).orEmpty()
            f += "EGL version string" to EGL14.eglQueryString(dpy, EGL14.EGL_VERSION).orEmpty()
            f += "EGL client APIs" to EGL14.eglQueryString(dpy, EGL14.EGL_CLIENT_APIS).orEmpty()
            var major = 3
            var cfg: EGLConfig? = null
            for (m in intArrayOf(3, 2)) {
                stage = "eglChooseConfig (GLES$m)"
                val attr = intArrayOf(
                    EGL14.EGL_RENDERABLE_TYPE, if (m == 3) EGLExt.EGL_OPENGL_ES3_BIT_KHR else EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_DEPTH_SIZE, 16, EGL14.EGL_NONE,
                )
                val cfgs = arrayOfNulls<EGLConfig>(1)
                val n = IntArray(1)
                if (EGL14.eglChooseConfig(dpy, attr, 0, cfgs, 0, 1, n, 0) && n[0] > 0) { cfg = cfgs[0]; major = m; break }
                f += "config GLES$m" to "none (count ${n[0]})"
            }
            if (cfg == null) return fail("no EGL config for GLES 3 or 2 with an RGBA8 + depth16 pbuffer")
            f += "context client version" to major.toString()
            stage = "eglCreateContext (GLES$major)"
            ctx = EGL14.eglCreateContext(dpy, cfg, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, major, EGL14.EGL_NONE), 0)
            if (ctx == EGL14.EGL_NO_CONTEXT) return fail("eglCreateContext returned EGL_NO_CONTEXT")
            stage = "eglCreatePbufferSurface"
            surf = EGL14.eglCreatePbufferSurface(dpy, cfg, intArrayOf(EGL14.EGL_WIDTH, 16, EGL14.EGL_HEIGHT, 16, EGL14.EGL_NONE), 0)
            if (surf == EGL14.EGL_NO_SURFACE) return fail("eglCreatePbufferSurface returned EGL_NO_SURFACE")
            stage = "eglMakeCurrent"
            if (!EGL14.eglMakeCurrent(dpy, surf, surf, ctx)) return fail("eglMakeCurrent returned false")
            stage = "glGetString"
            val vendor = GLES20.glGetString(GLES20.GL_VENDOR).orEmpty()
            val renderer = GLES20.glGetString(GLES20.GL_RENDERER).orEmpty()
            f += "GL_VENDOR" to vendor
            f += "GL_RENDERER" to renderer
            f += "GL_VERSION" to GLES20.glGetString(GLES20.GL_VERSION).orEmpty()
            f += "GLSL" to GLES20.glGetString(GLES20.GL_SHADING_LANGUAGE_VERSION).orEmpty()
            val x = IntArray(1)
            for ((k, p) in listOf("MAX_TEXTURE_SIZE" to GLES20.GL_MAX_TEXTURE_SIZE, "MAX_RENDERBUFFER_SIZE" to GLES20.GL_MAX_RENDERBUFFER_SIZE,
                "MAX_VERTEX_ATTRIBS" to GLES20.GL_MAX_VERTEX_ATTRIBS, "MAX_VERTEX_UNIFORM_VECTORS" to GLES20.GL_MAX_VERTEX_UNIFORM_VECTORS,
                "MAX_FRAGMENT_UNIFORM_VECTORS" to GLES20.GL_MAX_FRAGMENT_UNIFORM_VECTORS)) {
                GLES20.glGetIntegerv(p, x, 0); f += k to x[0].toString()
            }
            val ext = GLES20.glGetString(GLES20.GL_EXTENSIONS).orEmpty()
            f += "GL extensions" to ext.split(' ').count { it.isNotBlank() }.toString()
            f += "OES_element_index_uint" to (major >= 3 || "GL_OES_element_index_uint" in ext).toString()
            stage = "clear + glReadPixels"
            GLES20.glViewport(0, 0, 16, 16)
            GLES20.glClearColor(0f, 1f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            val px = java.nio.ByteBuffer.allocateDirect(4)
            GLES20.glReadPixels(0, 0, 1, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, px)
            val rb = IntArray(4) { px.get(it).toInt() and 0xFF }
            val glErr = GLES20.glGetError()
            f += "draw readback" to rb.joinToString(",") + (if (rb[0] == 0 && rb[1] == 255 && rb[2] == 0 && rb[3] == 255) " (the clear colour came back)" else " (NOT the clear colour 0,255,0,255)")
            f += "glGetError" to "0x" + Integer.toHexString(glErr)
            val soft = SOFTWARE.any { it in renderer.lowercase() || it in vendor.lowercase() }
            f += "hardware GPU" to if (soft) "NO: software rasteriser" else "yes (renderer is not a known software one)"
            if (!(rb[1] == 255 && rb[0] == 0)) return GlProbe(true, false, stage, "a clear + readPixels did not return the clear colour", f.toList(), soft)
            return GlProbe(true, true, "done", "", f.toList(), soft)
        } catch (e: Throwable) {
            return GlProbe(true, false, stage, "${e.javaClass.simpleName}: ${e.message}", f.toList())
        } finally {
            runCatching {
                if (dpy != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    if (surf != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, surf)
                    if (ctx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(dpy, ctx)
                    EGL14.eglTerminate(dpy)
                }
            }
        }
    }
}

/** The device, app and WebView facts of the report. */
internal object XrayFacts {
    fun app(): List<Pair<String, String>> = listOf(
        "Application" to "com.diegonmarcos.hardtechcad",
        "Version" to BuildConfig.VERSION_NAME,
        "Build sha" to BuildConfig.GIT_SHORT_SHA,
        "Build time" to BuildConfig.BUILD_TIMESTAMP,
        "Models base (runtime fetch)" to BuildConfig.MODELS_BASE,
    )

    fun device(ctx: Context): List<Pair<String, String>> {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val gl = am?.deviceConfigurationInfo?.glEsVersion.orEmpty()
        val pm = ctx.packageManager
        return listOf(
            "Model" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "Android" to "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
            "Hardware / board" to "${Build.HARDWARE} / ${Build.BOARD}",
            "ABIs" to Build.SUPPORTED_ABIS.joinToString(","),
            "System GLES version (ActivityManager)" to gl,
            "Vulkan hardware level feature" to pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL).toString(),
            "GLES extension pack feature" to pm.hasSystemFeature("android.hardware.opengles.aep").toString(),
            "Memory class (MB)" to (am?.memoryClass?.toString() ?: "?"),
            "Large memory class (MB)" to (am?.largeMemoryClass?.toString() ?: "?"),
            "Low RAM device" to (am?.isLowRamDevice?.toString() ?: "?"),
        )
    }

    fun webview(): List<Pair<String, String>> {
        val p = runCatching { WebView.getCurrentWebViewPackage() }.getOrNull()
        return listOf(
            "WebView package" to (if (p == null) "none (WebView.getCurrentWebViewPackage() is null)" else "${p.packageName} ${p.versionName}"),
            "WebView version code" to (p?.let { @Suppress("DEPRECATION") if (Build.VERSION.SDK_INT >= 28) it.longVersionCode.toString() else it.versionCode.toString() } ?: "-"),
        )
    }

    fun now(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
}

/** Reads every declared model's real asset: opened from the APK, counted, hashed and parsed; the URL the viewer page is given for it. */
internal object AssetProbe {
    private const val MODELS_ASSET = "cad/models.json"
    private const val ASSET_URL_BASE = "/assets/"

    fun run(ctx: Context): List<ModelAsset> {
        val text = runCatching { ctx.assets.open(MODELS_ASSET).use { it.readBytes().toString(Charsets.UTF_8) } }.getOrElse {
            return listOf(ModelAsset("(models.json)", "the model declaration", "json", "-", true, MODELS_ASSET, "", 0, "", false, null, "cannot open: ${it.javaClass.simpleName}: ${it.message}"))
        }
        return ModelManifest.parse(text).map { probe(ctx, it) }
    }

    fun probe(ctx: Context, m: Model): ModelAsset {
        val plan = CadFormats.plan(m)
        if (plan !is CadFormats.Plan.Show) {
            return ModelAsset(m.id, m.name, m.format, "store-only", false, m.path, "", m.size, m.sha256, false, null, (plan as CadFormats.Plan.StoreOnly).reason)
        }
        val asset = plan.asset
        if (asset.isEmpty()) {
            val cached = File(File(ctx.cacheDir, "cad-models"), plan.path.substringAfterLast('/'))
            return ModelAsset(m.id, m.name, plan.format, plan.lib, false, plan.path, "/models/" + cached.name, plan.size, plan.sha256, cached.isFile, null,
                "fetched at runtime from ${BuildConfig.MODELS_BASE}${plan.path}; cache file ${if (cached.isFile) "present, ${cached.length()} bytes" else "not present yet"}")
        }
        val r = runCatching { ctx.assets.open(asset).use { GlbInspector.inspect(it) } }
        return r.fold(
            { ModelAsset(m.id, m.name, plan.format, plan.lib, true, asset, ASSET_URL_BASE + asset, plan.size, plan.sha256, true, it) },
            { ModelAsset(m.id, m.name, plan.format, plan.lib, true, asset, ASSET_URL_BASE + asset, plan.size, plan.sha256, false, null, "${it.javaClass.simpleName}: ${it.message}") },
        )
    }

    /** The models the Live test can open: every declared model that has a viewer. */
    fun models(ctx: Context): List<Model> {
        val text = runCatching { ctx.assets.open(MODELS_ASSET).use { it.readBytes().toString(Charsets.UTF_8) } }.getOrDefault("")
        return ModelManifest.parse(text).filter { CadFormats.plan(it) is CadFormats.Plan.Show }
    }
}

private fun copy(ctx: Context, label: String, text: String) {
    runCatching { (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, text)) }
}

private fun statusColor(s: String) = when (s) { "OK" -> Color(0xFF3DDC84); "WARN" -> Color(0xFFFFC107); else -> Color(0xFFFF5252) }

/**
 * #1631 Configs > X-Ray App of Hardtech-CAD: WHY a 3D model does or does not render on this device, from the real pipeline, stage by stage
 * (EGL/GLES context, WebView engine, WebGL context of the viewer page, three.js module, the bundled asset's bytes and parse, the load and the
 * first painted frame), with the first failing stage as the verdict, a live test (open any model right here) and the full report as Markdown,
 * JSON, clipboard and share. Everything is read live at runtime; nothing is baked.
 */
@Composable
fun CadXrayPage(dark: Boolean = true) {
    val ctx = LocalContext.current
    val ink = if (dark) Color(0xFFE6EAF2) else Color(0xFF15181E)
    val dim = if (dark) Color(0xFF9AA3B2) else Color(0xFF5B6372)
    val card = if (dark) Color(0x22FFFFFF) else Color(0x14000000)
    val accent = Color(0xFF8E7CFF)
    var egl by remember { mutableStateOf(GlProbe(false, false, "not run", "", emptyList())) }
    var assets by remember { mutableStateOf<List<ModelAsset>>(emptyList()) }
    var liveModels by remember { mutableStateOf<List<Model>>(emptyList()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var runs by remember { mutableIntStateOf(0) }
    val dir = remember { File(ctx.cacheDir, "cad-models") }

    LaunchedEffect(runs) {
        egl = withContext(Dispatchers.Default) { EglProbe.run() }
        assets = withContext(Dispatchers.IO) { AssetProbe.run(ctx) }
        liveModels = withContext(Dispatchers.IO) { AssetProbe.models(ctx) }
    }
    LaunchedEffect(Unit) { while (true) { delay(1000); tick++ } }

    val live = remember(tick) { CadDiag.snapshot() }
    // the newest viewer-page diagnostics of any viewer (the model's viewer first, else the probe)
    val newest = live.filter { it.js.isNotEmpty() }.maxByOrNull { it.jsAt }
    val snap = XraySnapshot(
        takenAt = XrayFacts.now(), app = XrayFacts.app(), device = XrayFacts.device(ctx), egl = egl, webview = XrayFacts.webview(),
        viewerJs = newest?.js.orEmpty(), viewerJsAgeMs = newest?.let { System.currentTimeMillis() - it.jsAt } ?: -1, models = assets, live = live,
    )
    val stages = XrayReport.stages(snap)
    val verdict = XrayReport.verdict(snap)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp).testTag(XrayTags.PAGE), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Card(card) {
            Text("Verdict", color = dim, fontSize = KitStoreRow.caption)
            val bad = stages.any { it.status == "FAIL" }
            Text(verdict, color = if (bad) statusColor("FAIL") else statusColor("OK"), fontSize = KitStoreRow.body, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag(XrayTags.VERDICT))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Copy Markdown", accent, Modifier.testTag(XrayTags.COPY_MD)) { copy(ctx, "Hardtech-CAD X-Ray", XrayReport.markdown(snap)) }
                Chip("Copy JSON", accent, Modifier.testTag(XrayTags.COPY_JSON)) { copy(ctx, "Hardtech-CAD X-Ray", XrayReport.json(snap)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Share report", accent, Modifier.testTag(XrayTags.SHARE)) {
                    runCatching {
                        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, "Hardtech-CAD X-Ray")
                            .putExtra(Intent.EXTRA_TEXT, XrayReport.markdown(snap)), "Share the X-Ray report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
                Chip("Re-run probes", accent) { runs++ }
            }
        }
        Card(card) {
            Text("Pipeline, in order (the first FAIL is where a model stops rendering)", color = dim, fontSize = KitStoreRow.caption)
            stages.forEach { s ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(s.status, color = statusColor(s.status), fontSize = KitStoreRow.caption, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 1.dp))
                    Column {
                        Text(s.name, color = ink, fontSize = KitStoreRow.caption, fontWeight = FontWeight.Medium)
                        Text(s.detail, color = dim, fontSize = KitStoreRow.caption)
                    }
                }
            }
        }
        Card(card) {
            Text("GPU: EGL / GLES context created now on this device", color = dim, fontSize = KitStoreRow.caption)
            if (egl.ran && !egl.ok) Text("FAILED at ${egl.stage}: ${egl.error}", color = statusColor("FAIL"), fontSize = KitStoreRow.caption)
            Facts(egl.fields, ink, dim)
        }
        Card(card) {
            Text("WebView and device", color = dim, fontSize = KitStoreRow.caption)
            Facts(snap.webview + snap.device, ink, dim)
        }
        Card(card) {
            Text("Viewer page: window.cadDiag() (WebGL context kinds tried, three.js module, frames, errors)", color = dim, fontSize = KitStoreRow.caption)
            val d = runCatching { org.json.JSONObject(snap.viewerJs) }.getOrNull()
            if (d == null) Text("No viewer page has answered yet (a WebView below loads it; give it a few seconds).", color = statusColor("WARN"), fontSize = KitStoreRow.caption)
            else {
                val pr = d.optJSONObject("probe")
                val canvas = d.optJSONObject("canvas")
                val win = d.optJSONObject("window")
                Facts(listOfNotNull(
                    "stage" to "${d.optString("stage")} (at ${d.optLong("stageMs")} ms, page age ${d.optLong("ageMs")} ms)",
                    "module / renderer / ready" to "${d.optBoolean("moduleLoaded")} / ${d.optBoolean("rendererCreated")} / ${d.optBoolean("ready")}",
                    "frames drawn" to d.optInt("frames").toString(),
                    "first frame" to (if (d.optLong("firstFrameMs", -1) >= 0) "${d.optLong("firstFrameMs")} ms after page start" else "none yet"),
                    "last frame" to (if (d.optLong("lastFrameMs", -1) >= 0) "${d.optLong("lastFrameMs")} ms" else "none yet"),
                    "last render error" to d.optString("lastError").ifEmpty { "none" },
                    "context lost / restored" to "${d.optBoolean("contextLost")} / ${d.optInt("restored")}",
                    "opened / load / shown" to "${d.optString("opened").ifEmpty { "-" }} / ${d.optLong("loadMs")} ms / ${d.optLong("shownMs")} ms",
                    "draw calls / triangles (last frame)" to (d.optJSONObject("render")?.let { "${it.optInt("calls")} / ${it.optInt("triangles")}" } ?: "-"),
                    "canvas (drawing buffer / css)" to (canvas?.let { "${it.optInt("width")}x${it.optInt("height")} / ${it.optInt("clientWidth")}x${it.optInt("clientHeight")}" } ?: "no canvas"),
                    "window / dpr / visibility" to (win?.let { "${it.optInt("innerWidth")}x${it.optInt("innerHeight")} / ${it.optDouble("dpr")} / ${it.optString("visibility")}" } ?: "-"),
                    "WebGL context attempts" to (pr?.optJSONArray("attempts")?.let { a -> (0 until a.length()).joinToString("; ") { a.optString(it) } } ?: "-"),
                    "WebGL kind / hardware / draws" to (pr?.let { "${it.optString("kind")} / ${it.optBoolean("hardware")} / ${it.optBoolean("drawOk")}" } ?: "-"),
                    "WebGL renderer (unmasked)" to (pr?.let { it.optString("unmaskedRenderer").ifEmpty { it.optString("renderer") } } ?: "-"),
                    "WebGL vendor (unmasked)" to (pr?.let { it.optString("unmaskedVendor").ifEmpty { it.optString("vendor") } } ?: "-"),
                    "WebGL version" to (pr?.optString("version") ?: "-"),
                    "WebGL max texture / renderbuffer" to (pr?.let { "${it.optInt("maxTexture")} / ${it.optInt("maxRenderbuffer")}" } ?: "-"),
                    "WebGL error" to (pr?.optString("error")?.ifEmpty { "none" } ?: "-"),
                ), ink, dim)
            }
        }
        Card(card) {
            Text("Model assets: found, bytes, parse, hash, the path and URL actually used", color = dim, fontSize = KitStoreRow.caption)
            if (assets.isEmpty()) Text("reading…", color = dim, fontSize = KitStoreRow.caption)
            assets.forEach { a ->
                val i = a.inspect
                val ok = a.found && (i?.ok == true || !a.bundled) && a.hashOk != false && a.sizeOk != false
                Column(Modifier.testTag(XrayTags.model(a.id)), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text((if (ok) "OK  " else "FAIL  ") + a.id + "  (" + a.name + ")", color = if (ok || !a.bundled) ink else statusColor("FAIL"), fontSize = KitStoreRow.caption, fontWeight = FontWeight.Medium)
                    Facts(listOfNotNull(
                        "route" to "${a.route}, ${a.format}, ${if (a.bundled) "bundled in the APK" else "not bundled"}",
                        "resolved path" to a.path,
                        "URL given to the viewer" to a.url.ifEmpty { "-" },
                        "found" to a.found.toString(),
                        i?.let { "bytes (read / declared)" to "${it.bytes} / ${a.declaredSize}" },
                        i?.let { "parse" to (if (it.ok) "ok: glTF ${it.version}, ${it.meshes} mesh(es), ${it.vertices} vertices, ${it.triangles} triangles" else "FAILED: ${it.error}") },
                        i?.takeIf { it.sha256.isNotEmpty() }?.let { "sha256 matches the declaration" to (a.hashOk?.toString() ?: "-") },
                        a.note.takeIf { it.isNotEmpty() }?.let { "note" to it },
                    ), ink, dim, mono = false)
                }
            }
        }
        Card(card) {
            Text("Live test: open a model right here (the real viewer, recorded above); with none chosen the viewer page loads with no model", color = dim, fontSize = KitStoreRow.caption)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("No model", accent, selected = selected == null) { selected = null }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                liveModels.forEach { m -> Chip(m.id, accent, selected = selected == m.id) { selected = m.id } }
            }
            val sel = liveModels.firstOrNull { it.id == selected }
            if (sel != null) ModelViewer(sel, dir)
            else Column(Modifier.fillMaxWidth().height(160.dp).background(Color(0xFF0E1116)).border(1.dp, dim.copy(alpha = 0.3f), RoundedCornerShape(12.dp))) { ViewerProbe(dir, Modifier.fillMaxSize()) }
        }
        Card(card) {
            Text("Viewer sessions of this process (events as the page posted them)", color = dim, fontSize = KitStoreRow.caption)
            if (live.isEmpty()) Text("none yet", color = dim, fontSize = KitStoreRow.caption)
            live.forEach { r ->
                Text("${r.id}: ${r.state}${if (r.error.isNotEmpty()) "  ERROR: " + r.error else ""}", color = if (r.error.isNotEmpty()) statusColor("FAIL") else ink, fontSize = KitStoreRow.caption, fontWeight = FontWeight.Medium)
                Text("WebView ${r.viewW}x${r.viewH}, shown ${r.viewShown}, hardware accelerated ${r.viewHwAccel}, shown after ${r.shownMs} ms, ${r.triangles} triangles, painted ${r.paintedPct}%, on screen ${if (r.screenPct < 0) "not measured" else "${r.screenPct}%"} through ${r.present}", color = dim, fontSize = KitStoreRow.caption)
                r.events.takeLast(12).forEach { Text(it, color = dim, fontSize = KitStoreRow.caption, fontFamily = FontFamily.Monospace) }
            }
        }
    }
}

@Composable
private fun Card(bg: Color, body: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(bg).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { body() }
}

@Composable
private fun Facts(l: List<Pair<String, String>>, ink: Color, dim: Color, mono: Boolean = true) {
    l.forEach { (k, v) ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(k, color = dim, fontSize = KitStoreRow.caption, modifier = Modifier.weight(0.42f))
            Text(v.ifEmpty { "-" }, color = ink, fontSize = KitStoreRow.caption, fontFamily = if (mono) FontFamily.Monospace else null, modifier = Modifier.weight(0.58f))
        }
    }
}

@Composable
private fun Chip(label: String, accent: Color, modifier: Modifier = Modifier, selected: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Text(label, color = if (selected) Color.Black else accent, fontSize = KitStoreRow.caption, fontWeight = FontWeight.SemiBold,
        modifier = modifier.clip(shape).background(if (selected) accent else Color.Transparent).border(1.dp, accent, shape).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp))
}

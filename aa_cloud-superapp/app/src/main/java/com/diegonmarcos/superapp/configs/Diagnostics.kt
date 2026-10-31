package com.diegonmarcos.superapp.configs

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Base64
import android.view.PixelCopy
import com.diegonmarcos.superapp.BuildConfig
import com.diegonmarcos.superapp.devtools.AppDebugServer
import com.diegonmarcos.superapp.launcher.TileGridFragment
import com.diegonmarcos.superapp.launcher.TileMetrics
import com.diegonmarcos.superapp.launcher.tileIconDp
import com.diegonmarcos.superapp.onehand.EdgeEventLog
import com.diegonmarcos.superapp.onehand.OneHandConfig
import com.diegonmarcos.superapp.onehand.OneHandController
import com.diegonmarcos.superapp.onehand.OneHandOverlayService
import com.diegonmarcos.superapp.onehand.OneHandPrefs
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Configs ▸ Observability ▸ Diagnostics: ONE read of what this phone really does, as JSON. The page draws it
 * (Configs ▸ Observability ▸ X-Ray, its DIAGNOSTICS group) and the fleet debug API serves the same object ([register]: `diagnostics/get`, `/screenshot`,
 * `/open`, `/inject`), so the owner never has to screenshot anything for an agent to see the device.
 */
object Diagnostics {

    // ── the activity the app is showing now (also the other door that restarts the edge strips) ──
    private var current = WeakReference<Activity>(null)
    val activity: Activity? get() = current.get()

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: Activity) {
                current = WeakReference(a)
                runCatching { OneHandController.ensureRunning(a.applicationContext) }
            }
            override fun onActivityPaused(a: Activity) { if (current.get() === a) current = WeakReference(null) }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    /** Samsung One UI as "major.minor" from SEM_PLATFORM_INT (e.g. 60100 -> 6.1 on One UI 6.1), or "" off Samsung. */
    fun oneUi(): String = runCatching {
        val sem = Build.VERSION::class.java.getField("SEM_PLATFORM_INT").getInt(null)
        if (sem < 90000) "" else "${(sem - 90000) / 10000}.${(sem % 10000) / 100} (SEM $sem)"
    }.getOrDefault("")

    /** 0 = 3-button, 1 = 2-button, 2 = gesture, -1 unknown. */
    fun navMode(ctx: Context): Int = runCatching {
        val fw = ctx.resources.getIdentifier("config_navBarInteractionMode", "integer", "android")
        if (fw != 0) ctx.resources.getInteger(fw) else -1
    }.getOrDefault(-1)

    /** System gesture insets per edge, dp, from the live window; null before any window is attached. */
    private fun gestureInsets(a: Activity?, density: Float): JSONObject? {
        val root = a?.window?.decorView?.rootWindowInsets ?: return null
        val r = when {
            Build.VERSION.SDK_INT >= 30 ->
                root.getInsets(android.view.WindowInsets.Type.systemGestures()).let { intArrayOf(it.left, it.top, it.right, it.bottom) }
            Build.VERSION.SDK_INT >= 29 -> root.systemGestureInsets.let { intArrayOf(it.left, it.top, it.right, it.bottom) }
            else -> return null
        }
        fun dp(px: Int) = Math.round(px / density * 10f) / 10.0
        return JSONObject().put("left_dp", dp(r[0])).put("top_dp", dp(r[1])).put("right_dp", dp(r[2])).put("bottom_dp", dp(r[3]))
            .put("left_px", r[0]).put("right_px", r[2])
    }

    fun collect(ctx: Context): JSONObject {
        val a = activity
        val res = (a ?: ctx).resources
        val dm = res.displayMetrics
        val cfg = res.configuration
        val svc = OneHandOverlayService.instance
        val tile = TileMetrics.latest()
        val expectedIcon = tileIconDp(dm.widthPixels, dm.density, 8, 0, 18).value
        val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        return JSONObject()
            .put("build", JSONObject().put("sha", BuildConfig.GIT_SHORT_SHA).put("version", BuildConfig.VERSION_NAME)
                .put("code", BuildConfig.VERSION_CODE).put("built", BuildConfig.BUILD_TIMESTAMP))
            .put("device", JSONObject().put("model", Build.MODEL).put("manufacturer", Build.MANUFACTURER)
                .put("android", Build.VERSION.RELEASE).put("sdk", Build.VERSION.SDK_INT).put("one_ui", oneUi()))
            .put("display", JSONObject().put("density", dm.density.toDouble()).put("density_dpi", dm.densityDpi)
                .put("font_scale", cfg.fontScale.toDouble()).put("screen_w_dp", cfg.screenWidthDp).put("screen_h_dp", cfg.screenHeightDp)
                .put("width_px", dm.widthPixels).put("height_px", dm.heightPixels)
                .put("activity", a?.javaClass?.simpleName ?: ""))
            .put("gesture", JSONObject().put("nav_mode", navMode(ctx)).put("nav_mode_name", when (navMode(ctx)) {
                0 -> "3-button"; 1 -> "2-button"; 2 -> "gesture"; else -> "unknown" })
                .put("secure_navigation_mode", runCatching { Settings.Secure.getInt(ctx.contentResolver, "navigation_mode") }.getOrDefault(-1))
                .put("insets", gestureInsets(a, dm.density) ?: JSONObject.NULL))
            .put("edge", JSONObject()
                .put("switch_on", OneHandPrefs.isEnabled(ctx))
                .put("overlay_permission", Settings.canDrawOverlays(ctx))
                .put("service_running", OneHandOverlayService.isRunning)
                .put("strip_width_dp", OneHandPrefs.stripWidthDp(ctx))
                .put("inset_override_dp", OneHandPrefs.edgeInsetDp(ctx) ?: JSONObject.NULL)
                .put("debug_outline", OneHandPrefs.debugVisible(ctx))
                .put("strips", JSONArray().also { arr ->
                    svc?.stripRects()?.forEach { r ->
                        arr.put(JSONObject().put("id", r.id).put("attached", r.attached).put("left", r.left).put("top", r.top)
                            .put("width", r.width).put("height", r.height)
                            .put("left_dp", r.left / dm.density).put("width_dp", r.width / dm.density))
                    }
                })
                .put("events", JSONArray().also { arr ->
                    EdgeEventLog.snapshot().forEach { e ->
                        arr.put(JSONObject().put("t", fmt.format(Date(e.atMs))).put("at_ms", e.atMs).put("kind", e.kind)
                            .put("handle", e.handle).put("reason", e.reason).put("x", e.x.toInt()).put("y", e.y.toInt()))
                    }
                }))
            .put("configs_tiles", JSONObject()
                .put("measured", tile != null)
                .put("tiles_seen", TileMetrics.count())
                .put("tile_w_dp", tile?.tileWDp ?: 0f).put("tile_h_dp", tile?.tileHDp ?: 0f)
                .put("icon_w_dp", tile?.iconWDp ?: 0f).put("icon_h_dp", tile?.iconHDp ?: 0f)
                .put("largest_icon_dp", TileMetrics.largestIconDp())
                .put("expected_icon_dp_at_8_cols", expectedIcon).put("icon_max_dp", 32))
    }

    /** The same object as a screenshot-friendly text block. */
    fun text(j: JSONObject): String = buildString {
        fun o(k: String) = j.getJSONObject(k)
        o("build").let { appendLine("BUILD   sha=${it.getString("sha")} v${it.getString("version")} (${it.getInt("code")}) ${it.getString("built")}") }
        o("device").let { appendLine("DEVICE  ${it.getString("manufacturer")} ${it.getString("model")} · Android ${it.getString("android")} (sdk ${it.getInt("sdk")})" +
            (it.getString("one_ui").takeIf { s -> s.isNotEmpty() }?.let { s -> " · One UI $s" } ?: "")) }
        o("display").let { appendLine("DISPLAY density=${it.getDouble("density")} (${it.getInt("density_dpi")}dpi) fontScale=${it.getDouble("font_scale")} " +
            "screen=${it.getInt("screen_w_dp")}x${it.getInt("screen_h_dp")}dp ${it.getInt("width_px")}x${it.getInt("height_px")}px") }
        o("gesture").let { g ->
            appendLine("NAV     ${g.getString("nav_mode_name")} (framework ${g.getInt("nav_mode")}, secure ${g.getInt("secure_navigation_mode")})")
            val i = g.optJSONObject("insets")
            appendLine("GESTURE INSETS " + (if (i == null) "n/a (no window yet)" else
                "L=${i.getDouble("left_dp")}dp R=${i.getDouble("right_dp")}dp T=${i.getDouble("top_dp")}dp B=${i.getDouble("bottom_dp")}dp"))
        }
        o("edge").let { e ->
            appendLine("EDGE    switch=${e.getBoolean("switch_on")} overlayPermission=${e.getBoolean("overlay_permission")} " +
                "service=${if (e.getBoolean("service_running")) "RUNNING" else "NOT running"} strip=${e.getInt("strip_width_dp")}dp " +
                "inset=${e.opt("inset_override_dp").let { v -> if (v == JSONObject.NULL) "default" else "${v}dp" }} outline=${e.getBoolean("debug_outline")}")
            val s = e.getJSONArray("strips")
            if (s.length() == 0) appendLine("STRIPS  none attached")
            for (k in 0 until s.length()) s.getJSONObject(k).let { appendLine("STRIP   ${it.getString("id")} attached=${it.getBoolean("attached")} " +
                "x=${it.getInt("left")} y=${it.getInt("top")} ${it.getInt("width")}x${it.getInt("height")}px") }
            val ev = e.getJSONArray("events")
            appendLine("EVENTS  (${ev.length()}, last ${EdgeEventLog.CAP})")
            for (k in 0 until ev.length()) ev.getJSONObject(k).let { appendLine("  ${it.getString("t")} ${it.getString("kind")} ${it.getString("handle")} ${it.getString("reason")}") }
        }
        o("configs_tiles").let { t ->
            appendLine("TILES   " + if (!t.getBoolean("measured")) "not measured yet - open a Configs grid first" else
                "tile=${"%.1f".format(t.getDouble("tile_w_dp"))}x${"%.1f".format(t.getDouble("tile_h_dp"))}dp " +
                "icon=${"%.1f".format(t.getDouble("icon_w_dp"))}x${"%.1f".format(t.getDouble("icon_h_dp"))}dp " +
                "largestIcon=${"%.1f".format(t.getDouble("largest_icon_dp"))}dp (max 32, expected@8col ${"%.1f".format(t.getDouble("expected_icon_dp_at_8_cols"))})")
        }
    }

    // ── remote, read-only: the fleet debug API (gateway /app/<pkg>/api/diagnostics/<op>, fleet bearer) ──
    private val main = Handler(Looper.getMainLooper())

    fun register(ctx: Context) {
        val app = ctx.applicationContext
        AppDebugServer.route("diagnostics", listOf(
            AppDebugServer.Op("get", "", "build SHA, device/Android/One UI, density/fontScale/dp, gesture insets, overlay permission, edge service + strips + last 20 touch events, measured Configs tile/icon dp"),
            AppDebugServer.Op("screenshot", "w=pixels (default 540)", "PNG (base64 in JSON) of the current SuperApp activity window, PixelCopy of its own window"),
            AppDebugServer.Op("open", "page=<section>/<page> (default config/xray)", "open a SuperApp page in the foreground activity"),
            AppDebugServer.Op("inject", "edge=left|right&hold_ms=&push_dp=", "own-window test gesture on the edge strip (runs the real touch handler; see edge.events)"),
        )) { op, q ->
            when (op) {
                "", "get" -> collect(app).toString()
                "screenshot" -> screenshot((q["w"]?.toIntOrNull() ?: 540).coerceIn(120, 1080)).toString()
                "open" -> open(q["page"].orEmpty().ifBlank { "config/xray" }).toString()
                "inject" -> inject(app, q).toString()
                else -> null
            }
        }
    }

    fun open(page: String): JSONObject {
        val a = activity as? TileGridFragment.TileClickListener
            ?: return JSONObject().put("ok", false).put("error", "no SuperApp activity in the foreground")
        main.post { a.onTileClicked(if (page.contains(':')) page else "page:$page") }
        return JSONObject().put("ok", true).put("target", page)
    }

    fun inject(ctx: Context, q: Map<String, String>): JSONObject {
        val edge = if (q["edge"].equals("left", true)) OneHandConfig.Edge.LEFT else OneHandConfig.Edge.RIGHT
        val svc = OneHandOverlayService.instance
            ?: return JSONObject().put("ok", false).put("error", "edge service not running")
        val ok = svc.injectGesture(edge, (q["hold_ms"]?.toLongOrNull() ?: 450L).coerceIn(100, 3000),
            (q["push_dp"]?.toIntOrNull() ?: 90).coerceIn(10, 300))
        return JSONObject().put("ok", ok).put("edge", edge.name).apply { if (!ok) put("error", "no attached strip on that edge") }
    }

    fun screenshot(width: Int): JSONObject {
        val a = activity ?: return JSONObject().put("ok", false).put("error", "no SuperApp activity in the foreground")
        val w = a.window.decorView.width; val h = a.window.decorView.height
        if (w <= 0 || h <= 0 || Build.VERSION.SDK_INT < 26) return JSONObject().put("ok", false).put("error", "window not laid out")
        val bw = minOf(width, w); val bh = h * bw / w
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val latch = CountDownLatch(1); var code = -1
        main.post {
            runCatching { PixelCopy.request(a.window, bmp, { code = it; latch.countDown() }, main) }
                .onFailure { latch.countDown() }
        }
        if (!latch.await(4, TimeUnit.SECONDS) || code != PixelCopy.SUCCESS)
            return JSONObject().put("ok", false).put("error", "PixelCopy code=$code")
        val out = ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.PNG, 90, out)
        return JSONObject().put("ok", true).put("width", bw).put("height", bh).put("mime", "image/png")
            .put("png_b64", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
    }
}

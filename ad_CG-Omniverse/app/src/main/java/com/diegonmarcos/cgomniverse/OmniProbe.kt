package com.diegonmarcos.cgomniverse

import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.webkit.WebView
import com.diegonmarcos.cgomniverse.ui.Web
import com.diegonmarcos.cloudlib.web.GpuProbe
import org.json.JSONObject

/**
 * #1631 The native half of the X-Ray App: facts that do not need the Avatar page to be open. Reads the WebView package, creates a
 * real EGL / GLES2 context to see whether this process can get a hardware GPU context at all, runs the fleet's detached WebGL probe
 * ([GpuProbe]), and reads every asset the Avatar scene needs from the APK (found? bytes?), decoding the GLB header. Blocking: call
 * off the main thread (the probe page itself runs on the main thread and answers through [GpuProbe]).
 */
object OmniProbe {
    /** The assets the Avatar scene imports, besides its model: page, three.js build, cgc.js and its loader. */
    private val SCENE_ASSETS = listOf("avatar/index.html", "space/three.module.min.js", "cgc/cgc.js", "cgc/cgc-anim.js", "cgc/clips.json", "cgc/vendor/GLTFLoader.js")

    fun webView(): OmniXray.WebViewInfo? = runCatching {
        WebView.getCurrentWebViewPackage()?.let {
            OmniXray.WebViewInfo(it.packageName.orEmpty(), it.versionName.orEmpty(), if (android.os.Build.VERSION.SDK_INT >= 28) it.longVersionCode else 0L)
        }
    }.getOrNull()

    /** A pbuffer EGL context of GLES 2, current on this thread, read and released. The display is shared, so it is not terminated. */
    fun egl(): OmniXray.Egl = try {
        val d = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val v = IntArray(2)
        if (!EGL14.eglInitialize(d, v, 0, v, 1)) return OmniXray.Egl(false, "", "", "", 0, "eglInitialize failed, EGL error 0x" + Integer.toHexString(EGL14.eglGetError()))
        val want = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_NONE,
        )
        val cfgs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        if (!EGL14.eglChooseConfig(d, want, 0, cfgs, 0, 1, n, 0) || n[0] < 1) return OmniXray.Egl(false, "", "", "", 0, "no EGL config for GLES2 + pbuffer, EGL error 0x" + Integer.toHexString(EGL14.eglGetError()))
        val ctx = EGL14.eglCreateContext(d, cfgs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        val surf = EGL14.eglCreatePbufferSurface(d, cfgs[0], intArrayOf(EGL14.EGL_WIDTH, 16, EGL14.EGL_HEIGHT, 16, EGL14.EGL_NONE), 0)
        try {
            if (!EGL14.eglMakeCurrent(d, surf, surf, ctx)) return OmniXray.Egl(false, "", "", "", 0, "eglMakeCurrent failed, EGL error 0x" + Integer.toHexString(EGL14.eglGetError()))
            val max = IntArray(1)
            GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, max, 0)
            OmniXray.Egl(true, GLES20.glGetString(GLES20.GL_RENDERER).orEmpty(), GLES20.glGetString(GLES20.GL_VENDOR).orEmpty(), GLES20.glGetString(GLES20.GL_VERSION).orEmpty(), max[0], "")
        } finally {
            EGL14.eglMakeCurrent(d, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(d, surf)
            EGL14.eglDestroyContext(d, ctx)
        }
    } catch (e: Throwable) {
        OmniXray.Egl(false, "", "", "", 0, e.javaClass.simpleName + ": " + (e.message ?: ""))
    }

    /** The model the scene declares (data/avatar.json::scene.model.url), the absolute URL the page resolves it to, and its APK asset path. */
    fun modelRef(): Triple<String, String, String> {
        val declared = runCatching { JSONObject(Decl.avatar.scene).optJSONObject("model")?.optString("url").orEmpty() }.getOrDefault("")
        if (declared.isEmpty()) return Triple("", "", "")
        val abs = runCatching { java.net.URI(Web.AVATAR_URL).resolve(declared).toString() }.getOrDefault(declared)
        val prefix = Web.AVATAR_URL.substringBefore("avatar/index.html")
        return Triple(declared, abs, if (abs.startsWith(prefix)) abs.removePrefix(prefix).substringBefore('?') else "")
    }

    private fun check(ctx: Context, path: String): Pair<OmniXray.AssetCheck, ByteArray?> {
        // a directory-like path is listed, a file is read in full (the GLB is decoded from these very bytes)
        return try {
            val bytes = ctx.assets.open(path).use { it.readBytes() }
            OmniXray.AssetCheck(path, true, bytes.size.toLong(), if (bytes.isEmpty()) "EMPTY file" else "") to bytes
        } catch (e: Throwable) {
            OmniXray.AssetCheck(path, false, 0, e.javaClass.simpleName + ": " + (e.message ?: "")) to null
        }
    }

    private fun listing(ctx: Context, dir: String): OmniXray.AssetCheck {
        val names = runCatching { ctx.assets.list(dir)?.toList() ?: emptyList() }.getOrDefault(emptyList())
        return OmniXray.AssetCheck("$dir/", names.isNotEmpty(), names.size.toLong(), names.sorted().take(12).joinToString(", ") + if (names.size > 12) ", ..." else "")
    }

    /** Runs everything; [webgl] is the GpuProbe answer (null when it timed out). Blocking apart from [GpuProbe]. */
    fun run(ctx: Context, webgl: OmniXray.WebGl?): OmniXray.Probe {
        val (declared, abs, asset) = modelRef()
        val assets = ArrayList<OmniXray.AssetCheck>()
        var glb: Glb.Report? = null
        if (asset.isNotEmpty()) {
            val (c, bytes) = check(ctx, asset)
            assets += c
            if (bytes != null) glb = Glb.inspect(bytes)
        }
        SCENE_ASSETS.forEach { assets += check(ctx, it).first }
        assets += listing(ctx, "space")
        assets += listing(ctx, "cgc/vendor")
        assets += listing(ctx, "avatar/models")
        return OmniXray.Probe(System.currentTimeMillis(), webView(), egl(), webgl, assets, glb, declared, asset, Web.AVATAR_URL)
    }

    /** The fleet's detached WebGL/WebGPU probe, as the X-Ray's WebGL block; forced so a Retest measures again. */
    fun webgl(ctx: Context, done: (OmniXray.WebGl) -> Unit) {
        GpuProbe.probe(ctx, true) { c ->
            val info = LinkedHashMap<String, String>()
            c.glInfo.forEach { (k, v) -> info[k] = v }
            done(OmniXray.WebGl(c.webgl2, c.renderer, c.vendor, c.webViewVersion, info))
        }
    }
}

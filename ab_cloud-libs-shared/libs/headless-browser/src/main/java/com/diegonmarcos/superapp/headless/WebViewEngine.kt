package com.diegonmarcos.superapp.headless

import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.diegonmarcos.cloudlib.web.CloudWeb
import com.diegonmarcos.cloudlib.web.WebProfile
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * #1498 the "This App" engine: off-screen WebView sessions, each shown in a [Presentation] on one VirtualDisplay (nothing reaches the real
 * screen), one per-agent profile (androidx.webkit multi-profile: separate cookies and storage; the shared jar when the WebView lacks it).
 * Every method runs on a worker thread; the WebView is only touched on the main thread.
 */
@SuppressLint("RequiresFeature")
class WebViewEngine(context: Context) : HeadlessEngine {
    private val ctx = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null

    private class H(val view: WebView, val pres: Presentation, val profile: String?) {
        @Volatile var loaded = false
        @Volatile var lastNet = System.currentTimeMillis()
        val net = ArrayList<JSONObject>()
        val blocked = ArrayList<String>()
    }

    private fun <T> onMain(f: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return f()
        val out = AtomicReference<Result<T>>()
        val latch = CountDownLatch(1)
        main.post { out.set(runCatching(f)); latch.countDown() }
        if (!latch.await(20, TimeUnit.SECONDS)) throw HeadlessApi.Err("timeout", "main thread busy")
        return out.get().getOrThrow()
    }

    private fun ensureDisplay(): VirtualDisplay = display ?: run {
        val r = ImageReader.newInstance(W, Hh, PixelFormat.RGBA_8888, 2)
        val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val d = dm.createVirtualDisplay("cloud-headless", W, Hh, DPI, r.surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY)
        reader = r; display = d; d
    }

    override fun newSession(profile: String): Any = onMain {
        val d = ensureDisplay()
        val name = "p_" + profile.replace(Regex("[^A-Za-z0-9_]"), "_").take(60)
        val pres = Presentation(ctx, d.display)
        val view = CloudWeb.create(pres.context, WebProfile.BROWSER)   // #1371 the fleet's one web view; JS and storage on, no file access
        val usedProfile = runCatching {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) { ProfileStore.getInstance().getOrCreateProfile(name); WebViewCompat.setProfile(view, name); name } else null
        }.getOrNull()
        val h = H(view, pres, usedProfile)
        view.webViewClient = object : WebViewClientCompat() {
            override fun onPageStarted(v: WebView, url: String, f: Bitmap?) { h.loaded = false; h.lastNet = System.currentTimeMillis() }
            override fun onPageFinished(v: WebView, url: String) { h.loaded = true; h.lastNet = System.currentTimeMillis() }
            override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? {
                val u = r.url.toString()
                synchronized(h.net) { h.net.add(JSONObject().put("url", u).put("method", r.method)); if (h.net.size > 500) h.net.removeAt(0) }
                h.lastNet = System.currentTimeMillis()
                return if (synchronized(h.blocked) { h.blocked.any { u.contains(it) } }) WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0))) else null
            }
        }
        pres.setContentView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        pres.show()
        h
    }

    override fun closeSession(handle: Any) {
        val h = handle as H
        onMain { runCatching { h.view.stopLoading(); h.pres.dismiss(); h.view.destroy() } }
    }

    private fun js(h: H, script: String, timeoutMs: Long): Any? {
        val box = AtomicReference<String?>()
        val latch = CountDownLatch(1)
        // The script returns a JSON string; evaluateJavascript wraps it once more.
        val wrapped = "(function(){try{return JSON.stringify({v:(function(){$script})()})}catch(e){return JSON.stringify({e:String(e)})}})()"
        onMain { h.view.evaluateJavascript(wrapped) { box.set(it); latch.countDown() } }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) throw HeadlessApi.Err("timeout", "script timed out")
        val outer = JSONTokener(box.get() ?: "null").nextValue()
        val o = JSONObject(outer as? String ?: "{}")
        if (o.has("e")) throw HeadlessApi.Err("bad_request", o.getString("e"))
        return o.opt("v").takeIf { it != JSONObject.NULL }
    }

    private fun q(s: String) = JSONObject.quote(s)

    private fun waitFor(timeoutMs: Long, label: String, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!ok()) {
            if (System.currentTimeMillis() > end) throw HeadlessApi.Err("timeout", "$label timed out")
            Thread.sleep(100)
        }
    }

    override fun run(handle: Any, cmd: JSONObject): Any? {
        val h = handle as H
        val t = if (cmd.has("timeoutMs")) cmd.getLong("timeoutMs") else HeadlessPolicy.CALL_TIMEOUT_MS_DEFAULT
        return when (cmd.getString("cmd")) {
            "open" -> {
                onMain { h.loaded = false; h.view.loadUrl(cmd.getString("url")) }
                waitFor(t, "open") { h.loaded }
                JSONObject().put("url", onMain { h.view.url }).put("title", onMain { h.view.title })
            }
            "wait" -> {
                when {
                    cmd.has("selector") -> waitFor(t, "wait") { js(h, "return !!document.querySelector(${q(cmd.getString("selector"))})", 5000) == true }
                    cmd.has("idle") -> waitFor(t, "wait idle") { h.loaded && System.currentTimeMillis() - h.lastNet >= 500 }
                    else -> Thread.sleep(minOf(cmd.getLong("ms"), t))
                }
                JSONObject().put("waited", true)
            }
            "eval" -> js(h, "return (0,eval)(${q(cmd.getString("js"))})", t)
            "click" -> JSONObject().put("clicked", js(h, "var e=document.querySelector(${q(cmd.getString("selector"))});if(!e)return false;e.scrollIntoView();e.click();return true", t) == true)
            "type" -> {
                val text = cmd.getString("text")
                js(h, "var e=document.querySelector(${q(cmd.getString("selector"))});if(!e)throw new Error('no such element');e.focus();" +
                    "var s=${if (cmd.optBoolean("clear")) "''" else "(e.value||'')"}+${q(text)};" +
                    "var p=e instanceof HTMLTextAreaElement?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;" +
                    "Object.getOwnPropertyDescriptor(p,'value').set.call(e,s);e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));return true", t)
                JSONObject().put("typed", text.length)
            }
            "scroll" -> js(h, if (cmd.has("selector")) "var e=document.querySelector(${q(cmd.getString("selector"))});if(e)e.scrollIntoView();return {x:scrollX,y:scrollY}"
                else "window.scrollTo(${cmd.optInt("x", 0)},${cmd.optInt("y", 0)});return {x:scrollX,y:scrollY}", t)
            "html" -> js(h, if (cmd.has("selector")) "var e=document.querySelector(${q(cmd.getString("selector"))});return e?e.innerHTML:null" else "return document.documentElement.outerHTML", t)
            "text" -> js(h, "var e=document.querySelector(${q(cmd.optString("selector", "body"))});return e?e.innerText:null", t)
            "readable" -> js(h, HeadlessReadable.script(cmd), t)
            "content" -> js(h, "return document.documentElement.outerHTML", t)
            "links" -> js(h, HeadlessExtract.linksScript(cmd.optBoolean("sameOrigin"), cmd.optBoolean("visibleOnly")), t)
            "scrape" -> js(h, HeadlessExtract.scrapeScript(cmd.getJSONArray("elements").toString(), cmd.optInt("limit", 100)), t)
            "snapshot" -> JSONObject()
                .put("url", onMain { h.view.url }).put("title", onMain { h.view.title })
                .put("html", js(h, "return document.documentElement.outerHTML", t))
                .put("screenshot", shot(h, cmd))
            "pdf" -> throw HeadlessApi.Err("unsupported", "pdf is not available in the phone engine (WebView prints no PDF off-screen); use backend container or cloudflare")
            "screenshot" -> shot(h, cmd)
            "cookies" -> cookies(h, cmd)
            "storage" -> js(h, storageJs(cmd), t)
            "network" -> when (cmd.optString("action", "log")) {
                "clear" -> { synchronized(h.net) { h.net.clear() }; JSONArray() }
                "block" -> { synchronized(h.blocked) { cmd.optJSONArray("patterns")?.let { a -> for (i in 0 until a.length()) h.blocked.add(a.getString(i)) } }; JSONObject().put("blocked", JSONArray(synchronized(h.blocked) { h.blocked.toList() })) }
                "unblock" -> { synchronized(h.blocked) { cmd.optJSONArray("patterns")?.let { a -> for (i in 0 until a.length()) h.blocked.remove(a.getString(i)) } }; JSONObject().put("blocked", JSONArray(synchronized(h.blocked) { h.blocked.toList() })) }
                else -> synchronized(h.net) { JSONArray(h.net.map { it }) }
            }
            else -> throw HeadlessApi.Err("bad_request", "unsupported ${cmd.getString("cmd")}")
        }
    }

    private fun storageJs(c: JSONObject): String {
        val area = if (c.getString("area") == "session") "sessionStorage" else "localStorage"
        val key = q(c.optString("key"))
        return "var s=window.$area;" + when (c.optString("action", "get")) {
            "set" -> "s.setItem($key,${q(c.optString("value"))});"
            "remove" -> "s.removeItem($key);"
            "clear" -> "s.clear();"
            else -> if (c.has("key")) "var r={};r[$key]=s.getItem($key);return r;" else ""
        } + "var o={};for(var i=0;i<s.length;i++){var k=s.key(i);o[k]=s.getItem(k)}return o"
    }

    private fun cookieManager(h: H): CookieManager = runCatching {
        h.profile?.let { ProfileStore.getInstance().getProfile(it)?.cookieManager }
    }.getOrNull() ?: CookieManager.getInstance()

    private fun cookies(h: H, c: JSONObject): Any {
        val url = onMain { h.view.url } ?: throw HeadlessApi.Err("bad_request", "open a page first")
        val cm = cookieManager(h)
        when (c.optString("action", "get")) {
            "set" -> c.optJSONArray("cookies")?.let { a -> for (i in 0 until a.length()) { val o = a.getJSONObject(i); cm.setCookie(o.optString("url", url), "${o.getString("name")}=${o.getString("value")}") } }
            "clear" -> { cm.removeAllCookies(null); return JSONArray() }
        }
        val out = JSONArray()
        cm.getCookie(url)?.split(";")?.map { it.trim() }?.filter { it.contains("=") }?.forEach { out.put(JSONObject().put("name", it.substringBefore("=")).put("value", it.substringAfter("="))) }
        return out
    }

    private fun shot(h: H, c: JSONObject): JSONObject = onMain {
        val bmp = Bitmap.createBitmap(W, Hh, Bitmap.Config.ARGB_8888)
        h.view.measure(android.view.View.MeasureSpec.makeMeasureSpec(W, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(Hh, android.view.View.MeasureSpec.EXACTLY))
        h.view.layout(0, 0, W, Hh)
        h.view.draw(Canvas(bmp))
        val bos = ByteArrayOutputStream()
        val jpeg = c.optString("format") == "jpeg"
        bmp.compress(if (jpeg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG, if (jpeg) c.optInt("quality", 80) else 100, bos)
        bmp.recycle()
        JSONObject().put("mime", if (jpeg) "image/jpeg" else "image/png").put("base64", Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP))
    }

    fun shutdown() {
        onMain { runCatching { display?.release() }; runCatching { reader?.close() }; display = null; reader = null }
    }

    companion object {
        const val W = 1080
        const val Hh = 1920
        const val DPI = 420
    }
}

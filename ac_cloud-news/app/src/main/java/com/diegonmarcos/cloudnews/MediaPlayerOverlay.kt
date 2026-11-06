package com.diegonmarcos.cloudnews

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * The in-app YouTube player of the Media tab: the OFFICIAL embed (`https://www.youtube-nocookie.com/embed/<id>`) in its own WebView, drawn
 * over the page. No download, no ad handling, nothing of YouTube's chrome re-created. The embed sits in a wrapper page loaded with the
 * nocookie origin as base URL (an embed with no https origin is refused by YouTube, error 153), and the wrapper reports the playback
 * position through the embed's own postMessage channel so a video resumes where it stopped.
 *
 * Modes (the page decides, from its scroll): `inline` = exactly over the page's reserved 16:9 slot; `mini` = a small player in the bottom
 * right corner, still playing, while the feed scrolls (a tap expands it, the cross closes it). The embed's fullscreen button goes
 * fullscreen with rotation (the activity declares configChanges, so the WebView and the playback survive the rotation).
 * All methods run on the main thread (the bridge hops there).
 */
@SuppressLint("SetJavaScriptEnabled")
class MediaPlayerOverlay(private val act: Activity, private val host: WebView, private val onExpand: () -> Unit) {
    private val density = act.resources.displayMetrics.density
    private val prefs = act.getSharedPreferences("media_resume", Context.MODE_PRIVATE)
    private val frame = FrameLayout(act).apply { visibility = View.GONE; setBackgroundColor(Color.BLACK); elevation = ELEVATION_DP * density }
    private val web: WebView = WebView(act)
    private val tapToExpand = View(act)
    private val close = TextView(act)
    private var fullscreenView: View? = null
    private var fullscreenCb: WebChromeClient.CustomViewCallback? = null
    private var fsHolder: FrameLayout? = null
    private var videoId = ""
    private var mode = MODE_INLINE
    private var rect = intArrayOf(0, 0, 0, 0)
    @Volatile private var lastPos = 0.0
    @Volatile private var lastState = -1

    /** Reported by the wrapper page: only a finite position and a small state code are ever read. */
    private inner class PosSink {
        @JavascriptInterface
        fun pos(t: Double, s: Int) {
            if (t.isNaN() || t < 0 || t > MAX_SECONDS) return
            lastPos = t
            if (s != lastState) {
                lastState = s
                if (s == STATE_PAUSED || s == STATE_ENDED) act.runOnUiThread { persist() }
            }
        }
    }

    init {
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.setBackgroundColor(Color.BLACK)
        web.addJavascriptInterface(PosSink(), "Player")
        web.webViewClient = object : WebViewClient() {
            // the wrapper is the only page this WebView shows: nothing navigates it elsewhere
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = request.isForMainFrame
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View, callback: CustomViewCallback) = enterFullscreen(view, callback)
            override fun onHideCustomView() = exitFullscreen()
        }
        frame.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        tapToExpand.setOnClickListener { onExpand() }
        frame.addView(tapToExpand, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        close.text = "✕"
        close.setTextColor(Color.WHITE)
        close.gravity = Gravity.CENTER
        close.setBackgroundColor(SCRIM)
        close.setOnClickListener { stop() }
        val size = (CLOSE_DP * density).toInt()
        frame.addView(close, FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.END))
        act.addContentView(frame, FrameLayout.LayoutParams(0, 0))
        host.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> if (frame.visibility == View.VISIBLE) layout() }
        tapToExpand.visibility = View.GONE
    }

    /** The embed URL is built by media-core.js (embedUrl); only that exact shape is played. */
    fun play(url: String, l: Int, t: Int, w: Int, h: Int) {
        val id = idOf(url) ?: return
        if (id != videoId) persist()
        videoId = id
        lastPos = 0.0
        lastState = -1
        rect = intArrayOf(l, t, w, h)
        mode = MODE_INLINE
        val src = url + "&enablejsapi=1&origin=" + java.net.URLEncoder.encode(ORIGIN, "UTF-8")
        web.loadDataWithBaseURL(ORIGIN + "/", wrapper(src), "text/html", "utf-8", null)
        frame.visibility = View.VISIBLE
        layout()
    }

    /** `inline` re-anchors on the page's slot (l, t, w, h in CSS px relative to the page); `mini` docks the small player. */
    fun setMode(m: String, l: Int, t: Int, w: Int, h: Int) {
        if (frame.visibility != View.VISIBLE) return
        mode = if (m == MODE_MINI) MODE_MINI else MODE_INLINE
        if (mode == MODE_INLINE) rect = intArrayOf(l, t, w, h)
        layout()
    }

    fun stop() {
        persist()
        exitFullscreen()
        web.loadUrl("about:blank")
        frame.visibility = View.GONE
        videoId = ""
        act.window.decorView.post { host.evaluateJavascript("if (window.MediaUI) MediaUI.onPlayerClosed()", null) }
    }

    fun resumeAt(id: String): Int = if (FullId.matches(id)) prefs.getFloat(id, 0f).toInt() else 0

    fun onPause() { persist(); web.onPause() }
    fun onResume() { web.onResume() }
    fun destroy() { persist(); web.destroy() }
    fun inFullscreen(): Boolean = fullscreenView != null
    fun leaveFullscreen() = exitFullscreen()

    private fun layout() {
        val lp = frame.layoutParams as FrameLayout.LayoutParams
        val loc = IntArray(2)
        host.getLocationInWindow(loc)
        lp.gravity = Gravity.TOP or Gravity.START
        if (mode == MODE_INLINE) {
            lp.width = (rect[2] * density).toInt()
            lp.height = (rect[3] * density).toInt()
            lp.leftMargin = loc[0] + (rect[0] * density).toInt()
            lp.topMargin = loc[1] + (rect[1] * density).toInt()
        } else {
            lp.width = (host.width * MINI_FRACTION).toInt()
            lp.height = lp.width * 9 / 16
            lp.leftMargin = loc[0] + host.width - lp.width - (MARGIN_DP * density).toInt()
            lp.topMargin = loc[1] + host.height - lp.height - (MINI_BOTTOM_DP * density).toInt()
        }
        frame.layoutParams = lp
        tapToExpand.visibility = if (mode == MODE_MINI) View.VISIBLE else View.GONE
    }

    private fun persist() {
        if (videoId.isEmpty()) return
        val e = prefs.edit()
        // an ended video or one stopped in its first seconds starts over next time
        if (lastState == STATE_ENDED || lastPos < MIN_RESUME_S) e.remove(videoId) else e.putFloat(videoId, lastPos.toFloat())
        e.apply()
    }

    private fun enterFullscreen(view: View, cb: WebChromeClient.CustomViewCallback) {
        if (fullscreenView != null) { cb.onCustomViewHidden(); return }
        fullscreenView = view
        fullscreenCb = cb
        val holder = FrameLayout(act).apply { setBackgroundColor(Color.BLACK); elevation = (ELEVATION_DP + 1) * density }
        holder.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        act.addContentView(holder, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        fsHolder = holder
        frame.visibility = View.INVISIBLE
        act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        WindowInsetsControllerCompat(act.window, act.window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun exitFullscreen() {
        val v = fullscreenView ?: return
        fullscreenView = null
        fsHolder?.let { (it.parent as? ViewGroup)?.removeView(it); it.removeView(v) }
        fsHolder = null
        fullscreenCb?.onCustomViewHidden()
        fullscreenCb = null
        act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        WindowInsetsControllerCompat(act.window, act.window.decorView).show(WindowInsetsCompat.Type.systemBars())
        if (videoId.isNotEmpty()) { frame.visibility = View.VISIBLE; layout() }
    }

    companion object {
        const val MODE_INLINE = "inline"
        const val MODE_MINI = "mini"
        private const val ORIGIN = "https://www.youtube-nocookie.com"
        private const val EMBED_PREFIX = "https://www.youtube-nocookie.com/embed/"
        private val FullId = Regex("^[A-Za-z0-9_-]{11}$")
        private val EmbedShape = Regex("^https://www\\.youtube-nocookie\\.com/embed/([A-Za-z0-9_-]{11})\\?autoplay=1&playsinline=1(&start=\\d{1,6})?$")
        private const val STATE_ENDED = 0
        private const val STATE_PAUSED = 2
        private const val MAX_SECONDS = 86_400.0
        private const val MIN_RESUME_S = 5.0
        private const val ELEVATION_DP = 24f
        private const val CLOSE_DP = 36f
        private const val MARGIN_DP = 12f
        private const val MINI_BOTTOM_DP = 88f
        private const val MINI_FRACTION = 0.5f
        private const val SCRIM = 0x66000000

        /** The video id of an embed URL of exactly the shape media-core.js builds, else null. */
        fun idOf(url: String): String? = if (url.startsWith(EMBED_PREFIX)) EmbedShape.matchEntire(url)?.groupValues?.get(1) else null

        /** The wrapper page: the embed in an iframe, and its position / state forwarded to [PosSink] (the embed pushes them itself once it is listened to). */
        private fun wrapper(src: String): String = """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<style>html,body{margin:0;height:100%;background:#000}iframe{border:0;width:100%;height:100%}</style></head><body>
<iframe id="p" src="$src" allow="autoplay; fullscreen; encrypted-media; picture-in-picture" allowfullscreen></iframe>
<script>
var f=document.getElementById('p'),T=0,S=-1;
function listen(){try{f.contentWindow.postMessage(JSON.stringify({event:'listening',id:1,channel:'widget'}),'*')}catch(e){}}
f.addEventListener('load',function(){listen();setTimeout(listen,700)});
window.addEventListener('message',function(e){
  if(e.source!==f.contentWindow)return;var d;try{d=JSON.parse(e.data)}catch(x){return}
  if(d.event!=='infoDelivery'||!d.info)return;
  if(typeof d.info.currentTime==='number')T=d.info.currentTime;
  if(typeof d.info.playerState==='number')S=d.info.playerState;
  if(window.Player)Player.pos(T,S);
});
</script></body></html>"""
    }
}

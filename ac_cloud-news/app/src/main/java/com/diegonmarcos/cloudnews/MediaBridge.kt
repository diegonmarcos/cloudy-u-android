package com.diegonmarcos.cloudnews

import android.app.Activity
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * JS bridge of the Media tab, exposed to news.html as `window.MediaBridge` (same safety note as [NewsBridge]: only attached because the
 * WebView loads the local asset). Everything returns a JSON string. The fetch is a job the page polls, so the JS thread never waits on the
 * network ([MediaFetcher] is the policy); the player methods hop to the main thread ([MediaPlayerOverlay]).
 */
class MediaBridge(private val act: Activity, private val player: () -> MediaPlayerOverlay?) {
    private val declJson: String by lazy { String(Base64.decode(BuildConfig.MEDIA_B64, Base64.DEFAULT), Charsets.UTF_8) }
    private val keys: EncryptedKeyStore by lazy { EncryptedKeyStore(act) }
    private val fetcher: MediaFetcher by lazy {
        MediaFetcher(JSONObject(declJson), File(act.cacheDir, "media"), { keys }, ::autoAllowed, pageUserAgent = WebSettings.getDefaultUserAgent(act))
    }
    private val pool = Executors.newFixedThreadPool(3)
    private val jobs = ConcurrentHashMap<String, String>()

    /** data/media.json, verbatim (media-core.js parses it). */
    @JavascriptInterface
    fun config(): String = declJson

    /** Starts a fetch of an allowed YouTube url; poll [fetchResult]. mode: `auto` (a page open) or `force` (pull-to-refresh). */
    @JavascriptInterface
    fun fetchStart(url: String, mode: String): String {
        val job = UUID.randomUUID().toString()
        jobs[job] = ""
        pool.execute {
            val r = try { fetcher.fetch(url, mode) }
            catch (e: Exception) { JSONObject().put("ok", false).put("code", "error").put("error", e.message ?: e.javaClass.simpleName) }
            jobs[job] = r.toString()
        }
        return JSONObject().put("job", job).toString()
    }

    /** `{pending:true}` until the job is done, then its result once (and forgotten). */
    @JavascriptInterface
    fun fetchResult(job: String): String {
        val r = jobs[job] ?: return JSONObject().put("ok", false).put("error", "unknown job").toString()
        if (r.isEmpty()) return JSONObject().put("pending", true).toString()
        jobs.remove(job)
        return r
    }

    /** Whether a YouTube Data API key is stored (it never crosses back). */
    @JavascriptInterface
    fun hasKey(): String = JSONObject().put("has", runCatching { !keys.get(MediaFetcher.KEY_ID).isNullOrBlank() }.getOrDefault(false)).toString()

    /** Stores (blank clears) the optional YouTube Data API key, encrypted. */
    @JavascriptInterface
    fun setKey(value: String): String {
        val v = value.trim()
        if (v.any { it.isWhitespace() }) return JSONObject().put("ok", false).put("error", "a key has no spaces").toString()
        return try { keys.set(MediaFetcher.KEY_ID, v); JSONObject().put("ok", true).toString() }
        catch (e: Exception) { JSONObject().put("ok", false).put("error", e.message ?: "the private store is not available").toString() }
    }

    /** Seconds to resume [id] at (0 = from the start). */
    @JavascriptInterface
    fun resume(id: String): String = JSONObject().put("sec", player()?.resumeAt(id) ?: 0).toString()

    /** Plays [url] (an embed URL of media-core.js's shape) inline in the slot l,t,w,h (CSS px relative to the page). */
    @JavascriptInterface
    fun play(url: String, l: String, t: String, w: String, h: String): String {
        if (MediaPlayerOverlay.idOf(url) == null) return JSONObject().put("ok", false).put("error", "not an embed url").toString()
        val r = rectOf(l, t, w, h)
        act.runOnUiThread { player()?.play(url, r[0], r[1], r[2], r[3]) }
        return JSONObject().put("ok", player() != null).toString()
    }

    /** `inline` (re-anchored on the slot) or `mini`. */
    @JavascriptInterface
    fun setMode(mode: String, l: String, t: String, w: String, h: String): String {
        val r = rectOf(l, t, w, h)
        act.runOnUiThread { player()?.setMode(mode, r[0], r[1], r[2], r[3]) }
        return JSONObject().put("ok", true).toString()
    }

    @JavascriptInterface
    fun stop(): String { act.runOnUiThread { player()?.stop() }; return JSONObject().put("ok", true).toString() }

    private fun rectOf(l: String, t: String, w: String, h: String): IntArray =
        listOf(l, t, w, h).map { it.toDoubleOrNull()?.toInt()?.coerceIn(0, MAX_CSS_PX) ?: 0 }.toIntArray()

    /** The automatic-refresh gate (data/media.json feed.auto_refresh, the rule of the News updater): unmetered network, and charging when asked. */
    private fun autoAllowed(): Boolean {
        val a = JSONObject(declJson).optJSONObject("feed")?.optJSONObject("auto_refresh")
        if (a?.optBoolean("require_unmetered_network", true) != false) {
            val cm = act.getSystemService(ConnectivityManager::class.java)
            val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
            if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) return false
        }
        if (a?.optBoolean("require_charging", false) == true) {
            if (act.getSystemService(BatteryManager::class.java)?.isCharging != true) return false
        }
        return true
    }

    private companion object { const val MAX_CSS_PX = 10_000 }
}

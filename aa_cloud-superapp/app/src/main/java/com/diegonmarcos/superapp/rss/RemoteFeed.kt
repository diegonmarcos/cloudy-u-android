package com.diegonmarcos.superapp.rss

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.diegonmarcos.superapp.BuildConfig
import com.diegonmarcos.superapp.core.DataBackendClient
import org.json.JSONArray
import org.json.JSONObject

/** One feed entry, as the UI needs it. The app's own type on purpose: it does
 *  not compile libs:feed, so it does not see RssClient.Item. */
data class FeedItem(val title: String, val link: String, val date: String)

/**
 * The app's front end onto the feed ENGINE (Cloud-Lib-Feed.apk, build.json::engines.feed,
 * engine-apk-split move 4). Fetching and parsing RSS vs Atom happen in the engine; this side
 * receives JSON and builds view models. libs:feed is in no module map of this app, so a feed
 * change republishes the engine APK alone.
 *
 * THE HANDSHAKE COMES FIRST AND COSTS NO BIND (the GhEngine shape): PackageManager resolves the
 * declared action in the declared package and reads its CONTRACT. Missing and too-old are two
 * different messages naming the Store, and a failed fetch is THROWN, so the pane says why
 * instead of drawing "No items".
 *
 * Off the main thread only: bind and network both block.
 */
object RemoteFeed {

    /** The engine CONTRACT meta-data key (libs/feed's manifest). */
    const val CONTRACT_KEY = "com.diegonmarcos.cloud.engine.CONTRACT"

    // The engine's method names (FeedBackendService). A string, not an import: this app does not
    // compile libs:feed, and that is the point.
    const val FETCH = "fetch"

    @Volatile private var client: DataBackendClient? = null
    @Volatile private var app: Context? = null

    /** Null when the engine is ready, else the sentence that says what to do. */
    fun check(ctx: Context): String? {
        val pm = ctx.packageManager
        val pkg = BuildConfig.FEED_ENGINE_PACKAGE
        val needed = BuildConfig.FEED_ENGINE_MIN_CONTRACT
        val service = pm.resolveService(Intent(BuildConfig.FEED_ENGINE_ACTION).setPackage(pkg), PackageManager.GET_META_DATA)
            ?.serviceInfo
        if (service == null) {
            val installed = runCatching { pm.getPackageInfo(pkg, 0) }.isSuccess
            return if (installed) tooOld(pkg, 0, needed) else "$pkg is not installed — install it from Store ▸ Cloud Constellation ▸ Libs"
        }
        val found = service.metaData?.getInt(CONTRACT_KEY, 0) ?: 0
        if (found < needed) return tooOld(pkg, found, needed)
        if (client == null) synchronized(this) {
            if (client == null) client = DataBackendClient(ctx.applicationContext, service.packageName, service.name)
        }
        return null
    }

    fun fetch(ctx: Context, url: String, maxItems: Int = 25): List<FeedItem> { return com.diegonmarcos.superapp.xray.EngineProfiler.time("feeds", "rss") {
        app = ctx.applicationContext
        val text = ask(FETCH, url, maxItems.toString())
        com.diegonmarcos.superapp.xray.EngineProfiler.bytes("feeds", "rss", text.length.toLong())
        // Success is an array; a failure is {"error": …} and is thrown, never drawn as "No items".
        val arr = runCatching { JSONArray(text) }.getOrElse {
            val error = runCatching { JSONObject(text).optString("error") }.getOrNull()
            throw IllegalStateException(error?.takeIf { it.isNotBlank() } ?: "the feed engine answered $FETCH with something that is not a list")
        }
        return buildList {
            for (i in 0 until arr.length()) {
                val o: JSONObject = arr.optJSONObject(i) ?: continue
                add(FeedItem(
                    title = o.optString("title"),
                    link  = o.optString("link"),
                    date  = o.optString("date"),
                ))
            }
        }
    } }

    private fun ask(method: String, vararg args: String): String {
        val ctx = app ?: throw IllegalStateException("the feed engine was asked before any context reached it")
        val why = check(ctx)
        val c = client
        if (why != null || c == null) throw IllegalStateException(why ?: "the feed engine is not ready")
        return c.call(method, *args)
    }

    private fun tooOld(pkg: String, found: Int, needed: Int) =
        "$pkg serves contract $found, this build needs $needed — update it from Store ▸ Cloud Constellation ▸ Libs"
}

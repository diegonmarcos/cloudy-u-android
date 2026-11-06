package com.diegonmarcos.cloudbrowser.sites

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.Executors

/** One icon a site offers. [kind] orders them (manifest, apple-touch, favicon); [size] is the largest edge it declares, 0 = unknown / any. */
data class IconCandidate(val url: String, val kind: Int, val size: Int)

/**
 * #1531 Weby's icons, pure (JVM-tested in WebyIconsTest): which picture stands for a tile. The tile's WEBSITE is asked, never a
 * guess: its page's `<link rel=manifest|apple-touch-icon|icon>` tags (and the manifest's `icons`), the best of each kind by size.
 * Preference: the web manifest's icons, then apple-touch-icon, then the favicon, then the conventional /apple-touch-icon.png and
 * /favicon.ico. SVG is skipped (nothing here decodes it). No picture found = the tile is drawn GREY with its initial.
 */
object WebyIcons {
    const val MANIFEST = 0
    const val APPLE = 1
    const val FAVICON = 2
    const val FALLBACK = 3
    /** The edge a tile wants, in px: the smallest icon at least this big wins, else the biggest there is. */
    const val WANT = 96

    private val LINK = Regex("<link\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val ATTR = Regex("([a-zA-Z_:-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>\"']+))")

    private fun attrs(tag: String): Map<String, String> =
        ATTR.findAll(tag).associate { m -> m.groupValues[1].lowercase() to (m.groups[2]?.value ?: m.groups[3]?.value ?: m.groups[4]?.value ?: "") }

    /** The biggest edge of a `sizes` value ("16x16 32x32" -> 32; "any" or missing -> 0). */
    fun sizeOf(sizes: String?): Int =
        (sizes ?: "").lowercase().split(Regex("\\s+")).mapNotNull { Regex("^(\\d+)x(\\d+)$").find(it)?.groupValues?.get(1)?.toIntOrNull() }.maxOrNull() ?: 0

    fun resolve(base: String, href: String): String? {
        val h = href.trim().replace("&amp;", "&")
        if (h.isEmpty() || h.startsWith("data:")) return null
        val u = runCatching { URI(base).resolve(h).toString() }.getOrNull() ?: return null
        return u.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    private fun isSvg(url: String) = url.substringBefore('?').lowercase().endsWith(".svg")

    /** The page's icon links as candidates, and the manifest addresses it names. */
    fun parseHtml(html: String, base: String): Pair<List<IconCandidate>, List<String>> {
        val icons = ArrayList<IconCandidate>()
        val manifests = ArrayList<String>()
        for (m in LINK.findAll(html)) {
            val a = attrs(m.value)
            val rel = (a["rel"] ?: continue).lowercase().split(Regex("\\s+"))
            val url = resolve(base, a["href"] ?: continue) ?: continue
            when {
                "manifest" in rel -> manifests += url
                rel.any { it.startsWith("apple-touch-icon") } -> icons += IconCandidate(url, APPLE, sizeOf(a["sizes"]))
                "icon" in rel -> icons += IconCandidate(url, FAVICON, sizeOf(a["sizes"]))
            }
        }
        return icons.filterNot { isSvg(it.url) } to manifests
    }

    /** A web manifest's `icons` (monochrome ones and SVGs left out), relative to the manifest's own address. */
    fun parseManifest(json: String, manifestUrl: String): List<IconCandidate> {
        val a = runCatching { JSONObject(json).optJSONArray("icons") }.getOrNull() ?: JSONArray()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            if (o.optString("purpose").contains("monochrome")) return@mapNotNull null
            val url = resolve(manifestUrl, o.optString("src")) ?: return@mapNotNull null
            if (isSvg(url) || o.optString("type").contains("svg")) null else IconCandidate(url, MANIFEST, sizeOf(o.optString("sizes")))
        }
    }

    /** Kind first; inside a kind the smallest icon of at least [want] px, then the ones of unknown size, then the bigger of the small ones. */
    fun rank(list: List<IconCandidate>, want: Int = WANT): List<IconCandidate> =
        list.distinctBy { it.url }.sortedWith(compareBy<IconCandidate>({ it.kind }, { sizeGroup(it.size, want) }, { sizeKey(it.size, want) }))

    private fun sizeGroup(size: Int, want: Int) = if (size >= want) 0 else if (size == 0) 1 else 2
    private fun sizeKey(size: Int, want: Int) = if (size >= want) size - want else if (size == 0) 0 else want - size

    /** The conventional places, tried last. */
    fun fallbacks(site: String): List<IconCandidate> {
        val origin = runCatching { URI(site).let { "${it.scheme}://${it.authority}" } }.getOrNull() ?: return emptyList()
        return listOf(IconCandidate("$origin/apple-touch-icon.png", FALLBACK, 180), IconCandidate("$origin/favicon.ico", FALLBACK, 0))
    }

    /** Every address worth trying for [site], best first. [fetchText] answers a page's / manifest's text, null when it cannot. */
    fun candidates(site: String, fetchText: (String) -> String?): List<IconCandidate> {
        val html = fetchText(site)
        val (icons, manifests) = if (html == null) emptyList<IconCandidate>() to emptyList<String>() else parseHtml(html, site)
        val fromManifest = manifests.take(2).flatMap { m -> fetchText(m)?.let { parseManifest(it, m) }.orEmpty() }
        return rank(fromManifest + icons) + fallbacks(site)
    }

    /** The first candidate whose bytes [download] gets and [decodable] accepts; null = nothing usable, the tile stays grey. */
    fun pick(list: List<IconCandidate>, download: (String) -> ByteArray?, decodable: (ByteArray) -> Boolean): ByteArray? {
        for (c in list) { val b = download(c.url) ?: continue; if (b.isNotEmpty() && decodable(b)) return b }
        return null
    }

    /** The letter a grey tile shows. */
    fun initial(label: String): String = label.trim().firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"

    fun hostOf(url: String): String = runCatching { URI(url).host.orEmpty().removePrefix("www.") }.getOrDefault("")

    /** The declared override map (ui.weby.icon_overrides: tile id or host -> icon URL). */
    fun overrideOf(overrides: JSONObject, id: String, site: String?): String? =
        (overrides.optString(id).takeIf { it.isNotBlank() } ?: site?.let { hostOf(it) }?.takeIf { it.isNotBlank() }?.let { overrides.optString(it) }?.takeIf { it.isNotBlank() })
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }

    /** The website of a tile: its declared address, else the first http(s) value of the declared website meta keys [lookup] finds for its package. */
    fun website(url: String?, id: String, keys: List<String>, lookup: (String, String) -> String?): String? =
        url ?: keys.firstNotNullOfOrNull { k -> lookup(id, k)?.takeIf { it.startsWith("http://") || it.startsWith("https://") } }
}

/**
 * The icons on this phone: on demand (a tile asks once when it first shows), persistent (`weby_icons/<host>.png` in the app's files;
 * a site with no usable icon leaves a `.none` marker, asked again after [RETRY_MS]), and never polled. BLOCKING work runs on a small
 * pool, the answer lands on the main thread. [decl] = ui.weby (icon_overrides, website_meta_keys).
 */
class WebyIconCache(context: Context, private val decl: JSONObject) {
    private val ctx = context.applicationContext
    private val dir = File(ctx.filesDir, "weby_icons").apply { mkdirs() }
    private val mem = object : LruCache<String, Bitmap>(256) {}
    private val overrides = decl.optJSONObject("icon_overrides") ?: JSONObject()
    private val keys = (0 until (decl.optJSONArray("website_meta_keys")?.length() ?: 0)).map { decl.optJSONArray("website_meta_keys")!!.optString(it) }

    /** In memory only (safe on the main thread). */
    fun peek(tile: WebyTile): Bitmap? = keyOf(tile)?.let { mem.get(it) }

    /** Ask for [tile]'s icon: [done] gets it on the main thread, null = grey. */
    fun request(tile: WebyTile, done: (Bitmap?) -> Unit) {
        POOL.execute { val b = runCatching { load(tile) }.getOrNull(); onMain { done(b) } }
    }

    /** Forget [tile]'s icon (file, marker, memory) and fetch it again. */
    fun refresh(tile: WebyTile, done: (Bitmap?) -> Unit) {
        keyOf(tile)?.let { k -> mem.remove(k); File(dir, "$k.png").delete(); File(dir, "$k.none").delete() }
        request(tile, done)
    }

    private fun metaLookup(pkg: String, key: String): String? = runCatching {
        ctx.packageManager.getApplicationInfo(pkg, PackageManager.GET_META_DATA).metaData?.getString(key)
    }.getOrNull()

    private fun siteOf(t: WebyTile) = WebyIcons.website(t.url, t.id, keys, ::metaLookup)

    private fun keyOf(t: WebyTile): String? {
        val o = WebyIcons.overrideOf(overrides, t.id, t.url)
        val basis = o ?: siteOf(t) ?: return null
        val host = WebyIcons.hostOf(basis).ifBlank { return null }
        return if (o != null) host + "_" + sha(o).take(8) else host
    }

    private fun sha(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /** BLOCKING. */
    private fun load(t: WebyTile): Bitmap? {
        val key = keyOf(t) ?: return null
        mem.get(key)?.let { return it }
        val png = File(dir, "$key.png")
        if (png.exists()) BitmapFactory.decodeFile(png.path)?.let { mem.put(key, it); return it }
        val none = File(dir, "$key.none")
        if (none.exists() && System.currentTimeMillis() - none.lastModified() < RETRY_MS) return null
        val forced = WebyIcons.overrideOf(overrides, t.id, t.url)
        var reached = false
        val list = if (forced != null) listOf(IconCandidate(forced, WebyIcons.MANIFEST, 0)) else {
            val site = siteOf(t) ?: return null
            WebyIcons.candidates(site) { u -> fetch(u, MAX_TEXT)?.also { reached = true }?.toString(Charsets.UTF_8) }
        }
        val bytes = WebyIcons.pick(list, { u -> fetch(u, MAX_IMAGE)?.also { reached = true } }, { b -> BitmapFactory.decodeByteArray(b, 0, b.size) != null })
        val bmp = bytes?.let { scaled(it) }
        if (bmp == null) { if (reached) none.writeBytes(ByteArray(0)); return null }
        png.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        mem.put(key, bmp)
        return bmp
    }

    private fun scaled(b: ByteArray): Bitmap? {
        val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(b, 0, b.size, opt)
        var s = 1
        while (opt.outWidth / (s * 2) >= 192 && opt.outHeight / (s * 2) >= 192) s *= 2
        return BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = s })
    }

    private val ua: String by lazy { runCatching { android.webkit.WebSettings.getDefaultUserAgent(ctx) }.getOrDefault("") }

    private fun fetch(url: String, max: Int): ByteArray? = iconGet(url, max, ua)

    private companion object {
        const val RETRY_MS = 7L * 24 * 3600 * 1000
        const val MAX_TEXT = 512 * 1024
        const val MAX_IMAGE = 2 * 1024 * 1024
        val POOL = Executors.newFixedThreadPool(4)
    }
}

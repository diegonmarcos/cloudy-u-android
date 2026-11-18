package com.diegonmarcos.cloudkeyboard.grabber

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.Locale

/** Why a box has nothing to show; each one has its own state in the UI. */
enum class FailReason(val label: String) { OFFLINE("OFFLINE"), ERROR("ERROR"), EMPTY("NO RESULT"), BLOCKED("BLOCKED") }

class GrabberNetException(val reason: FailReason, message: String) : IOException(message)

/** The three states every box moves through, plus the result. */
sealed interface Load<out T> {
    data object Idle : Load<Nothing>
    data object Loading : Load<Nothing>
    data class Ready<T>(val value: T, val ms: Long) : Load<T>
    data class Failed(val reason: FailReason, val detail: String) : Load<Nothing>
}

/** The media box: a map (OSM tiles around a geocoded point) or the first image. */
@Immutable
sealed interface MediaResult {
    val caption: String
    val ms: Long

    class MapView(override val caption: String, val lat: Double, val lon: Double, val zoom: Int, val tiles: List<LoadedTile>, override val ms: Long,
                  val place: PlaceInfo? = null) : MediaResult
    class Photo(override val caption: String, val image: ImageBitmap, val source: String, val pageUrl: String, override val ms: Long,
                val attribution: String = "") : MediaResult
    /** #1793 not a place and no image anywhere: a neutral frame that says so (never an empty box). */
    class Placeholder(override val caption: String, val reason: String, override val ms: Long) : MediaResult
}

class LoadedTile(val tile: TileMath.Tile, val image: ImageBitmap?)

/**
 * The network of the Grabber. Plain HttpURLConnection through the system resolver (the fleet's DNS menu applies), no key anywhere:
 * DuckDuckGo's public endpoints and OpenStreetMap's tile server and geocoder. Every failure is a [GrabberNetException] naming its
 * reason, so a box shows OFFLINE, BLOCKED or ERROR instead of an empty frame.
 */
object GrabberNet {
    private const val UA = "Mozilla/5.0 (Linux; Android 14) CloudKeyboard-Grabber/1.0 (+https://github.com/diegonmarcos/cloudy-u-android)"
    const val MAP_W = 480
    const val MAP_H = 240

    fun online(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * #1793 the User-Agent the free OSM and Wikimedia services ask for: it names the app, and nothing about its user or owner
     * (Nominatim, the OSM tile server, the FOSSGIS OSRM servers and the Wikimedia APIs refuse anonymous or browser-faked clients).
     */
    const val APP_UA = "CloudKeyboard-Grabber/1.1 (Android keyboard app; open source)"

    private fun open(url: String, referer: String?, ua: String = UA): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 7000
        c.readTimeout = 9000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", ua)
        c.setRequestProperty("Accept-Language", "en")
        if (referer != null) c.setRequestProperty("Referer", referer)
        return c
    }

    private fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: GrabberNetException) {
        throw e
    } catch (e: UnknownHostException) {
        throw GrabberNetException(FailReason.OFFLINE, "cannot resolve the host")
    } catch (e: SocketTimeoutException) {
        throw GrabberNetException(FailReason.ERROR, "timed out")
    } catch (e: IOException) {
        throw GrabberNetException(FailReason.ERROR, e.message ?: e.javaClass.simpleName)
    }

    fun text(url: String, referer: String? = null, ua: String = UA): String = guarded {
        val c = open(url, referer, ua)
        try {
            val code = c.responseCode
            if (code == 202 || code == 403 || code == 429) throw GrabberNetException(FailReason.BLOCKED, "HTTP $code")
            if (code !in 200..299) throw GrabberNetException(FailReason.ERROR, "HTTP $code")
            c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    fun bytes(url: String, referer: String? = null, ua: String = UA): ByteArray = guarded {
        val c = open(url, referer, ua)
        try {
            val code = c.responseCode
            if (code !in 200..299) throw GrabberNetException(FailReason.ERROR, "HTTP $code")
            c.inputStream.use { it.readBytes() }
        } finally { c.disconnect() }
    }

    private fun bitmap(url: String, referer: String? = null, ua: String = UA): Bitmap? {
        val b = bytes(url, referer, ua)
        return BitmapFactory.decodeByteArray(b, 0, b.size)
    }

    /** Box B: the first DuckDuckGo result. The html page first, the lite page when that one is walled, the instant answer last. */
    suspend fun firstResult(query: String): SearchHit = withContext(Dispatchers.IO) {
        val q = GrabberRouter.enc(query)
        var walled: GrabberNetException? = null
        val pages = listOf<Pair<String, (String) -> SearchHit?>>(
            "https://html.duckduckgo.com/html/?q=$q" to GrabberParsers::firstHtmlResult,
            "https://lite.duckduckgo.com/lite/?q=$q" to GrabberParsers::firstLiteResult,
        )
        for ((url, parse) in pages) {
            try {
                parse(text(url, "https://duckduckgo.com/"))?.let { return@withContext it }
            } catch (e: GrabberNetException) {
                if (e.reason == FailReason.OFFLINE) throw e
                walled = e
            }
        }
        val instant = try { text("https://api.duckduckgo.com/?q=$q&format=json&no_html=1&skip_disambig=1&t=cloudkeyboard") } catch (e: GrabberNetException) {
            if (e.reason == FailReason.OFFLINE) throw e
            throw walled ?: e
        }
        GrabberParsers.instantAnswer(instant)?.let { return@withContext it }
        throw walled ?: GrabberNetException(FailReason.EMPTY, "DuckDuckGo had no result for this text")
    }

    /** Box C, not a place: the first DuckDuckGo image, downloaded. */
    suspend fun firstImage(query: String): MediaResult.Photo = withContext(Dispatchers.IO) {
        val t0 = SystemClock.elapsedRealtime()
        val q = GrabberRouter.enc(query)
        val page = text("https://duckduckgo.com/?q=$q&iax=images&ia=images", "https://duckduckgo.com/")
        val vqd = GrabberParsers.vqd(page) ?: throw GrabberNetException(FailReason.BLOCKED, "DuckDuckGo gave no image token")
        val json = text("https://duckduckgo.com/i.js?l=us-en&o=json&q=$q&vqd=$vqd&f=,,,,,&p=1", "https://duckduckgo.com/")
        val hit = GrabberParsers.firstImage(json) ?: throw GrabberNetException(FailReason.EMPTY, "DuckDuckGo had no image for this text")
        val bmp = runCatching { bitmap(hit.thumbUrl, "https://duckduckgo.com/") }.getOrNull()
            ?: runCatching { bitmap(hit.fullUrl) }.getOrNull()
            ?: throw GrabberNetException(FailReason.ERROR, "the image could not be downloaded")
        MediaResult.Photo(hit.title.ifBlank { query }, bmp.asImageBitmap(), runCatching { java.net.URI(hit.pageUrl).host.orEmpty().removePrefix("www.") }.getOrDefault(""), hit.pageUrl, SystemClock.elapsedRealtime() - t0)
    }

    /** Box C, an address or a place: geocode it (OSM Nominatim) and fetch the tiles around the point (the fleet map's OSM tile source). */
    suspend fun map(query: String, zoom: Int): MediaResult.MapView = withContext(Dispatchers.IO) {
        val t0 = SystemClock.elapsedRealtime()
        val json = text("https://nominatim.openstreetmap.org/search?format=jsonv2&limit=1&q=${GrabberRouter.enc(query)}")
        val geo = GrabberParsers.firstGeo(json) ?: throw GrabberNetException(FailReason.EMPTY, "no place found for this text")
        val plan = TileMath.plan(geo.lat, geo.lon, zoom, MAP_W, MAP_H)
        val tiles = coroutineScope {
            plan.map { t -> async { LoadedTile(t, runCatching { bitmap(TileMath.url(t), ua = APP_UA) }.getOrNull()?.asImageBitmap()) } }.map { it.await() }
        }
        if (tiles.none { it.image != null }) throw GrabberNetException(FailReason.ERROR, "the map tiles could not be downloaded")
        MediaResult.MapView(geo.name.ifBlank { query }, geo.lat, geo.lon, zoom, tiles, SystemClock.elapsedRealtime() - t0)
    }

    // ── #1793 the Place card ─────────────────────────────────────────────────

    /** Nominatim allows one request a second; OSRM's public servers ask the same courtesy; Wikimedia a light touch. */
    private val nominatimGate = RateGate(1100)
    private val osrmGates = TravelMode.entries.filter { it.routed }.associateWith { RateGate(1000) }
    private val wikiGate = RateGate(250)
    private val routeCache = Lru<String, Leg>(64)
    private val noRoute = Lru<String, String>(64)
    private val imageCache = Lru<String, TermImage>(48)
    private val noImage = Lru<String, String>(48)

    private suspend fun gated(g: RateGate) { val w = g.reserve(SystemClock.elapsedRealtime()); if (w > 0) delay(w) }

    /** The geocoder's best hit for [query], or null: answers are cached in memory and on disk ([GeoCache]), so a re-grab asks nothing. */
    suspend fun geocode(ctx: Context, query: String): GeoHit? = withContext(Dispatchers.IO) {
        val key = query.trim().lowercase(Locale.ROOT)
        val raw = GeoCache.get(ctx, key) ?: run {
            gated(nominatimGate)
            val lang = Locale.getDefault().language.ifBlank { "en" }
            val r = text("https://nominatim.openstreetmap.org/search?format=jsonv2&limit=1&accept-language=$lang,en&q=${GrabberRouter.enc(query)}", ua = APP_UA)
            GeoCache.put(ctx, key, r)
            r
        }
        GrabberParsers.firstGeo(raw)
    }

    /** The place card's map: the tiles around [hit] with its address and type. Tiles that fail leave the grey frame and the marker. */
    suspend fun placeMap(hit: GeoHit, kind: SelectionKind, query: String): MediaResult.MapView = withContext(Dispatchers.IO) {
        val t0 = SystemClock.elapsedRealtime()
        val zoom = PlaceDecision.zoom(kind, hit.placeRank)
        val plan = TileMath.plan(hit.lat, hit.lon, zoom, MAP_W, MAP_H)
        val tiles = coroutineScope {
            plan.map { t -> async { LoadedTile(t, runCatching { bitmap(TileMath.url(t), ua = APP_UA) }.getOrNull()?.asImageBitmap()) } }.map { it.await() }
        }
        MediaResult.MapView(PlaceDecision.hitName(hit).ifBlank { query }, hit.lat, hit.lon, zoom, tiles, SystemClock.elapsedRealtime() - t0, PlaceDecision.info(hit))
    }

    /** One routed leg (car, foot or bicycle) on the free public OSRM servers; cached; EMPTY when there is no route. */
    suspend fun route(mode: TravelMode, fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Leg = withContext(Dispatchers.IO) {
        val url = mode.routeUrl(fromLat, fromLon, toLat, toLon)
        routeCache.get(url)?.let { return@withContext it }
        noRoute.get(url)?.let { throw GrabberNetException(FailReason.EMPTY, it) }
        gated(osrmGates.getValue(mode))
        val leg = PlaceParsers.osrmLeg(text(url, ua = APP_UA))
        if (leg == null) { noRoute.put(url, "no ${mode.osrmProfile} route between these points"); throw GrabberNetException(FailReason.EMPTY, "no ${mode.osrmProfile} route between these points") }
        routeCache.put(url, leg)
        leg
    }

    private fun termImageLookup(query: String): TermImage? {
        val q = query.trim().take(120)
        val langs = listOf(Locale.getDefault().language, "en").filter { it.isNotBlank() }.distinct()
        for (lang in langs) {
            val title = GrabberRouter.enc(q.replace(' ', '_')).replace("+", "%20")
            val hit = try {
                PlaceParsers.wikipediaSummary(text("https://$lang.wikipedia.org/api/rest_v1/page/summary/$title?redirect=true", ua = APP_UA), lang)
            } catch (e: GrabberNetException) { if (e.reason == FailReason.OFFLINE) throw e; null }
            if (hit != null) return hit
        }
        val commons = "https://commons.wikimedia.org/w/api.php?action=query&format=json&generator=search&gsrnamespace=6&gsrlimit=1" +
            "&gsrsearch=${GrabberRouter.enc("$q filetype:bitmap")}&prop=imageinfo&iiprop=url%7Cextmetadata&iiurlwidth=640" +
            "&iiextmetadatafilter=Artist%7CLicenseShortName"
        return try { PlaceParsers.commonsFirst(text(commons, ua = APP_UA)) } catch (e: GrabberNetException) { if (e.reason == FailReason.OFFLINE) throw e; null }
    }

    /**
     * Not a place: an image FOR the term. The Wikipedia article's thumbnail (the device language, then English), else the first
     * Wikimedia Commons file, each with its attribution; else DuckDuckGo's first image (the #1754 source, kept); else a neutral
     * placeholder that says there is none. Only OFFLINE is an error: everything else ends in a card.
     */
    suspend fun termImage(query: String): MediaResult = withContext(Dispatchers.IO) {
        val t0 = SystemClock.elapsedRealtime()
        val key = query.trim().lowercase(Locale.ROOT)
        val hit = imageCache.get(key) ?: if (noImage.get(key) != null) null else {
            gated(wikiGate)
            termImageLookup(query).also { if (it != null) imageCache.put(key, it) else noImage.put(key, "none") }
        }
        if (hit != null) {
            val bmp = runCatching { bitmap(hit.thumbUrl, ua = APP_UA) }.getOrNull()
            if (bmp != null) return@withContext MediaResult.Photo(hit.title.ifBlank { query }, bmp.asImageBitmap(), hit.source, hit.pageUrl,
                SystemClock.elapsedRealtime() - t0, hit.attribution)
        }
        try {
            firstImage(query)
        } catch (e: GrabberNetException) {
            if (e.reason == FailReason.OFFLINE) throw e
            MediaResult.Placeholder(query, "No Wikipedia or Wikimedia Commons image for this text, and no web image either (${e.reason.label.lowercase(Locale.ROOT)})",
                SystemClock.elapsedRealtime() - t0)
        }
    }
}

/** #1793 the geocoder answers on disk (cacheDir/grabber-geocode.json, the newest [MAX]), behind an in-memory LRU: Nominatim asks for caching. */
object GeoCache {
    const val MAX = 200
    private val mem = Lru<String, String>(MAX)
    @Volatile private var loaded = false

    private fun file(ctx: Context) = java.io.File(ctx.cacheDir, "grabber-geocode.json")

    @Synchronized private fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        runCatching {
            val o = org.json.JSONObject(file(ctx).readText())
            o.keys().forEach { k -> mem.put(k, o.getString(k)) }
        }
    }

    @Synchronized fun get(ctx: Context, key: String): String? { load(ctx); return mem.get(key) }

    @Synchronized fun put(ctx: Context, key: String, raw: String) {
        load(ctx)
        mem.put(key, raw)
        runCatching {
            val o = org.json.JSONObject()
            mem.snapshot().forEach { (k, v) -> o.put(k, v) }
            file(ctx).writeText(o.toString())
        }
    }
}

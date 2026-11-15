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
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

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

    class MapView(override val caption: String, val lat: Double, val lon: Double, val zoom: Int, val tiles: List<LoadedTile>, override val ms: Long) : MediaResult
    class Photo(override val caption: String, val image: ImageBitmap, val source: String, val pageUrl: String, override val ms: Long) : MediaResult
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
    const val MAP_H = 270

    fun online(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun open(url: String, referer: String?): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 7000
        c.readTimeout = 9000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", UA)
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

    fun text(url: String, referer: String? = null): String = guarded {
        val c = open(url, referer)
        try {
            val code = c.responseCode
            if (code == 202 || code == 403 || code == 429) throw GrabberNetException(FailReason.BLOCKED, "HTTP $code")
            if (code !in 200..299) throw GrabberNetException(FailReason.ERROR, "HTTP $code")
            c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    fun bytes(url: String, referer: String? = null): ByteArray = guarded {
        val c = open(url, referer)
        try {
            val code = c.responseCode
            if (code !in 200..299) throw GrabberNetException(FailReason.ERROR, "HTTP $code")
            c.inputStream.use { it.readBytes() }
        } finally { c.disconnect() }
    }

    private fun bitmap(url: String, referer: String? = null): Bitmap? {
        val b = bytes(url, referer)
        return BitmapFactory.decodeByteArray(b, 0, b.size)
    }

    /** Box B: the first DuckDuckGo result. html endpoint first, the instant answer when the html one is walled. */
    suspend fun firstResult(query: String): SearchHit = withContext(Dispatchers.IO) {
        val q = GrabberRouter.enc(query)
        var walled: GrabberNetException? = null
        val html = try { text("https://html.duckduckgo.com/html/?q=$q", "https://duckduckgo.com/") } catch (e: GrabberNetException) {
            if (e.reason == FailReason.OFFLINE) throw e
            walled = e; null
        }
        GrabberParsers.firstHtmlResult(html.orEmpty())?.let { return@withContext it }
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
            plan.map { t -> async { LoadedTile(t, runCatching { bitmap(TileMath.url(t)) }.getOrNull()?.asImageBitmap()) } }.map { it.await() }
        }
        if (tiles.none { it.image != null }) throw GrabberNetException(FailReason.ERROR, "the map tiles could not be downloaded")
        MediaResult.MapView(geo.name.ifBlank { query }, geo.lat, geo.lon, zoom, tiles, SystemClock.elapsedRealtime() - t0)
    }
}

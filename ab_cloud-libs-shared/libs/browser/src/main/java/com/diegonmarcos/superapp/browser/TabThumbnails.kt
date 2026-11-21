package com.diegonmarcos.superapp.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.View
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * What a private tab's snapshot may do. Declared once in build.json::ui.browser.tab_thumbnails.incognito.
 *  - [SKIP]   nothing is captured or stored (the default: a private tab leaves no picture behind).
 *  - [MEMORY] captured and kept in the memory LRU only; never written to disk.
 */
enum class IncognitoThumbs { SKIP, MEMORY }

/** The tab-snapshot knobs. Every default here is what build.json::ui.browser.tab_thumbnails overrides. */
data class TabThumbConfig(
    /** Capture size relative to the WebView (0.25 = a quarter of each side). */
    val scale: Float = 0.25f,
    /** Memory LRU bound, KiB of bitmap bytes. */
    val memoryKb: Int = 8 * 1024,
    /** Disk cache bound, KiB of files under cacheDir/tabs; oldest files go first. */
    val diskKb: Int = 32 * 1024,
    /** Lossy WebP quality of the disk copy. */
    val quality: Int = 70,
    val incognito: IncognitoThumbs = IncognitoThumbs.SKIP,
) {
    companion object {
        fun parse(o: JSONObject?): TabThumbConfig {
            if (o == null) return TabThumbConfig()
            val d = TabThumbConfig()
            return TabThumbConfig(
                scale = o.optDouble("scale", d.scale.toDouble()).toFloat().coerceIn(0.05f, 1f),
                memoryKb = o.optInt("memory_kb", d.memoryKb).coerceAtLeast(64),
                diskKb = o.optInt("disk_kb", d.diskKb).coerceAtLeast(64),
                quality = o.optInt("quality", d.quality).coerceIn(10, 100),
                incognito = if (o.optString("incognito", "skip").equals("memory", ignoreCase = true)) IncognitoThumbs.MEMORY else IncognitoThumbs.SKIP,
            )
        }
    }
}

/**
 * Tab snapshots: a memory LRU in front of a bounded disk cache under cacheDir/tabs, keyed by tab id.
 * Reads that must not block ([cached]) touch memory only; [load] does the disk read off the UI thread
 * and delivers on [main]. Writes go through [io]. A tab's picture is removed with [delete] when it closes.
 */
class TabThumbnails(
    private val dir: File,
    val config: TabThumbConfig = TabThumbConfig(),
    private val io: Executor = Executors.newSingleThreadExecutor { r -> Thread(r, "tab-thumbs").apply { isDaemon = true } },
    private val main: Executor = Handler(Looper.getMainLooper()).let { h -> Executor { h.post(it) } },
) {
    private val mem = object : LruCache<String, Bitmap>(config.memoryKb) {
        override fun sizeOf(key: String, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
    }
    private val icons = LruCache<String, Bitmap>(64)

    /** Store [bmp] under [id]. A private tab follows [IncognitoThumbs]; [bmp] is kept by the cache (not recycled here). */
    fun put(id: String, bmp: Bitmap, incognito: Boolean = false) {
        if (id.isBlank()) return
        if (incognito && config.incognito == IncognitoThumbs.SKIP) return
        mem.put(id, bmp)
        if (incognito) return
        io.execute { runCatching { write(id, bmp) } }
    }

    /** Memory only: safe on the UI thread. */
    fun cached(id: String): Bitmap? = mem.get(id)

    /** Memory, then disk (blocking: call off the UI thread). A disk hit is promoted into memory. */
    fun get(id: String): Bitmap? {
        mem.get(id)?.let { return it }
        val f = fileOf(id)
        if (!f.isFile) return null
        val bmp = runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull() ?: return null
        // #1841 a black picture written by an earlier build stays on disk across restarts: it is dropped, so the card shows its placeholder
        if (isBlack(bmp)) { f.delete(); bmp.recycle(); return null }
        f.setLastModified(System.currentTimeMillis())   // touched: the disk LRU keeps what is looked at
        mem.put(id, bmp)
        return bmp
    }

    /** Async [get]: [cb] runs on the main executor with the snapshot or null. */
    fun load(id: String, cb: (Bitmap?) -> Unit) {
        io.execute { val b = runCatching { get(id) }.getOrNull(); main.execute { cb(b) } }
    }

    /** Async decode of a small icon file (favicon), memoised. */
    fun loadIcon(path: String, cb: (Bitmap?) -> Unit) {
        icons.get(path)?.let { cb(it); return }
        io.execute {
            val b = runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
            if (b != null) icons.put(path, b)
            main.execute { cb(b) }
        }
    }

    /** The tab closed: its picture goes from memory and disk. */
    fun delete(id: String) {
        mem.remove(id)
        io.execute { fileOf(id).delete() }
    }

    /** "Clear browsing data > previews". */
    fun clear() {
        mem.evictAll(); icons.evictAll()
        io.execute { dir.listFiles()?.forEach { if (it.name.endsWith(EXT)) it.delete() } }
    }

    /** Draw [v] into a [TabThumbConfig.scale] bitmap on the calling (UI) thread, then [put] it. */
    fun capture(v: View, id: String, incognito: Boolean) {
        if (incognito && config.incognito == IncognitoThumbs.SKIP) return
        val w = (v.width * config.scale).toInt(); val h = (v.height * config.scale).toInt()
        if (w <= 0 || h <= 0) return
        runCatching {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
            val c = Canvas(bmp)
            c.scale(config.scale, config.scale)
            v.draw(c)
            // #1841 a WebView drawn on a software canvas while its renderer is gone, detached or not yet painted comes out black: that
            // picture must never replace a good one (it made every tab card read as a black page)
            if (isBlack(bmp)) { bmp.recycle(); return@runCatching }
            put(id, bmp, incognito)
        }
    }

    private fun fileOf(id: String) = File(dir, key(id) + EXT)

    /** True when a sample of [bmp]'s pixels is all black or fully transparent. */
    private fun isBlack(bmp: Bitmap): Boolean {
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h).also { bmp.getPixels(it, 0, w, 0, 0, w, h) }
        return blackPixels(px)
    }

    private fun write(id: String, bmp: Bitmap) {
        dir.mkdirs()
        val f = fileOf(id)
        f.outputStream().use { bmp.compress(webp(), config.quality, it) }
        trimDisk()
    }

    @Suppress("DEPRECATION")
    private fun webp(): Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP

    private fun trimDisk() {
        val files = dir.listFiles { x -> x.name.endsWith(EXT) }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        val cap = config.diskKb * 1024L
        for (f in files) {
            if (total <= cap) break
            total -= f.length(); f.delete()
        }
    }

    companion object {
        const val EXT = ".webp"
        const val DIR = "tabs"

        /** #1841 pure: every pixel (a sample of at most ~512) is black or fully transparent, the look of a WebView drawn without its renderer. */
        fun blackPixels(px: IntArray): Boolean {
            if (px.isEmpty()) return true
            val step = (px.size / 512).coerceAtLeast(1)
            var i = 0
            while (i < px.size) {
                val p = px[i]
                val alpha = p ushr 24
                if (alpha != 0 && ((p shr 16) and 0xFF) + ((p shr 8) and 0xFF) + (p and 0xFF) > 24) return false
                i += step
            }
            return true
        }

        fun key(id: String): String =
            MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }.take(40)

        @Volatile private var shared: TabThumbnails? = null

        /** One cache per process: the grid, the host and "Clear browsing data" must see the same memory. */
        fun shared(ctx: Context, config: TabThumbConfig): TabThumbnails =
            shared?.takeIf { it.config == config } ?: synchronized(this) {
                shared?.takeIf { it.config == config } ?: TabThumbnails(File(ctx.applicationContext.cacheDir, DIR), config).also { shared = it }
            }

        /** For clear-data, which has no config at hand. */
        fun sharedOrNull(): TabThumbnails? = shared
    }
}

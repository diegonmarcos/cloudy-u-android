package com.diegonmarcos.superapp.scraper

import android.content.Context
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * #1427 reads what the browser left on the phone, as files: the WebView HTTP cache (both places WebView
 * keeps it), the downloads list, the saved pages and the history (for "last visit"). BLOCKING: call off
 * the main thread.
 */
object SiteFilesSource {
    private const val MAX_ENTRY = 32L shl 20

    /** Both places WebView keeps its data (any profile: Default or Profiles/<name>), walked for simple-cache entries. */
    fun webRoots(ctx: Context): List<File> = listOf(File(ctx.cacheDir, "WebView"), File(ctx.dataDir, "app_webview"))

    private val ENTRY = Regex("^[0-9a-f]{16}_0$")

    fun cacheEntries(ctx: Context): Sequence<File> = webRoots(ctx).asSequence()
        .flatMap { r -> r.walkTopDown().maxDepth(6).filter { it.isFile && ENTRY.matches(it.name) } }

    fun load(ctx: Context): List<SiteGroup> {
        val files = ArrayList<SiteFile>()
        val stat: (String) -> Pair<Long, Long>? = { p -> File(p).takeIf { it.isFile }?.let { it.length() to it.lastModified() } }
        for (f in cacheEntries(ctx)) {
            if (f.length() > MAX_ENTRY) continue
            val e = runCatching { SimpleCache.parse(f.readBytes()) }.getOrNull() ?: continue
            files += SiteFile(SiteFiles.hostOf(e.url), SiteFiles.nameOf(e.url), f.path, FileSource.CACHE,
                e.bodyBytes.toLong(), f.lastModified(), e.url, e.contentType)
        }
        val dl = ctx.getSharedPreferences("browser_downloads", Context.MODE_PRIVATE).getString("jobs_v2", null)
        files += SiteFiles.downloads(dl, stat)
        val off = File(ctx.filesDir, "offline")
        files += SiteFiles.saved(runCatching { File(off, "index.json").readText() }.getOrNull(), off, stat)
        files += ownDownloads(ctx, stat)
        val visits = SiteFiles.visits(ctx.getSharedPreferences("browser_history", Context.MODE_PRIVATE).getString("history_json", null))
        return SiteFiles.group(files.distinctBy { it.path + it.url }, visits)
    }

    /** Files the Scrap Tools saved to the app's external folder count under the site they came from. */
    private fun ownDownloads(ctx: Context, stat: (String) -> Pair<Long, Long>?): List<SiteFile> =
        YtDownloader.externalDir(ctx).listFiles()?.filter { it.isFile }?.mapNotNull { f ->
            val (len, mod) = stat(f.path) ?: return@mapNotNull null
            SiteFile("youtube.com", f.name, f.path, FileSource.DOWNLOAD, len, mod)
        }.orEmpty()

    /** The file the viewer opens: a cache entry's body is cut out (and un-gzipped) into the cache first. */
    fun materialize(ctx: Context, f: SiteFile): File {
        if (f.source != FileSource.CACHE) return File(f.path)
        val raw = File(f.path).readBytes()
        val e = SimpleCache.parse(raw) ?: throw IllegalStateException("not a cache entry")
        var body = raw.copyOfRange(e.bodyStart, e.bodyEnd)
        if (e.contentEncoding == "gzip" || (body.size > 2 && body[0] == 0x1f.toByte() && body[1] == 0x8b.toByte())) {
            body = runCatching { GZIPInputStream(body.inputStream()).readBytes() }.getOrDefault(body)
        }
        val dir = File(ctx.cacheDir, "scraper-view").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }   // one viewed file at a time
        val name = f.name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifBlank { "file" }
        return File(dir, name).apply { writeBytes(body) }
    }
}

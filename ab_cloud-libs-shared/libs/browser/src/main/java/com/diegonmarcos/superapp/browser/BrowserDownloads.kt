package com.diegonmarcos.superapp.browser

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.URLUtil
import com.diegonmarcos.cloudlib.browsercore.BrowserCore
import com.diegonmarcos.superapp.browser.download.DlQueue
import com.diegonmarcos.superapp.browser.download.DlService
import org.json.JSONArray
import org.json.JSONObject

/** One download this browser started: DownloadManager's id plus what we asked for. */
data class BrowserDownload(val id: Long, val url: String, val file: String, val mime: String, val ts: Long)

/** #802 the download index's (de)serialisation, pure and JVM-tested. */
object BrowserDownloadIndex {
    fun toJson(list: List<BrowserDownload>): JSONArray = JSONArray().also { arr ->
        list.forEach { arr.put(JSONObject().put("id", it.id).put("url", it.url).put("file", it.file).put("mime", it.mime).put("ts", it.ts)) }
    }

    fun fromJson(raw: String?): List<BrowserDownload> {
        val arr = runCatching { JSONArray(raw ?: "[]") }.getOrDefault(JSONArray())
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            if (!o.has("id")) null
            else BrowserDownload(o.optLong("id"), o.optString("url"), o.optString("file"), o.optString("mime"), o.optLong("ts"))
        }
    }

    /** #1841 the rows of a core answer (`downloads`). */
    fun fromArray(arr: JSONArray?): List<BrowserDownload> {
        arr ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { BrowserDownload(it.optLong("id"), it.optString("url"), it.optString("file"), it.optString("mime"), it.optLong("ts")) }
    }

    /** ponytail: a flat cap; older entries drop off the index (the files stay in Downloads). */
    const val CAP = 200

    fun add(list: List<BrowserDownload>, d: BrowserDownload) = (listOf(d) + list.filterNot { it.id == d.id }).take(CAP)

    /** DownloadManager.STATUS_* in words. */
    fun status(code: Int): String = when (code) {
        1 -> "pending"; 2 -> "running"; 4 -> "paused"; 8 -> "successful"; 16 -> "failed"; else -> "unknown"
    }
}

/** What [BrowserDownloads.start] queued: the row's id and the file name it will have. */
data class BrowserDownloadStarted(val id: String, val file: String)

/**
 * The browser's downloads. A host that declares [DlService] (cloud-browser) gets the full download
 * manager (download/DlQueue: foreground service, pause / resume with Range, retries, queue
 * policy, segmented transfers, torrents). Any other host of libs:browser keeps the original path below.
 *
 * #802 downloads through Android's DownloadManager (it owns the progress
 * notification, and tapping that notification opens the file), with the page's
 * cookies and user agent so a signed-in download works. The index of what we
 * started lives in the `browser_downloads` prefs (declared, class device: it names
 * files on THIS phone).
 */
class BrowserDownloads(context: Context) {
    private val app = context.applicationContext
    private val sp = app.getSharedPreferences("browser_downloads", Context.MODE_PRIVATE)
    private val dm get() = app.getSystemService(DownloadManager::class.java)

    /**
     * #1841 the index is the browser core's (downloads: dl_index_load / dl_index_add); the stored text keeps its prefs file, key and schema,
     * [BrowserListMigration] normalises it once with a backup. [BrowserDownloadIndex] stays as the readable rules and the parity reference.
     */
    private fun raw(): String {
        BrowserListMigration.ensure(app, "downloads", "browser_downloads", KEY)
        return sp.getString(KEY, "[]") ?: "[]"
    }

    fun all(): List<BrowserDownload> = BrowserDownloadIndex.fromArray(BrowserCore.lists("dl_index_load", JSONObject().put("raw", raw())).optJSONArray("downloads"))

    /** True the first time only: the browser asks for the notification permission once. */
    fun firstNotificationAsk(): Boolean {
        if (sp.getBoolean("asked_notifications", false)) return false
        sp.edit().putBoolean("asked_notifications", true).apply()
        return true
    }

    /** Does this host run the full download manager? */
    val managed: Boolean get() = DlService.available(app)

    /**
     * Start a download with the page's cookies, agent and referer: through the download manager when
     * [managed], else DownloadManager ([enqueue]).
     */
    fun start(url: String, userAgent: String?, referer: String?, contentDisposition: String?, mime: String?, dir: String,
              cookieHeader: (String) -> String?): BrowserDownloadStarted {
        if (managed) {
            val j = DlQueue.get(app).add(url, userAgent, referer, cookieHeader(url), contentDisposition, mime, dir)
            return BrowserDownloadStarted(j.id, j.fileName)
        }
        val d = enqueue(url, userAgent, contentDisposition, mime, dir, cookieHeader)
        return BrowserDownloadStarted(d.id.toString(), d.file)
    }

    /** Queue [url]; [dir] is a sub-folder of the public Downloads ("" = Downloads itself). */
    fun enqueue(url: String, userAgent: String?, contentDisposition: String?, mime: String?, dir: String,
                cookieHeader: (String) -> String?): BrowserDownload {
        val name = URLUtil.guessFileName(url, contentDisposition, mime)
        val kind = MhtmlIntent.downloadMime(name, mime)   // a saved page keeps a type the browser opens
        val rel = BrowserBookmarkOps.normFolder(dir).let { if (it.isEmpty()) name else "$it/$name" }
        val req = DownloadManager.Request(Uri.parse(url))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, rel)
            .setTitle(name)
        cookieHeader(url)?.let { req.addRequestHeader("Cookie", it) }
        userAgent?.let { req.addRequestHeader("User-Agent", it) }
        kind?.takeIf { it.isNotBlank() }?.let { req.setMimeType(it) }
        val id = dm.enqueue(req)
        val d = BrowserDownload(id, url, rel, kind.orEmpty(), System.currentTimeMillis())
        val a = BrowserCore.lists("dl_index_add", JSONObject().put("raw", raw()).put("cap", BrowserDownloadIndex.CAP)
            .put("download", JSONObject().put("id", d.id).put("url", d.url).put("file", d.file).put("mime", d.mime).put("ts", d.ts)))
        sp.edit().putString(KEY, a.getString("raw")).apply()
        return d
    }

    /** DownloadManager's live status for [id], in words. */
    fun status(id: Long): String = runCatching {
        dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
            if (!c.moveToFirst()) "gone"
            else BrowserDownloadIndex.status(c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)))
        }
    }.getOrDefault("unknown")

    /** The content:// uri of a finished download, or null while it is not one. */
    fun uriOf(id: Long): Uri? = runCatching { dm.getUriForDownloadedFile(id) }.getOrNull()

    /** Forget the index (the files stay in Downloads). */
    fun clear() {
        sp.edit().putString(KEY, "[]").apply()
        if (managed) DlQueue.get(app).clear()
    }

    /** #887 bytes of the finished downloads this browser started (DownloadManager's own sizes). */
    fun bytes(): Long = (if (managed) DlQueue.get(app).all().sumOf { it.downloaded } else 0L) + all().sumOf { d ->
        runCatching {
            dm.query(DownloadManager.Query().setFilterById(d.id)).use { c ->
                if (c.moveToFirst() && c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) == DownloadManager.STATUS_SUCCESSFUL)
                    c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)).coerceAtLeast(0) else 0L
            }
        }.getOrDefault(0L)
    }

    /** #887 delete the files of every download this browser started (DownloadManager removes them), then the index. */
    fun removeAllFiles(): Int {
        val list = all()
        list.forEach { runCatching { dm.remove(it.id) } }
        val managedRows = if (managed) DlQueue.get(app).all().onEach { DlQueue.get(app).remove(it.id, deleteFile = true) }.size else 0
        clear()
        return list.size + managedRows
    }

    private companion object { const val KEY = "downloads_json" }
}

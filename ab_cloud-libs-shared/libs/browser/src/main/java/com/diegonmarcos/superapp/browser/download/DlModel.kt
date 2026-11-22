package com.diegonmarcos.superapp.browser.download

import com.diegonmarcos.cloudlib.browsercore.BrowserCore
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.util.Locale

/**
 * The download manager's data model: what one download is, its state, and the pure
 * rules over it (backoff, names, formatting). Plain JVM: the JVM unit tests run it
 * without Android.
 */
enum class DlState {
    QUEUED, RUNNING, PAUSED, WAITING_NETWORK, RETRYING, FAILED, COMPLETED, CANCELLED;

    val active get() = this == QUEUED || this == RUNNING || this == RETRYING || this == WAITING_NETWORK
    val finished get() = this == COMPLETED || this == CANCELLED
}

/** One byte range of a segmented download: [start]..[end] inclusive, [done] bytes written from [start]. */
data class DlSegment(val start: Long, val end: Long, val done: Long = 0) {
    val length get() = end - start + 1
    val complete get() = done >= length
    val next get() = start + done
}

/**
 * One download. [kind] is "http" or "torrent". The request inputs (cookie, agent,
 * referer) are kept so a resume after a reboot is still signed in.
 * [file] is the path in app-specific storage while it downloads; [publishedUri] is
 * the MediaStore (or shared-store) address once it is published.
 */
data class DlJob(
    val id: String,
    val url: String,
    val fileName: String,
    val mime: String = "",
    val dir: String = "",
    val kind: String = KIND_HTTP,
    val userAgent: String? = null,
    val referer: String? = null,
    val cookie: String? = null,
    val checksum: String? = null,
    val state: DlState = DlState.QUEUED,
    val total: Long = -1,
    val downloaded: Long = 0,
    val etag: String? = null,
    val lastModified: String? = null,
    val acceptRanges: Boolean? = null,
    val segments: List<DlSegment> = emptyList(),
    val attempts: Int = 0,
    val nextAttemptAt: Long = 0,
    val error: String? = null,
    val file: String = "",
    val publishedUri: String? = null,
    val saveToSharedStore: Boolean = false,
    val createdAt: Long = 0,
    val finishedAt: Long = 0,
    /** Torrent only: the info-hash, the files chosen (indices), sequential order, seeding. */
    val infoHash: String? = null,
    val selectedFiles: List<Int>? = null,
    val sequential: Boolean = false,
    val seed: Boolean = false,
) {
    val progress: Float get() = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else 0f
    val validator: String? get() = etag?.takeIf { !it.startsWith("W/") } ?: lastModified

    companion object {
        const val KIND_HTTP = "http"
        const val KIND_TORRENT = "torrent"
    }
}

/** Live numbers that are not worth persisting: bytes per second and seconds left. */
data class DlLive(val bytesPerSec: Long = 0, val etaSec: Long = -1, val peers: Int = 0, val seeds: Int = 0,
                  val upPerSec: Long = 0, val fileProgress: List<Pair<String, Float>> = emptyList())

/** The queue's policy, written by the browser's settings and read by the service. */
data class DlPolicy(
    val parallel: Int = 3,
    val wifiOnly: Boolean = false,
    val allowMetered: Boolean = true,
    val speedLimitKbps: Int = 0,
    val segments: Int = 4,
    val sharedStore: Boolean = false,
    /** Torrents: keep seeding after the download; ratio in percent (100 = 1.0) and minutes cap it, 0 = no cap. */
    val seed: Boolean = false,
    val seedRatio: Int = 0,
    val seedMinutes: Int = 0,
    val uploadKbps: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject().put("parallel", parallel).put("wifiOnly", wifiOnly).put("allowMetered", allowMetered)
        .put("speedLimitKbps", speedLimitKbps).put("segments", segments).put("sharedStore", sharedStore)
        .put("seed", seed).put("seedRatio", seedRatio).put("seedMinutes", seedMinutes).put("uploadKbps", uploadKbps)

    companion object {
        /** #1841 the clamping is the browser core's (downloads: dl_policy); the Kotlin twin without the library. */
        fun fromJson(raw: String?): DlPolicy {
            val o = BrowserCore.lists("dl_policy", JSONObject().put("raw", raw ?: "{}")).getJSONObject("policy")
            return DlPolicy(
                o.getInt("parallel"), o.getBoolean("wifiOnly"), o.getBoolean("allowMetered"), o.getInt("speedLimitKbps"), o.getInt("segments"),
                o.getBoolean("sharedStore"), o.getBoolean("seed"), o.getInt("seedRatio"), o.getInt("seedMinutes"), o.getInt("uploadKbps"))
        }
    }
}

/** JSON (de)serialisation of the jobs list, pure and JVM-tested. */
object DlCodec {
    /** #1841 the stored text is written by the browser core (every field, the unknown state as paused), from the jobs' json. */
    fun toJson(jobs: List<DlJob>): String =
        BrowserCore.lists("dl_jobs_load", JSONObject().put("jobs", JSONArray().also { arr -> jobs.forEach { arr.put(job(it)) } })).getString("raw")

    fun job(j: DlJob): JSONObject = JSONObject().apply {
        put("id", j.id); put("url", j.url); put("fileName", j.fileName); put("mime", j.mime); put("dir", j.dir)
        put("kind", j.kind); putOpt("userAgent", j.userAgent); putOpt("referer", j.referer); putOpt("cookie", j.cookie)
        putOpt("checksum", j.checksum); put("state", j.state.name); put("total", j.total); put("downloaded", j.downloaded)
        putOpt("etag", j.etag); putOpt("lastModified", j.lastModified); j.acceptRanges?.let { put("acceptRanges", it) }
        put("segments", JSONArray().also { a -> j.segments.forEach { a.put(JSONArray().put(it.start).put(it.end).put(it.done)) } })
        put("attempts", j.attempts); put("nextAttemptAt", j.nextAttemptAt); putOpt("error", j.error); put("file", j.file)
        putOpt("publishedUri", j.publishedUri); put("saveToSharedStore", j.saveToSharedStore)
        put("createdAt", j.createdAt); put("finishedAt", j.finishedAt); putOpt("infoHash", j.infoHash)
        j.selectedFiles?.let { s -> put("selectedFiles", JSONArray().also { a -> s.forEach { a.put(it) } }) }
        put("sequential", j.sequential); put("seed", j.seed)
    }

    /** #1841 the rows are read by the browser core (dl_jobs_load: defaults, unknown state = paused, [requeue] = a RUNNING / RETRYING row of a dead process goes back to QUEUED). */
    fun fromJson(raw: String?, requeue: Boolean = false): List<DlJob> {
        val arr = BrowserCore.lists("dl_jobs_load", JSONObject().put("raw", raw ?: "[]").put("requeue", requeue)).optJSONArray("jobs") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { runCatching { parse(it) }.getOrNull() } }
    }

    private fun JSONObject.str(k: String): String? = if (has(k) && !isNull(k)) optString(k) else null

    fun parse(o: JSONObject): DlJob {
        val segs = o.optJSONArray("segments") ?: JSONArray()
        val sel = o.optJSONArray("selectedFiles")
        return DlJob(
            id = o.getString("id"), url = o.optString("url"), fileName = o.optString("fileName"), mime = o.optString("mime"),
            dir = o.optString("dir"), kind = o.optString("kind", DlJob.KIND_HTTP), userAgent = o.str("userAgent"),
            referer = o.str("referer"), cookie = o.str("cookie"), checksum = o.str("checksum"),
            state = runCatching { DlState.valueOf(o.optString("state")) }.getOrDefault(DlState.PAUSED),
            total = o.optLong("total", -1), downloaded = o.optLong("downloaded"), etag = o.str("etag"),
            lastModified = o.str("lastModified"), acceptRanges = if (o.has("acceptRanges")) o.optBoolean("acceptRanges") else null,
            segments = (0 until segs.length()).mapNotNull { k -> segs.optJSONArray(k)?.let { DlSegment(it.optLong(0), it.optLong(1), it.optLong(2)) } },
            attempts = o.optInt("attempts"), nextAttemptAt = o.optLong("nextAttemptAt"), error = o.str("error"),
            file = o.optString("file"), publishedUri = o.str("publishedUri"), saveToSharedStore = o.optBoolean("saveToSharedStore"),
            createdAt = o.optLong("createdAt"), finishedAt = o.optLong("finishedAt"), infoHash = o.str("infoHash"),
            selectedFiles = sel?.let { a -> (0 until a.length()).map { a.optInt(it) } },
            sequential = o.optBoolean("sequential"), seed = o.optBoolean("seed"),
        )
    }
}

/** Retry timing: exponential with a cap and a little jitter, so a flapping server is not hammered. */
object DlBackoff {
    const val MAX_ATTEMPTS = 8
    fun delayMs(attempt: Int, jitter: Double = Math.random()): Long =   // 2s, 4s, 8s ... 512s, capped at 5 min (#1841: the browser core's dl_backoff)
        BrowserCore.lists("dl_backoff", JSONObject().put("attempt", attempt).put("jitter", jitter)).getLong("delay_ms")

    /** HTTP statuses worth another try; everything else 4xx is final. */
    fun retryable(status: Int) = BrowserCore.lists("dl_retryable", JSONObject().put("status", status)).getBoolean("retryable")
}

/** Names and sizes in words. */
object DlFormat {
    /** #1841 sizes and times in words are the browser core's (dl_format); the Kotlin twin without the library. */
    private fun fmt(kind: String, n: Long) = BrowserCore.lists("dl_format", JSONObject().put("kind", kind).put("n", n)).getString("text")

    fun bytes(n: Long): String = fmt("bytes", n)

    fun speed(bps: Long) = fmt("speed", bps)

    fun eta(sec: Long): String = fmt("eta", sec)

    /**
     * The file name a Content-Disposition header names: RFC 6266 `filename*=UTF-8''…` wins over
     * `filename="…"`; null when the header names none. Path parts are stripped (no ../ escapes).
     */
    fun dispositionName(cd: String?): String? {
        if (cd.isNullOrBlank()) return null
        Regex("""filename\*\s*=\s*([^';]+)'[^']*'([^;]+)""", RegexOption.IGNORE_CASE).find(cd)?.let { m ->
            val v = runCatching { URLDecoder.decode(m.groupValues[2].trim().trim('"'), m.groupValues[1].trim()) }.getOrNull()
            if (!v.isNullOrBlank()) return safeName(v)
        }
        Regex("""filename\s*=\s*(?:"((?:[^"\\]|\\.)*)"|([^;]+))""", RegexOption.IGNORE_CASE).find(cd)?.let { m ->
            val v = (m.groupValues[1].ifEmpty { m.groupValues[2] }).trim().replace("\\\"", "\"")
            if (v.isNotBlank()) return safeName(v)
        }
        return null
    }

    /** A name safe for one path segment. */
    fun safeName(raw: String): String = BrowserCore.lists("dl_safe_name", JSONObject().put("raw", raw)).getString("name")

    /** "name.ext" -> "name (2).ext" when [taken] says the name is in use. */
    fun unique(name: String, taken: (String) -> Boolean): String {
        if (!taken(name)) return name
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val stem = name.substring(0, dot); val ext = name.substring(dot)
        var i = 2
        while (taken("$stem ($i)$ext")) i++
        return "$stem ($i)$ext"
    }
}

/** When a seeding torrent has given back enough: the ratio (percent of its size uploaded) or the time cap. */
object DlSeedRule {
    fun reached(p: DlPolicy, uploaded: Long, size: Long, seedingSec: Long): Boolean =
        BrowserCore.lists("dl_seed_reached", JSONObject().put("policy", JSONObject().put("seedRatio", p.seedRatio).put("seedMinutes", p.seedMinutes))
            .put("uploaded", uploaded).put("size", size).put("seeding_sec", seedingSec)).getBoolean("reached")
}

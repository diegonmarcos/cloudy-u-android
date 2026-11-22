package com.diegonmarcos.cloudlib.browsercore

import org.json.JSONArray
import org.json.JSONObject

/**
 * The Kotlin twin of src/more.rs (#1841 phase 2): the stores after the lists (downloads; settings, site permissions and the page views are
 * added by their own commits). Same ops, same answers; BrowserCoreListsTest holds both to fixtures/lists-corpus.json.
 */
internal object KtMore {
    private fun text(o: JSONObject, k: String, d: String) = KtLists.text(o, k, d)
    private fun long(o: JSONObject, k: String) = KtLists.long(o, k)

    private fun int(o: JSONObject, k: String, d: Long): Long = when (val x = o.opt(k)) {
        is Number -> x.toLong()
        is String -> x.trim().toLongOrNull() ?: d
        else -> d
    }

    private fun bool(o: JSONObject, k: String, d: Boolean): Boolean = when (val x = o.opt(k)) {
        is Boolean -> x
        is String -> if (x.equals("true", true)) true else if (x.equals("false", true)) false else d
        else -> d
    }

    private fun optText(o: JSONObject, k: String): String? = if (!o.has(k) || o.isNull(k)) null else text(o, k, "")

    private fun longAt(a: JSONArray, i: Int): Long = when (val x = a.opt(i)) {
        is Number -> x.toLong()
        is String -> x.trim().toLongOrNull() ?: 0L
        else -> 0L
    }

    // ── downloads ──────────────────────────────────────────────────────────────────────────────────────────────────────

    private const val DL_INDEX_CAP = 200
    private val DL_STATES = setOf("QUEUED", "RUNNING", "PAUSED", "WAITING_NETWORK", "RETRYING", "FAILED", "COMPLETED", "CANCELLED")

    private fun indexRow(o: JSONObject) = JSONObject().put("id", long(o, "id")).put("url", text(o, "url", "")).put("file", text(o, "file", "")).put("mime", text(o, "mime", "")).put("ts", long(o, "ts"))

    private fun indexIn(a: JSONObject, key: String): List<JSONObject> {
        val src = (a.opt("raw") as? String)?.let { KtLists.rawArray(it) } ?: a.optJSONArray(key)
        return KtLists.objs(src).filter { it.has("id") }.map { indexRow(it) }
    }

    private fun indexOut(v: List<JSONObject>): JSONObject { val arr = JSONArray(v); return JSONObject().put("downloads", arr).put("raw", arr.toString()).put("count", v.size) }

    private fun job(o: JSONObject, requeue: Boolean): JSONObject? {
        if (!o.has("id") || o.isNull("id")) return null
        val j = JSONObject()
        j.put("id", text(o, "id", "")).put("url", text(o, "url", "")).put("fileName", text(o, "fileName", "")).put("mime", text(o, "mime", ""))
            .put("dir", text(o, "dir", "")).put("kind", text(o, "kind", "http"))
        for (k in listOf("userAgent", "referer", "cookie", "checksum", "etag", "lastModified", "error", "publishedUri", "infoHash")) optText(o, k)?.let { j.put(k, it) }
        var st = text(o, "state", "")
        if (st !in DL_STATES) st = "PAUSED"
        if (requeue && (st == "RUNNING" || st == "RETRYING")) st = "QUEUED"
        j.put("state", st).put("total", int(o, "total", -1)).put("downloaded", int(o, "downloaded", 0))
        if (o.has("acceptRanges")) j.put("acceptRanges", bool(o, "acceptRanges", false))
        val segs = JSONArray()
        val sa = o.optJSONArray("segments")
        if (sa != null) for (i in 0 until sa.length()) sa.optJSONArray(i)?.let { segs.put(JSONArray().put(longAt(it, 0)).put(longAt(it, 1)).put(longAt(it, 2))) }
        j.put("segments", segs).put("attempts", int(o, "attempts", 0).toInt()).put("nextAttemptAt", int(o, "nextAttemptAt", 0))
            .put("file", text(o, "file", "")).put("saveToSharedStore", bool(o, "saveToSharedStore", false))
            .put("createdAt", int(o, "createdAt", 0)).put("finishedAt", int(o, "finishedAt", 0))
        o.optJSONArray("selectedFiles")?.let { sel -> j.put("selectedFiles", JSONArray().also { r -> for (i in 0 until sel.length()) r.put(longAt(sel, i).toInt()) }) }
        j.put("sequential", bool(o, "sequential", false)).put("seed", bool(o, "seed", false))
        return j
    }

    private fun policy(a: JSONObject): JSONObject {
        val o = (a.opt("raw") as? String)?.let { try { JSONObject(it) } catch (_: Throwable) { null } } ?: JSONObject()
        fun c(k: String, d: Long, lo: Long, hi: Long) = int(o, k, d).coerceIn(lo, hi)
        val big = Int.MAX_VALUE.toLong()
        return JSONObject().put("parallel", c("parallel", 3, 1, 8)).put("wifiOnly", bool(o, "wifiOnly", false)).put("allowMetered", bool(o, "allowMetered", true))
            .put("speedLimitKbps", c("speedLimitKbps", 0, 0, big)).put("segments", c("segments", 4, 1, 8)).put("sharedStore", bool(o, "sharedStore", false))
            .put("seed", bool(o, "seed", false)).put("seedRatio", c("seedRatio", 0, 0, big)).put("seedMinutes", c("seedMinutes", 0, 0, big))
            .put("uploadKbps", c("uploadKbps", 0, 0, big))
    }

    fun bytes(n: Long): String {
        if (n < 0) return "?"
        if (n < 1024) return "$n B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = n / 1024.0
        var i = 0
        while (v >= 1024.0 && i < units.size - 1) { v /= 1024.0; i++ }
        if (v >= 100.0) return "${Math.floor(v + 0.5).toLong()} ${units[i]}"
        val t = Math.floor(v * 10.0 + 0.5).toLong()
        return "${t / 10}.${t % 10} ${units[i]}"
    }

    fun eta(sec: Long): String = when {
        sec < 0 -> "--"
        sec < 60 -> "${sec}s"
        sec < 3600 -> "${sec / 60}m ${sec % 60}s"
        else -> "${sec / 3600}h ${(sec % 3600) / 60}m"
    }

    fun safeName(raw: String): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\')
        val clean = base.map { if (it.code < 0x20 || it in "<>:\"|?*") '_' else it }.joinToString("").trim().trimStart('.')
        return if (clean.isBlank()) "download" else clean.take(200)
    }

    private fun unique(name: String, taken: Set<String>): String {
        if (name !in taken) return name
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val stem = name.substring(0, dot); val ext = name.substring(dot)
        var i = 2
        while ("$stem ($i)$ext" in taken) i++
        return "$stem ($i)$ext"
    }

    private fun jobsQuery(a: JSONObject): JSONObject {
        val jobs = KtLists.objs(a.optJSONArray("jobs"))
        val q = text(a, "query", "").lowercase()
        var idx = jobs.indices.filter { q.isBlank() || text(jobs[it], "fileName", "").lowercase().contains(q) || text(jobs[it], "url", "").lowercase().contains(q) || text(jobs[it], "state", "").lowercase().contains(q) }
        idx = when (text(a, "sort", "input")) {
            "created" -> idx.sortedByDescending { long(jobs[it], "createdAt") }
            "name" -> idx.sortedBy { text(jobs[it], "fileName", "").lowercase() }
            else -> idx
        }
        return JSONObject().put("indices", JSONArray(idx)).put("count", idx.size)
    }

    private fun downloads(op: String, a: JSONObject): JSONObject? = when (op) {
        "dl_index_load" -> indexOut(indexIn(a, "downloads"))
        "dl_index_add" -> {
            val d = indexRow(a.optJSONObject("download") ?: JSONObject())
            val id = d.getLong("id")
            val cap = KtLists.cap(a, DL_INDEX_CAP)
            indexOut((listOf(d) + indexIn(a, "downloads").filter { it.getLong("id") != id }).take(cap))
        }
        "dl_status" -> JSONObject().put("status", when (int(a, "code", 0)) { 1L -> "pending"; 2L -> "running"; 4L -> "paused"; 8L -> "successful"; 16L -> "failed"; else -> "unknown" })
        "dl_jobs_load" -> {
            val src = (a.opt("raw") as? String)?.let { KtLists.rawArray(it) } ?: a.optJSONArray("jobs")
            val requeue = bool(a, "requeue", false)
            val jobs = KtLists.objs(src).mapNotNull { job(it, requeue) }
            val arr = JSONArray(jobs)
            JSONObject().put("jobs", arr).put("raw", arr.toString()).put("count", jobs.size)
        }
        "dl_policy" -> JSONObject().put("policy", policy(a))
        "dl_backoff" -> {
            val attempt = int(a, "attempt", 0).coerceIn(0, 8).toInt()
            val jitter = (a.opt("jitter") as? Number)?.toDouble() ?: 0.0
            JSONObject().put("delay_ms", minOf(2_000L shl attempt, 300_000L) + (jitter * 1_000).toLong())
        }
        "dl_retryable" -> { val s = int(a, "status", 0); JSONObject().put("retryable", s == 408L || s == 425L || s == 429L || s >= 500) }
        "dl_format" -> {
            val n = int(a, "n", 0)
            JSONObject().put("text", when (text(a, "kind", "bytes")) { "speed" -> bytes(n) + "/s"; "eta" -> eta(n); else -> bytes(n) })
        }
        "dl_safe_name" -> JSONObject().put("name", safeName(text(a, "raw", "")))
        "dl_unique" -> {
            val t = a.optJSONArray("taken")
            JSONObject().put("name", unique(text(a, "name", ""), if (t == null) emptySet() else (0 until t.length()).mapNotNull { t.opt(it) as? String }.toSet()))
        }
        "dl_seed_reached" -> {
            val p = a.optJSONObject("policy") ?: JSONObject()
            val ratio = int(p, "seedRatio", 0); val minutes = int(p, "seedMinutes", 0)
            val up = int(a, "uploaded", 0); val size = int(a, "size", 0); val sec = int(a, "seeding_sec", 0)
            JSONObject().put("reached", (ratio > 0 && size > 0 && up * 100 >= ratio * size) || (minutes > 0 && sec >= minutes * 60))
        }
        "dl_jobs_query" -> jobsQuery(a)
        else -> null
    }

    /** The canonical list of a stored store of [kind] handled here (lists.rs `migrate`), null = not a kind of this file. */
    fun migrateKind(kind: String, src: JSONArray?): JSONArray? = when (kind) {
        "downloads" -> JSONArray(KtLists.objs(src).filter { it.has("id") }.map { indexRow(it) })
        "dljobs" -> JSONArray(KtLists.objs(src).mapNotNull { job(it, false) })
        else -> null
    }

    fun run(op: String, a: JSONObject): JSONObject = downloads(op, a) ?: throw IllegalArgumentException("unknown list op $op")
}

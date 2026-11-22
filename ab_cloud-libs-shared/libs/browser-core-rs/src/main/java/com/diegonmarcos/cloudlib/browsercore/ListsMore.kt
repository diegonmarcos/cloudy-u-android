package com.diegonmarcos.cloudlib.browsercore

import org.json.JSONArray
import org.json.JSONObject

/**
 * The Kotlin twin of src/more.rs (#1841 phase 2): the stores after the lists (downloads, settings, site permissions). Same ops, same answers; BrowserCoreListsTest holds both to fixtures/lists-corpus.json.
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

    // ── settings ───────────────────────────────────────────────────────────────────────────────────────────────────────

    private fun strs(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.opt(it).let { x -> if (x is String) x else x.toString() } }

    private fun setting(cat: List<JSONObject>, key: String) = cat.firstOrNull { text(it, "key", "") == key }

    private fun parseBool(v: String): Boolean? = when (v.lowercase()) {
        "true", "1", "on", "yes" -> true
        "false", "0", "off", "no" -> false
        else -> null
    }

    private fun splitSet(v: String): List<String> = v.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    private fun limit(s: JSONObject, k: String): Long? = (s.opt(k) as? Number)?.toLong()

    private fun err(m: String) = JSONObject().put("error", m)

    private fun setParse(a: JSONObject): JSONObject {
        val cat = KtLists.objs(a.optJSONArray("catalogue"))
        val key = text(a, "key", "")
        val s = setting(cat, key) ?: return err("$key: not a setting (see settings/catalogue)")
        val v = text(a, "raw", "").trim()
        val ty = text(s, "type", "string")
        val values = strs(s.optJSONArray("values"))
        return when (ty) {
            "bool" -> parseBool(v)?.let { JSONObject().put("value", it).put("type", "bool") } ?: err("$key: '$v' is not a bool (true/false)")
            "int" -> {
                val n = v.toIntOrNull() ?: return err("$key: '$v' is not an int")
                val mn = limit(s, "min"); val mx = limit(s, "max")
                if ((mn != null && n < mn) || (mx != null && n > mx)) err("$key: $n is out of range $mn..$mx") else JSONObject().put("value", n).put("type", "int")
            }
            "enum" -> if (v in values) JSONObject().put("value", v).put("type", "enum") else err("$key: '$v' is not one of $values")
            "set" -> {
                val set = splitSet(v)
                val bad = if (values.isEmpty()) emptyList() else set.filterNot { it in values }
                if (bad.isEmpty()) JSONObject().put("value", JSONArray(set)).put("type", "set") else err("$key: $bad not in $values")
            }
            else -> JSONObject().put("value", v).put("type", ty)
        }
    }

    private fun setSnapshot(a: JSONObject): JSONObject {
        val values = a.optJSONObject("values") ?: JSONObject()
        val out = JSONObject(); val types = JSONObject()
        for (s in KtLists.objs(a.optJSONArray("catalogue"))) {
            val key = text(s, "key", "")
            var v: Any? = if (!values.has(key) || values.isNull(key)) (if (s.has("default")) s.opt("default") else JSONObject.NULL) else values.opt(key)
            if (v is JSONArray) v = JSONArray(strs(v).sorted())
            out.put(key, v ?: JSONObject.NULL)
            types.put(key, text(s, "type", "string"))
        }
        return out.put("_types", types)
    }

    private fun setCoerce(ty: String, v: Any?): Any? = when (ty) {
        "bool" -> when (v) { is Boolean -> v; is String -> parseBool(v.trim()); else -> null }
        "int" -> when (v) {
            is Number -> v.toLong().takeIf { v !is Double && v !is Float && it in Int.MIN_VALUE..Int.MAX_VALUE }
            is String -> v.trim().toIntOrNull()
            else -> null
        }
        "set" -> when (v) {
            is JSONArray -> JSONArray((0 until v.length()).mapNotNull { v.opt(it) as? String })
            is String -> JSONArray(splitSet(v.trim()))
            else -> null
        }
        else -> when (v) { is String -> v; is Number -> v.toString(); is Boolean -> v.toString(); else -> null }
    }

    private fun setMigrate(a: JSONObject): JSONObject {
        val cat = KtLists.objs(a.optJSONArray("catalogue"))
        val fixes = JSONObject(); val drops = ArrayList<String>()
        val stored = a.optJSONObject("stored") ?: JSONObject()
        for (k in stored.keys().asSequence().toList().sorted()) {
            val s = setting(cat, k) ?: continue
            val ty = text(s, "type", "string")
            val want = when (ty) { "bool" -> "Boolean"; "int" -> "Integer"; "set" -> "Set"; else -> "String" }
            val e = stored.optJSONObject(k) ?: JSONObject()
            if (text(e, "t", "") == want) continue
            val c = setCoerce(ty, e.opt("v"))
            if (c != null) fixes.put(k, JSONObject().put("t", if (ty == "enum") "string" else ty).put("v", c)) else drops.add(k)
        }
        return JSONObject().put("fixes", fixes).put("drops", JSONArray(drops))
    }

    // ── site permissions ───────────────────────────────────────────────────────────────────────────────────────────────

    private fun pkey(host: String, perm: String) = "${host.lowercase()}|$perm"

    private fun suffixes(host: String): List<String> {
        val parts = host.lowercase().split('.').filter { it.isNotEmpty() }
        return parts.indices.map { parts.drop(it).joinToString(".") }
    }

    private fun rule(rules: JSONObject, k: String): String? = rules.opt(k) as? String

    private fun permsOp(op: String, a: JSONObject): JSONObject? {
        val rules = a.optJSONObject("rules") ?: JSONObject()
        return when (op) {
            "perm_key" -> JSONObject().put("key", pkey(text(a, "host", ""), text(a, "perm", "")))
            "perm_resolve" -> {
                val perm = text(a, "perm", "")
                JSONObject().put("value", suffixes(text(a, "host", "")).firstNotNullOfOrNull { rule(rules, pkey(it, perm)) } ?: rule(rules, pkey("*", perm)) ?: text(a, "default", "ask"))
            }
            "perm_default" -> JSONObject().put("value", rule(rules, pkey("*", text(a, "perm", ""))) ?: text(a, "default", "ask"))
            "perm_zoom" -> JSONObject().put("zoom", suffixes(text(a, "host", "")).firstNotNullOfOrNull { rule(rules, pkey(it, "zoom")) }?.toIntOrNull()?.coerceIn(50, 300) ?: JSONObject.NULL)
            "perm_hosts" -> {
                val by = java.util.TreeMap<String, JSONObject>()
                for (k in rules.keys()) {
                    val h = k.substringBefore('|', "")
                    if (h.isNotEmpty() && h != "*") by.getOrPut(h) { JSONObject() }.put(k.substringAfter('|'), rules.opt(k))
                }
                JSONObject().put("hosts", JSONArray().also { r -> by.forEach { (h, v) -> r.put(JSONObject().put("host", h).put("rules", v)) } })
            }
            "perm_ordered" -> {
                val p = KtLists.objs(a.optJSONArray("perms"))
                JSONObject().put("ids", JSONArray((p.filter { it.optBoolean("top", false) } + p.filterNot { it.optBoolean("top", false) }).map { text(it, "id", "") }))
            }
            "perm_set" -> {
                val host = text(a, "host", ""); val perm = text(a, "perm", ""); val value = text(a, "value", "")
                when {
                    host.isBlank() -> err("host= is required")
                    value !in listOf("allow", "deny", "ask") -> err("value must be one of [allow, deny, ask]")
                    else -> JSONObject().put("key", pkey(host, perm)).put("value", value)
                }
            }
            "perm_set_zoom" -> {
                val host = text(a, "host", "")
                if (host.isBlank()) JSONObject().put("action", "none")
                else {
                    val k = pkey(host, "zoom")
                    val p = (a.opt("percent") as? Number)?.toLong()
                    if (p == null) JSONObject().put("action", "remove").put("key", k)
                    else JSONObject().put("action", "put").put("key", k).put("value", p.coerceIn(50, 300).toString())
                }
            }
            "perm_forget" -> {
                val host = text(a, "host", "").lowercase()
                JSONObject().put("remove", JSONArray(strs(a.optJSONArray("keys")).filter { it.substringBefore('|') == host }))
            }
            "perm_map" -> {
                val state = text(a, "state", ""); val gesture = a.optBoolean("gesture", false)
                when (val kind = text(a, "kind", "")) {
                    "autoplay_needs_gesture" -> JSONObject().put("value", state != "allow")
                    "mixed_content" -> JSONObject().put("value", when (state) { "allow" -> 0; "deny" -> 1; else -> 2 })
                    "popup_allowed" -> JSONObject().put("value", when (state) { "allow" -> true; "deny" -> false; else -> gesture })
                    "on_unless_blocked" -> JSONObject().put("value", state != "deny")
                    else -> err("unknown mapping $kind")
                }
            }
            "perm_migrate" -> {
                val moves = JSONArray(); val drops = ArrayList<String>(); val movedTo = HashSet<String>()
                for (k in rules.keys().asSequence().toList().sorted()) {
                    val h = k.substringBefore('|', "")
                    if (h.isNotEmpty() && h.lowercase() != h) {
                        val to = h.lowercase() + "|" + k.substringAfter('|')
                        if (rules.has(to) || to in movedTo) drops.add(k) else { movedTo.add(to); moves.put(JSONObject().put("from", k).put("to", to).put("value", rules.opt(k))) }
                    }
                }
                JSONObject().put("moves", moves).put("drops", JSONArray(drops))
            }
            else -> null
        }
    }

    // ── history pages ──────────────────────────────────────────────────────────────────────────────────────────────────

    private fun offsetAt(offs: List<Pair<Long, Long>>, ts: Long): Long {
        var cur = offs.firstOrNull()?.second ?: 0L
        for ((from, off) in offs) { if (from <= ts) cur = off else break }
        return cur
    }

    private fun civil(days: Long): String {
        val z = days + 719_468
        val era = Math.floorDiv(z, 146_097L)
        val doe = Math.floorMod(z, 146_097L)
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        val yy = if (m <= 2) y + 1 else y
        return String.format(java.util.Locale.ROOT, "%04d-%02d-%02d", yy, m, d)
    }

    private fun dayOf(offs: List<Pair<Long, Long>>, ts: Long) = civil(Math.floorDiv(ts + offsetAt(offs, ts), 86_400_000L))

    private fun offsetsIn(a: JSONObject): List<Pair<Long, Long>> {
        val arr = a.optJSONArray("offsets") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONArray(it) }.map { longAt(it, 0) to longAt(it, 1) }
    }

    private class Row(val url: String, val title: String, val ts: Long, val domain: String)

    private fun visitRows(a: JSONObject): List<Row> = KtLists.objs(a.optJSONArray("visits")).map { o ->
        val url = text(o, "url", "")
        Row(url, text(o, "title", url), long(o, "ts"), (o.opt("domain") as? String) ?: KtLists.hostOf(url))
    }

    private fun rowJson(r: Row) = JSONObject().put("url", r.url).put("title", r.title).put("ts", r.ts)

    private fun views(op: String, a: JSONObject): JSONObject? = when (op) {
        "view_pages_by_day" -> {
            val offs = offsetsIn(a)
            val days = visitRows(a).sortedByDescending { it.ts }.groupBy { dayOf(offs, it.ts) }.toList().sortedByDescending { it.first }
            JSONObject().put("days", JSONArray().also { r -> days.forEach { (d, v) -> r.put(JSONObject().put("day", d).put("pages", JSONArray(v.map { rowJson(it) }))) } })
        }
        "view_domains_by_day" -> {
            val offs = offsetsIn(a)
            val days = visitRows(a).filter { it.domain.isNotEmpty() }.groupBy { dayOf(offs, it.ts) }.map { (d, v) ->
                d to v.groupBy { it.domain }.map { (dom, p) -> Triple(dom, p.size, p.maxOf { it.ts }) }.sortedWith(compareByDescending<Triple<String, Int, Long>> { it.second }.thenBy { it.first })
            }.sortedByDescending { it.first }
            JSONObject().put("days", JSONArray().also { r -> days.forEach { (d, c) ->
                r.put(JSONObject().put("day", d).put("domains", JSONArray().also { x -> c.forEach { x.put(JSONObject().put("domain", it.first).put("pages", it.second).put("last", it.third)) } }))
            } })
        }
        "view_sites" -> JSONObject().put("sites", JSONArray().also { r ->
            visitRows(a).filter { it.domain.isNotEmpty() }.groupBy { it.domain }.forEach { (d, v) ->
                r.put(JSONObject().put("domain", d).put("first", v.minOf { it.ts }).put("last", v.maxOf { it.ts }).put("pages", v.map { it.url }.distinct().size))
            }
        })
        "view_sites_sort" -> {
            val sites = KtLists.objs(a.optJSONArray("sites"))
            JSONObject().put("sites", JSONArray(if (text(a, "by", "name") == "last") sites.sortedWith(compareByDescending<JSONObject> { long(it, "last") }.thenBy { text(it, "domain", "") })
                else sites.sortedBy { text(it, "domain", "").lowercase() }))
        }
        "view_ever_seen" -> {
            val visited = strs(a.optJSONArray("visited")); val rule = strs(a.optJSONArray("rule_hosts"))
            JSONObject().put("hosts", JSONArray((visited + rule.map { it.removePrefix("www.") }.filter { it.isNotEmpty() && it != "*" }).distinct().sortedBy { it.lowercase() }))
        }
        else -> null
    }

    // ── the address bar's ranking ──────────────────────────────────────────────────────────────────────────────────────

    fun matchScore(needle: String, url: String, title: String): Double? {
        val u = url.lowercase().removePrefix("https://").removePrefix("http://").removePrefix("www.")
        val t = title.lowercase()
        return when {
            u.startsWith(needle) -> 100.0
            t.startsWith(needle) -> 90.0
            t.split(' ', '-', '_', '|', '/', '.').any { it.isNotEmpty() && it.startsWith(needle) } -> 70.0
            u.contains(needle) -> 50.0
            t.contains(needle) -> 40.0
            else -> null
        }
    }

    fun recency(ts: Long, now: Long): Double = 30.0 / (1.0 + maxOf(now - ts, 0L) / 86_400_000.0)

    private class Ranked(val score: Double, val source: String, val url: String, val label: String)

    private fun suggestOp(op: String, a: JSONObject): JSONObject? = when (op) {
        "suggest_score" -> JSONObject().put("score", matchScore(text(a, "needle", ""), text(a, "url", ""), text(a, "title", "")) ?: JSONObject.NULL)
        "suggest_rank" -> {
            val needle = text(a, "needle", ""); val now = long(a, "now"); val limit = maxOf(int(a, "limit", 5), 0L).toInt()
            val seen = HashSet<String>(); val rows = ArrayList<Ranked>()
            for (t in KtLists.objs(a.optJSONArray("tabs"))) {
                val url = text(t, "url", ""); val m = matchScore(needle, url, text(t, "title", "")) ?: continue
                if (seen.add(url)) rows.add(Ranked(m + 1000.0, "TAB", url, text(t, "title", "").ifBlank { url }))
            }
            for (v in KtLists.objs(a.optJSONArray("history"))) {
                val url = text(v, "url", ""); val m = matchScore(needle, url, text(v, "title", "")) ?: continue
                if (seen.add(url)) rows.add(Ranked(m + recency(long(v, "ts"), now), "HISTORY", url, text(v, "title", "").ifBlank { url }))
            }
            JSONObject().put("rows", JSONArray(rows.sortedByDescending { it.score }.take(limit).map { JSONObject().put("url", it.url).put("label", it.label).put("source", it.source) }))
        }
        "suggest_fav" -> {
            val toks = text(a, "query", "").lowercase().split(' ', '\t').filter { it.isNotEmpty() }
            val limit = int(a, "limit", 5)
            if (toks.isEmpty() || limit <= 0) JSONObject().put("rows", JSONArray())
            else {
                val skip = strs(a.optJSONArray("skip")).toSet()
                val rows = KtLists.objs(a.optJSONArray("bookmarks")).filter { b ->
                    val url = text(b, "url", ""); val hay = (text(b, "title", "") + " " + url).lowercase()
                    url !in skip && toks.all { hay.contains(it) }
                }.sortedByDescending { b -> toks.sumOf { matchScore(it, text(b, "url", ""), text(b, "title", "")) ?: 10.0 } / toks.size }
                    .distinctBy { text(it, "url", "") }.take(limit.toInt())
                JSONObject().put("rows", JSONArray(rows.map { JSONObject().put("url", text(it, "url", "")).put("title", text(it, "title", "")) }))
            }
        }
        else -> null
    }

    /** The canonical list of a stored store of [kind] handled here (lists.rs `migrate`), null = not a kind of this file. */
    fun migrateKind(kind: String, src: JSONArray?): JSONArray? = when (kind) {
        "downloads" -> JSONArray(KtLists.objs(src).filter { it.has("id") }.map { indexRow(it) })
        "dljobs" -> JSONArray(KtLists.objs(src).mapNotNull { job(it, false) })
        else -> null
    }

    fun run(op: String, a: JSONObject): JSONObject = downloads(op, a)
        ?: when (op) { "set_parse" -> setParse(a); "set_snapshot" -> setSnapshot(a); "set_migrate" -> setMigrate(a); else -> null }
        ?: permsOp(op, a)
        ?: views(op, a)
        ?: suggestOp(op, a)
        ?: throw IllegalArgumentException("unknown list op $op")
}

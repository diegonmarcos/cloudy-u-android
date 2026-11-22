package com.diegonmarcos.cloudlib.browsercore

import org.json.JSONArray
import org.json.JSONObject
import java.util.TreeSet

/**
 * The Kotlin twin of src/lists.rs (#1841): the list stores' logic (history, bookmarks, Quickmarks, the visit log) as `op(json) -> json`.
 * It is what Cloud Browser runs when libbrowser_core.so is not loaded or a call fails, and the parity oracle: BrowserCoreListsTest holds
 * it and the Rust core to the same fixture corpus (browser-core-rs/fixtures/lists-corpus.json). Change one, change the other.
 */
internal object KtLists {
    const val HISTORY_CAP = 500
    const val VISITLOG_CAP = 20_000
    private const val DAY_MS = 86_400_000L

    private data class V(val url: String, val title: String, val ts: Long)
    private data class B(val url: String, val title: String, val folder: String, val ts: Long)

    // ── tolerant reads ─────────────────────────────────────────────────────────────────────────────────────────────────

    private fun text(o: JSONObject, k: String, d: String): String = when (val x = o.opt(k)) {
        is String -> x
        is Number -> x.toString()
        is Boolean -> x.toString()
        else -> d
    }

    private fun long(o: JSONObject, k: String): Long = when (val x = o.opt(k)) {
        is Number -> x.toLong()
        is String -> x.trim().toLongOrNull() ?: 0L
        else -> 0L
    }

    private fun num(o: JSONObject, k: String): Long? = (o.opt(k) as? Number)?.toLong()

    private fun objs(a: JSONArray?): List<JSONObject> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }

    private fun rawArray(raw: String): JSONArray = try { JSONArray(raw) } catch (_: Throwable) { JSONArray() }

    private fun cap(a: JSONObject, d: Int): Int = num(a, "cap")?.takeIf { it > 0 }?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: d

    private fun low(s: String) = s.lowercase()

    /** A structural, key-order-free text of a json value (the comparison the migration's `changed` and the tests use). */
    fun canon(x: Any?): String = when {
        x == null || x == JSONObject.NULL -> "null"
        x is JSONObject -> x.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canon(x.opt(it)) }
        x is JSONArray -> (0 until x.length()).joinToString(",", "[", "]") { canon(x.opt(it)) }
        x is String -> JSONObject.quote(x)
        else -> x.toString()
    }

    // ── history ────────────────────────────────────────────────────────────────────────────────────────────────────────

    private fun parseVisits(a: JSONArray?): List<V> = objs(a).mapNotNull { o ->
        val url = text(o, "url", "")
        if (url.isBlank()) null else V(url, text(o, "title", url), long(o, "ts"))
    }

    private fun visitsIn(a: JSONObject): List<V> = (a.opt("raw") as? String)?.let { parseVisits(rawArray(it)) } ?: parseVisits(a.optJSONArray("visits"))

    private fun byTsDesc(v: List<V>): List<V> = v.sortedByDescending { it.ts }

    private fun visitJson(x: V) = JSONObject().put("url", x.url).put("title", x.title).put("ts", x.ts)
    private fun visitsArr(v: List<V>) = JSONArray().also { a -> v.forEach { a.put(visitJson(it)) } }
    private fun visitsOut(v: List<V>): JSONObject { val a = visitsArr(v); return JSONObject().put("visits", a).put("raw", a.toString()).put("count", v.size) }

    fun hostOf(url: String): String {
        val l = low(url)
        val rest = (if (l.contains("://")) l.substringAfter("://") else l).removePrefix("www.")
        return rest.takeWhile { it != '/' && it != '?' && it != '#' && it != ':' }
    }

    fun frecency(ts: Long, now: Long): Int {
        val days = maxOf(now - ts, 0L) / DAY_MS
        return when { days <= 4 -> 100; days <= 14 -> 70; days <= 31 -> 50; days <= 90 -> 30; else -> 10 }
    }

    private fun historyQuery(a: JSONObject): JSONObject {
        val v = visitsIn(a)
        val query = text(a, "query", "")
        val q = low(query.trim())
        val sort = text(a, "sort", "input")
        val ranked = text(a, "mode", "filter") == "ranked" && q.isNotEmpty()
        val limit = num(a, "limit")?.takeIf { it >= 0 }?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: Int.MAX_VALUE
        val now = num(a, "now") ?: (v.maxOfOrNull { it.ts } ?: 0L)
        var idx: List<Int> = if (ranked) KtSearch.search(v.map { SearchEntry(it.title, it.url, it.ts) }, query, Int.MAX_VALUE, true)
        else v.indices.filter { q.isEmpty() || low(v[it].title).contains(q) || low(v[it].url).contains(q) }
        if (!ranked) idx = when (sort) {
            "recent" -> idx.sortedByDescending { v[it].ts }
            "oldest" -> idx.sortedBy { v[it].ts }
            "title" -> idx.sortedBy { low(v[it].title) }
            "host" -> idx.sortedWith(compareBy<Int> { hostOf(v[it].url) }.thenByDescending { v[it].ts })
            "frecency" -> idx.sortedWith(compareByDescending<Int> { frecency(v[it].ts, now) }.thenByDescending { v[it].ts })
            else -> idx
        }
        idx = idx.take(limit)
        return JSONObject().put("visits", visitsArr(idx.map { v[it] })).put("indices", JSONArray(idx)).put("count", idx.size)
    }

    /** The list of an import: `doc` (a value) or `text`: a bare array or an object holding [key]. Left = the error code. */
    private fun docEntries(a: JSONObject, key: String): Pair<List<Any?>?, String?> {
        val doc: Any? = if (a.has("doc")) a.opt("doc") else {
            val t = text(a, "text", "").trim()
            try {
                when {
                    t.startsWith("[") -> JSONArray(t)
                    t.startsWith("{") -> JSONObject(t)
                    t.startsWith("\"") || t == "true" || t == "false" || t == "null" || t.toDoubleOrNull() != null -> 0
                    else -> return null to "not_json"
                }
            } catch (_: Throwable) { return null to "not_json" }
        }
        return when (doc) {
            is JSONArray -> (0 until doc.length()).map { doc.opt(it) } to null
            is JSONObject -> doc.optJSONArray(key)?.let { x -> (0 until x.length()).map { x.opt(it) } to null } ?: (null to "no_list")
            else -> null to "not_a_list"
        }
    }

    private fun historyImport(a: JSONObject): JSONObject {
        val (entries, err) = docEntries(a, "visits")
        if (entries == null) return JSONObject().put("error", err)
        val cap = cap(a, HISTORY_CAP)
        val v = byTsDesc(visitsIn(a)).toMutableList()
        val pos = HashMap<String, Int>()
        v.forEachIndexed { i, x -> if (x.url !in pos) pos[x.url] = i }
        var added = 0; var updated = 0; var skipped = 0
        for (e in entries) {
            val o = e as? JSONObject
            val url = if (o != null) text(o, "url", "") else ""
            if (o == null || url.isBlank() || url == "about:blank") { skipped++; continue }
            val nv = V(url, text(o, "title", url), long(o, "ts"))
            val i = pos[url]
            if (i != null) { if (nv.ts > v[i].ts) { v[i] = nv; updated++ } }
            else { pos[url] = v.size; v.add(nv); added++ }
        }
        return visitsOut(byTsDesc(v).take(cap)).put("added", added).put("updated", updated).put("skipped", skipped)
    }

    private fun logIn(a: JSONObject, rawKey: String, listKey: String): List<V> =
        (a.opt(rawKey) as? String)?.let { raw ->
            objs(rawArray(raw)).mapNotNull { o -> text(o, "u", "").let { u -> if (u.isBlank()) null else V(u, text(o, "t", u), long(o, "s")) } }
        } ?: parseVisits(a.optJSONArray(listKey))

    private fun visitlogMerge(a: JSONObject): JSONObject {
        val log = logIn(a, "log_raw", "log")
        val history = byTsDesc((a.opt("history_raw") as? String)?.let { parseVisits(rawArray(it)) } ?: parseVisits(a.optJSONArray("history")))
        val seen = log.mapTo(HashSet()) { it.url to it.ts }
        val fresh = history.filter { seen.add(it.url to it.ts) }
        val all = byTsDesc(fresh + log).take(cap(a, VISITLOG_CAP))
        val rows = JSONArray().also { r -> all.forEach { r.put(JSONObject().put("u", it.url).put("t", it.title).put("s", it.ts)) } }
        return visitsOut(all).put("raw", rows.toString())
    }

    // ── bookmarks ──────────────────────────────────────────────────────────────────────────────────────────────────────

    fun normFolder(f: String): String = f.split('/').map { it.trim() }.filter { it.isNotEmpty() }.joinToString("/")
    private fun inFolder(f: String, folder: String) = f == folder || f.startsWith("$folder/")

    private fun parseBms(a: JSONArray?): List<B> = objs(a).mapNotNull { o ->
        val url = text(o, "url", "")
        if (url.isBlank()) null else B(url, text(o, "title", url), text(o, "folder", ""), long(o, "ts"))
    }

    private fun bmsIn(a: JSONObject): List<B> = (a.opt("raw") as? String)?.let { parseBms(rawArray(it)) } ?: parseBms(a.optJSONArray("bookmarks"))
    private fun bmJson(b: B) = JSONObject().put("url", b.url).put("title", b.title).put("folder", b.folder).put("ts", b.ts)
    private fun bmsArr(v: List<B>) = JSONArray().also { a -> v.forEach { a.put(bmJson(it)) } }
    private fun bmsOut(v: List<B>): JSONObject { val a = bmsArr(v); return JSONObject().put("bookmarks", a).put("raw", a.toString()).put("count", v.size) }
    private fun titleOrUrl(t: String, url: String) = if (t.isBlank()) url else t

    private fun sectionsOf(v: List<B>): List<Pair<String, List<B>>> =
        v.groupBy { it.folder }.toList().sortedWith(compareBy({ it.first.isNotEmpty() }, { it.first }))

    private fun sectionsJson(s: List<Pair<String, List<B>>>) = JSONArray().also { a -> s.forEach { (f, items) -> a.put(JSONObject().put("folder", f).put("items", bmsArr(items))) } }

    private fun tokens(q: String): List<String> = low(q).split(' ', '\t').filter { it.isNotEmpty() }

    private fun bmView(a: JSONObject): JSONObject {
        var secs: List<Pair<String, List<B>>> = if (a.has("sections")) objs(a.optJSONArray("sections")).map { text(it, "folder", "") to parseBms(it.optJSONArray("items")) } else sectionsOf(bmsIn(a))
        (a.opt("chip") as? String)?.let { chip -> secs = secs.filter { it.first.substringBefore('/') == chip } }
        val toks = tokens(text(a, "query", ""))
        if (toks.isNotEmpty()) secs = secs.mapNotNull { (f, items) -> items.filter { b -> low(b.title + " " + b.url).let { h -> toks.all { h.contains(it) } } }.takeIf { it.isNotEmpty() }?.let { f to it } }
        return JSONObject().put("sections", sectionsJson(secs)).put("count", secs.sumOf { it.second.size })
    }

    private fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.opt(it) as? String }

    private fun bmMergeSeed(a: JSONObject): JSONObject {
        var v = bmsIn(a)
        val seed = a.optJSONObject("seed") ?: JSONObject()
        val items = objs(seed.optJSONArray("items"))
        val legacy = strings(seed.optJSONArray("legacyGroups")).toSet()
        val seen = strings(a.optJSONArray("seen")).toSet()
        val version = long(seed, "version")
        val now = long(a, "now")
        if (items.isEmpty()) return bmsOut(v).put("seen", JSONArray(seen.sorted())).put("changed", false)
        for (si in items) {
            val url = text(si, "url", ""); val title = text(si, "title", ""); val group = text(si, "group", "")
            if (legacy.isNotEmpty()) v = v.map { b -> if (b.url == url && b.folder in legacy) b.copy(folder = normFolder(group)) else b }
            if (url in seen) continue
            if (v.none { b -> b.url == url }) v = v + B(url, title, normFolder(group), now)
        }
        val nowSeen = seen + items.map { text(it, "url", "") }
        val changed = !(nowSeen == seen && long(a, "stored_version") >= version)
        return bmsOut(v).put("seen", JSONArray(nowSeen.sorted())).put("changed", changed).put("version", version)
    }

    private fun bmImport(a: JSONObject): JSONObject {
        val (entries, err) = docEntries(a, "bookmarks")
        if (entries == null) return JSONObject().put("error", err)
        val v = bmsIn(a).toMutableList()
        val pos = HashMap<String, Int>()
        v.forEachIndexed { i, x -> if (x.url !in pos) pos[x.url] = i }
        var added = 0; var updated = 0; var unchanged = 0; var skipped = 0
        for (e in entries) {
            val o = e as? JSONObject
            val url = if (o != null) text(o, "url", "") else ""
            if (o == null || url.isBlank()) { skipped++; continue }
            val nb = B(url, text(o, "title", url), normFolder(text(o, "folder", "")), long(o, "ts"))
            val i = pos[url]
            if (i != null) { if (nb.ts > v[i].ts) { v[i] = nb; updated++ } else unchanged++ }
            else { pos[url] = v.size; v.add(nb); added++ }
        }
        return bmsOut(v).put("added", added).put("updated", updated).put("unchanged", unchanged).put("skipped", skipped)
    }

    // ── Quickmarks ─────────────────────────────────────────────────────────────────────────────────────────────────────

    private fun webyTile(x: JSONObject): JSONObject {
        val o = JSONObject().put("id", text(x, "id", "")).put("label", text(x, "label", ""))
        val u = x.opt("url")
        o.put("url", if (u is String && u.isNotBlank()) u else JSONObject.NULL)
        text(x, "pkg", "").takeIf { it.isNotBlank() }?.let { o.put("pkg", it) }
        return o
    }

    private fun webyParse(a: JSONArray?): JSONArray = JSONArray().also { out ->
        objs(a).forEach { o ->
            out.put(JSONObject().put("title", text(o, "title", "")).put("tiles", JSONArray().also { t -> objs(o.optJSONArray("tiles")).forEach { t.put(webyTile(it)) } })
                .put("folders", webyParse(o.optJSONArray("folders"))))
        }
    }

    private fun webyAll(a: JSONObject): JSONObject {
        val unsortedKey = "Unsorted"
        val by = LinkedHashMap<String, MutableList<JSONObject>>()
        for (b in objs(a.optJSONArray("browser"))) {
            val url = text(b, "url", ""); val title = text(b, "title", ""); val folder = text(b, "folder", "")
            val host = url.substringAfter("://").substringBefore('/')
            by.getOrPut(if (folder.isBlank()) unsortedKey else folder) { ArrayList() }
                .add(JSONObject().put("id", "u:$url").put("label", if (title.isBlank()) host else title).put("url", url))
        }
        val unsorted = by.remove(unsortedKey).orEmpty()
        val out = JSONArray()
        if (by.isNotEmpty() || unsorted.isNotEmpty()) out.put(JSONObject().put("title", "Browser bookmarks").put("tiles", JSONArray(unsorted))
            .put("folders", JSONArray().also { s -> by.forEach { (f, t) -> s.put(JSONObject().put("title", f.replace('/', '-')).put("tiles", JSONArray(t)).put("folders", JSONArray())) } }))
        val quick = a.optJSONArray("quick")
        if (quick != null && quick.length() > 0) out.put(JSONObject().put("title", "Quickmarks").put("tiles", JSONArray()).put("folders", quick))
        val cat = a.optJSONArray("catalogue")
        if (cat != null) for (i in 0 until cat.length()) out.put(cat.opt(i))
        return JSONObject().put("folders", out)
    }

    // ── migration of the Kotlin on-disk format ─────────────────────────────────────────────────────────────────────────

    private fun migrate(a: JSONObject): JSONObject {
        val kind = text(a, "kind", "")
        val raw = text(a, "raw", "")
        val src: JSONArray? = try { JSONArray(raw) } catch (_: Throwable) { null }
        val list: JSONArray = when (kind) {
            "history" -> visitsArr(byTsDesc(parseVisits(src)))
            "bookmarks" -> bmsArr(parseBms(src))
            "weby" -> webyParse(src)
            "visitlog" -> JSONArray().also { r -> logIn(JSONObject().put("raw", raw), "raw", "none").forEach { r.put(JSONObject().put("u", it.url).put("t", it.title).put("s", it.ts)) } }
            else -> throw IllegalArgumentException("unknown list kind $kind")
        }
        val count = list.length()
        val changed = src == null || canon(src) != canon(list)
        return JSONObject().put("kind", kind).put("valid", src != null).put("list", list).put("raw", list.toString()).put("count", count)
            .put("dropped", maxOf((src?.length() ?: 0) - count, 0)).put("changed", changed)
    }

    // ── dispatch ───────────────────────────────────────────────────────────────────────────────────────────────────────

    fun run(op: String, a: JSONObject): JSONObject = when (op) {
        "history_load" -> visitsOut(byTsDesc(visitsIn(a)))
        "history_record" -> {
            val url = text(a, "url", "")
            var v = byTsDesc(visitsIn(a))
            if (url.isBlank() || url == "about:blank") visitsOut(v).put("recorded", false)
            else {
                v = listOf(V(url, titleOrUrl(text(a, "title", ""), url), long(a, "now"))) + v.filter { it.url != url }
                visitsOut(v.take(cap(a, HISTORY_CAP))).put("recorded", true)
            }
        }
        "history_dedupe" -> { val seen = HashSet<String>(); visitsOut(byTsDesc(visitsIn(a)).filter { seen.add(it.url) }) }
        "history_query" -> historyQuery(a)
        "history_export" -> { val v = visitsIn(a); JSONObject().put("doc", JSONObject().put("format", "cloud-browser-history").put("version", 1).put("count", v.size).put("visits", visitsArr(v))) }
        "history_import" -> historyImport(a)
        "visitlog_merge" -> visitlogMerge(a)
        "bm_load" -> bmsOut(bmsIn(a))
        "bm_add" -> {
            val url = text(a, "url", "")
            bmsOut(listOf(B(url, titleOrUrl(text(a, "title", ""), url), normFolder(text(a, "folder", "")), long(a, "now"))) + bmsIn(a).filter { it.url != url })
        }
        "bm_remove" -> { val url = text(a, "url", ""); bmsOut(bmsIn(a).filter { it.url != url }) }
        "bm_has" -> { val url = text(a, "url", ""); JSONObject().put("has", bmsIn(a).any { it.url == url }) }
        "bm_folders" -> JSONObject().put("folders", JSONArray(bmsIn(a).flatMap { b ->
            val parts = b.folder.split('/').filter { it.isNotEmpty() }
            parts.indices.map { parts.subList(0, it + 1).joinToString("/") }
        }.toCollection(TreeSet<String>()).toList()))
        "bm_move_folder" -> {
            val from = normFolder(text(a, "from", "")); val to = normFolder(text(a, "to", ""))
            val v = bmsIn(a)
            bmsOut(if (from.isEmpty()) v else v.map { if (inFolder(it.folder, from)) it.copy(folder = normFolder(to + it.folder.removePrefix(from))) else it })
        }
        "bm_delete_folder" -> { val f = normFolder(text(a, "folder", "")); val v = bmsIn(a); bmsOut(if (f.isEmpty()) v else v.filterNot { inFolder(it.folder, f) }) }
        "bm_edit" -> {
            val url = text(a, "url", ""); val v = bmsIn(a)
            val cur = v.firstOrNull { it.url == url }
            bmsOut(if (cur == null) v else v.map { if (it.url == url) cur.copy(title = titleOrUrl(text(a, "title", ""), url), folder = normFolder(text(a, "folder", ""))) else it })
        }
        "bm_sections" -> JSONObject().put("sections", sectionsJson(sectionsOf(bmsIn(a))))
        "bm_view" -> bmView(a)
        "bm_merge_seed" -> bmMergeSeed(a)
        "bm_export" -> { val v = bmsIn(a); JSONObject().put("doc", JSONObject().put("format", "cloud-browser-bookmarks").put("version", 1).put("count", v.size).put("bookmarks", bmsArr(v))) }
        "bm_import" -> bmImport(a)
        "weby_load" -> {
            val src: JSONArray? = try { JSONArray(text(a, "raw", "")) } catch (_: Throwable) { null }
            JSONObject().put("folders", if (src == null) JSONObject.NULL else webyParse(src))
        }
        "weby_encode" -> JSONObject().put("raw", webyParse(a.optJSONArray("folders")).toString())
        "weby_all" -> webyAll(a)
        "migrate" -> migrate(a)
        else -> throw IllegalArgumentException("unknown list op $op")
    }
}

package com.diegonmarcos.cloudlib.browsercore

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The Kotlin twins of the three Rust jobs (#1841): what Cloud Browser runs when libbrowser_core.so is not loaded (no .so for the ABI, a
 * load failure) or a call fails. Same contract, same answers: BrowserCoreParityTest holds both to the same cases.
 */

/** One tab's page state, enough to rebuild it when its renderer, its process or its WebView is gone. */
data class TabSnapshot(
    val key: String,
    val url: String,
    val title: String = "",
    val scrollX: Int = 0,
    val scrollY: Int = 0,
    val favicon: String = "",
    /** The back/forward stack, oldest first, urls without secrets (the page's own addresses, as WebView keeps them). */
    val history: List<String> = emptyList(),
    /** Where in [history] the tab is. */
    val index: Int = 0,
    /** The file holding WebView's own saveState bundle for this tab ("" = none). */
    val stateFile: String = "",
    val updatedAt: Long = 0L,
) {
    fun toJson(): JSONObject = JSONObject().put("key", key).put("url", url).put("title", title).put("scrollX", scrollX).put("scrollY", scrollY)
        .put("favicon", favicon).put("history", JSONArray(history)).put("index", index).put("stateFile", stateFile).put("updatedAt", updatedAt)

    companion object {
        fun fromJson(o: JSONObject): TabSnapshot {
            val h = o.optJSONArray("history")
            return TabSnapshot(
                key = o.optString("key"), url = o.optString("url"), title = o.optString("title"),
                scrollX = o.optInt("scrollX"), scrollY = o.optInt("scrollY"), favicon = o.optString("favicon"),
                history = if (h == null) emptyList() else (0 until h.length()).map { h.optString(it) },
                index = o.optInt("index"), stateFile = o.optString("stateFile"), updatedAt = o.optLong("updatedAt"),
            )
        }
    }
}

/** A row of the history / bookmark list the search runs over. */
data class SearchEntry(val title: String, val url: String, val ts: Long = 0L)

/** The Kotlin session store: one JSON file, rewritten atomically (temp + rename), newest first on list. */
internal class KtSessionStore(private val file: File) {
    private val rows = LinkedHashMap<String, TabSnapshot>()

    init {
        runCatching {
            if (file.isFile) {
                val a = JSONArray(file.readText())
                for (i in 0 until a.length()) a.optJSONObject(i)?.let { TabSnapshot.fromJson(it) }?.takeIf { it.key.isNotEmpty() }?.let { rows[it.key] = it }
            }
        }
    }

    @Synchronized fun put(s: TabSnapshot): Boolean {
        if (s.key.isEmpty() || s.url.isEmpty()) return false
        rows[s.key] = s
        return flush()
    }

    @Synchronized fun get(key: String): TabSnapshot? = rows[key]
    @Synchronized fun list(): List<TabSnapshot> = rows.values.sortedWith(compareByDescending<TabSnapshot> { it.updatedAt }.thenBy { it.key })
    @Synchronized fun delete(key: String): Boolean = (rows.remove(key) != null).also { if (it) flush() }
    @Synchronized fun count(): Int = rows.size
    @Synchronized fun prune(keep: Collection<String>): Int {
        val live = keep.toSet()
        val gone = rows.keys.filter { it !in live }
        gone.forEach { rows.remove(it) }
        if (gone.isNotEmpty()) flush()
        return gone.size
    }

    private fun flush(): Boolean = runCatching {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(JSONArray(rows.values.map { it.toJson() }).toString())
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        true
    }.getOrDefault(false)
}

/** The History page's filter and the ranked address-bar search, as in search.rs. */
internal object KtSearch {
    private fun hostPath(url: String): String {
        val l = url.lowercase()
        val rest = if (l.contains("://")) l.substringAfter("://") else l
        return rest.removePrefix("www.")
    }

    fun search(entries: List<SearchEntry>, query: String, limit: Int, ranked: Boolean): List<Int> {
        val q = query.trim().lowercase()
        val max = limit.coerceAtLeast(0)
        if (q.isEmpty()) return (0 until minOf(entries.size, max)).toList()
        if (!ranked) {
            val out = ArrayList<Int>()
            for ((i, e) in entries.withIndex()) {
                if (out.size >= max) break
                if (e.title.lowercase().contains(q) || e.url.lowercase().contains(q)) out.add(i)
            }
            return out
        }
        val words = q.split(Regex("\\s+")).filter { it.isNotEmpty() }
        class Hit(val score: Int, val ts: Long, val idx: Int)
        val hits = ArrayList<Hit>()
        for ((i, e) in entries.withIndex()) {
            val title = e.title.lowercase()
            val url = e.url.lowercase()
            val hp = hostPath(url)
            var total = 0
            var all = true
            for (w in words) {
                val s = when {
                    hp.startsWith(w) -> 100
                    title.startsWith(w) -> 80
                    title.split(Regex("[^\\p{L}\\p{N}]")).any { it.startsWith(w) } -> 60
                    title.contains(w) || url.contains(w) -> 30
                    else -> 0
                }
                if (s == 0) { all = false; break }
                total += s
            }
            if (all) hits.add(Hit(total, e.ts, i))
        }
        return hits.sortedWith(compareByDescending<Hit> { it.score }.thenByDescending { it.ts }.thenBy { it.idx }).map { it.idx }.take(max)
    }
}

/** The content-blocking host matcher, as in filter.rs (and the BrowserTrackers it replaces). */
internal class KtFilter(rules: String) {
    private val block = HashSet<String>()
    private val allow = HashSet<String>()
    private val paths = ArrayList<String>()
    private val pathAllow = ArrayList<String>()

    init {
        for (raw in rules.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("!") || line.startsWith("#")) continue
            val exception = line.startsWith("@@")
            val body = line.removePrefix("@@").substringBefore('$')
            if (body.startsWith("/")) {
                val seg = body.removePrefix("/")
                if (seg.isNotEmpty()) (if (exception) pathAllow else paths).add("/" + seg.lowercase())
                continue
            }
            val host = body.trim().removePrefix("||").trimEnd('^').trim().trimEnd('.').lowercase()
            if (!host.contains('.')) continue
            if (exception) allow.add(host) else block.add(host)
        }
    }

    private fun suffixes(host: String): List<String> {
        val parts = host.lowercase().split('.').filter { it.isNotEmpty() }
        return parts.indices.map { parts.drop(it).joinToString(".") }
    }

    fun isTracker(host: String): Boolean {
        val s = suffixes(host)
        return s.any { it.contains('.') && it in block } && s.none { it.contains('.') && it in allow }
    }

    fun shouldBlock(requestHost: String, pageHost: String, mainFrame: Boolean): Boolean {
        if (mainFrame || requestHost.isEmpty() || !isTracker(requestHost)) return false
        return !isTracker(pageHost)
    }

    fun shouldBlockUrl(url: String, requestHost: String, pageHost: String, mainFrame: Boolean): Boolean {
        if (mainFrame) return false
        if (shouldBlock(requestHost, pageHost, false)) return true
        if (paths.isEmpty()) return false
        val u = url.lowercase()
        return paths.any { u.contains(it) } && pathAllow.none { u.contains(it) }
    }
}

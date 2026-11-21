package com.diegonmarcos.cloudlib.browsercore

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

/** The Kotlin twins of omnibox.rs, reader.rs, the download machine, the list merge and the rows table (#1841): same rules, same answers. */
internal object KtOmnibox {
    fun isPrivateHost(h: String): Boolean {
        if (h == "localhost" || h == "127.0.0.1" || h.startsWith("10.") || h.startsWith("192.168.")) return true
        if (h.startsWith("172.")) {
            val r = h.removePrefix("172.")
            if (r.length >= 3 && r[2] == '.') return when (r[0]) {
                '1' -> r[1] in '6'..'9'
                '2' -> r[1].isDigit()
                '3' -> r[1] == '0' || r[1] == '1'
                else -> false
            }
        }
        return false
    }

    private fun hostOf(s: String) = s.substringBefore("/").substringBefore(":")

    fun isUrlLike(raw: String): Boolean {
        val s = raw.trim()
        if (s.isEmpty()) return false
        if (s.contains("://")) return true
        if (s.any { it.isWhitespace() }) return false
        val host = hostOf(s)
        if (isPrivateHost(host) || host.endsWith(".local") || host.endsWith(".lan")) return true
        val dot = host.lastIndexOf('.')
        if (dot <= 0 || dot == host.length - 1) return false
        val tld = host.substring(dot + 1)
        return tld.length >= 2 && tld.all { it.isLetter() }
    }

    fun normalizeUrl(raw: String): String {
        val s = raw.trim()
        if (s.isEmpty()) return ""
        if (s.contains("://")) return s
        val host = hostOf(s)
        return if (isPrivateHost(host) || host.endsWith(".local") || host.endsWith(".lan")) "http://$s" else "https://$s"
    }

    fun resolve(input: String, template: String): String {
        val s = input.trim()
        if (s.isEmpty()) return ""
        return if (isUrlLike(s)) normalizeUrl(s) else template.replace("{q}", URLEncoder.encode(s, "UTF-8"))
    }

    fun start(unclean: Boolean, hasLast: Boolean, restoreSetting: Boolean, openUrl: Boolean, kept: Boolean): Pair<String, Boolean> = when {
        openUrl -> "open_url" to false
        kept -> "kept" to false
        hasLast && restoreSetting && !unclean -> "restore" to false
        else -> "grid" to (unclean && hasLast)
    }
}

internal object KtReader {
    private val SKIP = setOf("script", "style", "noscript", "nav", "header", "footer", "aside", "form", "svg", "template")
    private val BLOCK = setOf("p", "h1", "h2", "h3", "h4", "h5", "h6", "li", "blockquote", "pre", "div")
    private val HEAD = setOf("h1", "h2", "h3", "h4", "h5", "h6")

    fun decode(s: String) = s.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
    private fun squash(s: String) = s.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

    fun extract(html: String): Pair<String, List<String>> {
        var i = 0
        val skip = ArrayList<String>()
        var inTitle = false
        val title = StringBuilder()
        val cur = StringBuilder()
        var curHead = false
        val out = ArrayList<String>()
        fun flush(head: Boolean) {
            val t = squash(decode(cur.toString())); cur.setLength(0)
            if (t.isNotEmpty() && (head || t.codePointCount(0, t.length) >= 25)) out.add(t)
        }
        while (i < html.length) {
            if (html[i] == '<') {
                val end = html.indexOf('>', i)
                if (end < 0) break
                val tag = html.substring(i + 1, end)
                i = end + 1
                if (tag.startsWith("!") || tag.startsWith("?")) continue
                val closing = tag.startsWith("/")
                val name = tag.trimStart('/').takeWhile { it.isLetterOrDigit() && it.code < 128 }.lowercase()
                if (name.isEmpty()) continue
                if (name == "title") { inTitle = !closing; continue }
                if (name in SKIP) {
                    if (closing) { val p = skip.lastIndexOf(name); if (p >= 0) while (skip.size > p) skip.removeAt(skip.size - 1) }
                    else if (!tag.endsWith("/")) { flush(curHead); skip.add(name) }
                    continue
                }
                if (name in BLOCK) { flush(curHead); curHead = !closing && name in HEAD }
            } else {
                val start = i
                while (i < html.length && html[i] != '<') i++
                val text = html.substring(start, i)
                if (inTitle) title.append(text) else if (skip.isEmpty()) { cur.append(text); cur.append(' ') }
            }
        }
        flush(curHead)
        return squash(decode(title.toString())) to out
    }
}

internal object KtMisc {
    fun downloadNext(state: String, event: String): String? = when (state to event) {
        "queued" to "start" -> "running"
        "running" to "pause" -> "paused"
        "paused" to "resume" -> "running"
        "running" to "finish" -> "done"
        "running" to "fail", "queued" to "fail", "paused" to "fail" -> "failed"
        "queued" to "cancel", "running" to "cancel", "paused" to "cancel" -> "cancelled"
        "failed" to "retry", "cancelled" to "retry" -> "queued"
        else -> null
    }

    fun merge(lists: List<String>): String {
        val seen = LinkedHashSet<String>()
        for (l in lists) for (line in l.lines()) { val t = line.trim(); if (t.isEmpty() || t.startsWith("!") || t.startsWith("#")) continue; seen.add(t) }
        return seen.joinToString("\n")
    }
}

/** The rows table as a JSON file, newest first on list (the twin of store.rs rows). */
internal class KtRows(private val file: File) {
    private class Row(val v: String, val ts: Long)
    private val ns = LinkedHashMap<String, LinkedHashMap<String, Row>>()

    init {
        runCatching {
            if (file.isFile) {
                val a = JSONArray(file.readText())
                for (i in 0 until a.length()) a.optJSONObject(i)?.let { ns.getOrPut(it.optString("ns")) { LinkedHashMap() }[it.optString("key")] = Row(it.optString("val"), it.optLong("ts")) }
            }
        }
    }

    @Synchronized fun put(n: String, k: String, v: String, ts: Long): Boolean {
        if (n.isEmpty() || k.isEmpty()) return false
        ns.getOrPut(n) { LinkedHashMap() }[k] = Row(v, ts); return flush()
    }
    @Synchronized fun get(n: String, k: String): Pair<String, Long>? = ns[n]?.get(k)?.let { it.v to it.ts }
    @Synchronized fun list(n: String, limit: Int): List<Triple<String, String, Long>> =
        (ns[n] ?: emptyMap<String, Row>()).entries.sortedWith(compareByDescending<Map.Entry<String, Row>> { it.value.ts }.thenBy { it.key }).take(limit.coerceAtLeast(0)).map { Triple(it.key, it.value.v, it.value.ts) }
    @Synchronized fun delete(n: String, k: String): Boolean = (ns[n]?.remove(k) != null).also { if (it) flush() }
    @Synchronized fun clear(n: String): Int { val c = ns[n]?.size ?: 0; ns.remove(n); if (c > 0) flush(); return c }
    @Synchronized fun count(n: String): Int = ns[n]?.size ?: 0
    @Synchronized fun historyVisit(url: String, title: String, ts: Long, cap: Int) {
        put("history", url, JSONObject().put("url", url).put("title", title).put("ts", ts).toString(), ts)
        val keep = list("history", cap.coerceAtLeast(1)).map { it.first }.toSet()
        ns["history"]?.keys?.filter { it !in keep }?.forEach { ns["history"]?.remove(it) }
        flush()
    }

    private fun flush(): Boolean = runCatching {
        file.parentFile?.mkdirs()
        val a = JSONArray()
        for ((n, m) in ns) for ((k, r) in m) a.put(JSONObject().put("ns", n).put("key", k).put("val", r.v).put("ts", r.ts))
        val tmp = File(file.path + ".tmp"); tmp.writeText(a.toString())
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        true
    }.getOrDefault(false)
}

package com.diegonmarcos.hardtechbiomed

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * #1777 the served content of Hardtech-BioMed: what each tab teaches and which parts of which 3D model it lights. It is DATA read at run time
 * (data/fleet/biomed/content.json, next to the models), never baked into the APK: the guard of runtime data holds. A topic names the parts it
 * highlights by a SPEC (libs:cad-core viewer, `cadHighlight`): a substring of a node's name, or `system:<id>` for every node of a body system.
 * Pure JVM (org.json only), so the parser and the spec rules are unit-tested.
 */
data class Topic(val id: String, val title: String, val text: String, val highlight: List<String>, val isolate: Boolean = false)
data class Group(val id: String, val label: String, val topics: List<Topic>)
data class Layer(val id: String, val label: String, val match: List<String>)
data class ModelRef(val id: String, val file: String, val sha256: String, val size: Long, val licence: String, val credit: String)
data class SceneTab(
    val id: String, val model: String, val title: String, val intro: String,
    val groups: List<Group>, val layers: List<Layer>, val hidden: List<String>,
)
data class BioContent(val models: Map<String, ModelRef>, val tabs: Map<String, SceneTab>, val notice: String)

object BioParse {
    private val SHA = Regex("[0-9a-f]{64}")

    private fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }

    /** The content, or null when [json] is not a content document of schemaVersion 1 (the caller then shows "content unavailable", never a crash). */
    fun parse(json: String): BioContent? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        if (root.optInt("schemaVersion", 0) != 1) return null
        val mo = root.optJSONObject("models") ?: return null
        val models = mo.keys().asSequence().mapNotNull { id ->
            val o = mo.optJSONObject(id) ?: return@mapNotNull null
            val file = o.optString("file"); val sha = o.optString("sha256")
            if (file.isBlank() || !SHA.matches(sha)) null
            else id to ModelRef(id, file, sha, o.optLong("size"), o.optString("licence"), o.optString("credit"))
        }.toMap()
        val to = root.optJSONObject("tabs") ?: return null
        val tabs = to.keys().asSequence().mapNotNull { id ->
            val o = to.optJSONObject(id) ?: return@mapNotNull null
            val model = o.optString("model")
            if (model !in models) return@mapNotNull null
            val groups = (0 until (o.optJSONArray("groups")?.length() ?: 0)).mapNotNull { gi ->
                val g = o.getJSONArray("groups").optJSONObject(gi) ?: return@mapNotNull null
                val topics = (0 until (g.optJSONArray("topics")?.length() ?: 0)).mapNotNull { ti ->
                    val t = g.getJSONArray("topics").optJSONObject(ti) ?: return@mapNotNull null
                    val tid = t.optString("id"); val title = t.optString("title")
                    if (tid.isBlank() || title.isBlank()) null
                    else Topic(tid, title, t.optString("text"), strings(t.optJSONArray("highlight")), t.optBoolean("isolate", false))
                }
                Group(g.optString("id"), g.optString("label"), topics)
            }
            val layers = (0 until (o.optJSONArray("layers")?.length() ?: 0)).mapNotNull { li ->
                val l = o.getJSONArray("layers").optJSONObject(li) ?: return@mapNotNull null
                Layer(l.optString("id"), l.optString("label"), strings(l.optJSONArray("match")))
            }
            id to SceneTab(id, model, o.optString("title"), o.optString("intro"), groups, layers, strings(o.optJSONArray("hidden")))
        }.toMap()
        return BioContent(models, tabs, root.optString("notice"))
    }

    /** The page call that highlights [spec] (empty clears it); [isolate] hides every other part instead of dimming it. */
    fun highlightJs(spec: List<String>, isolate: Boolean): String =
        "window.cadHighlight&&window.cadHighlight(${JSONArray(spec)},'${if (isolate) "isolate" else "dim"}')"

    fun focusJs(spec: List<String>): String = "window.cadFocus&&window.cadFocus(${JSONArray(spec)})"
    fun showJs(spec: List<String>, on: Boolean): String = "window.cadShow&&window.cadShow(${JSONArray(spec)},$on)"

    /** Every topic id of a tab, in order (the nav-shape test and the parity test read it). */
    fun topicIds(t: SceneTab): List<String> = t.groups.flatMap { g -> g.topics.map { it.id } }
}

/** Run-time fetch of the content document and the models, with an on-device cache. Blocking: call off the main thread. */
object BioStore {
    const val CONTENT = "content.json"

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15000; readTimeout = 60000; instanceFollowRedirects = true
        if (responseCode !in 200..299) throw IllegalStateException("HTTP $responseCode " + url.substringAfter("//").substringBefore("?"))
    }

    /** The content text: fresh from [base] when reachable (and cached in [dir]), else the cached copy, else null. */
    fun contentText(base: String, dir: File): String? {
        dir.mkdirs(); val f = File(dir, CONTENT)
        return runCatching { open(base + CONTENT).inputStream.use { it.readBytes() }.toString(Charsets.UTF_8) }
            .onSuccess { if (BioParse.parse(it) != null) f.writeText(it) }.getOrNull()?.takeIf { BioParse.parse(it) != null }
            ?: f.takeIf { it.isFile }?.readText()
    }
}

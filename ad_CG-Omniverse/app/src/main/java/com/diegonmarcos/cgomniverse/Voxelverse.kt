package com.diegonmarcos.cgomniverse

import org.json.JSONArray
import org.json.JSONObject

/**
 * #1784 the Voxelverse tab, pure part: the declaration (data/voxelverse.json), the addresses it implies, and the readers of the
 * plan document (markdown, fetched at run time from the owner's repo) and of the repo's status (GitHub JSON). Nothing of the plan
 * is stored here: the page shows what the repo holds now. Pure, so the JVM suite covers it.
 */
data class VoxSub(val id: String, val label: String)
data class VoxSection(val id: String, val heading: String, val title: String)
data class VoxPhase(val title: String, val summary: String, val gate: String)
data class VoxCommit(val sha: String, val date: String, val subject: String)
data class VoxMilestone(val title: String, val open: Int, val closed: Int)
data class VoxBlock(val title: String, val bullets: List<String>, val paragraphs: List<String>)

class VoxelverseDecl(
    val repo: String, val branch: String, val planPath: String,
    val rawBase: String, val apiBase: String, val webBase: String, val engineMarker: String,
    val subpages: List<VoxSub>, val sections: List<VoxSection>, val roadmapHeading: String, val roadmapTitle: String,
    val artifact: String?, val text: Map<String, String>,
) {
    fun t(key: String): String = text[key].orEmpty()
    val planUrl: String get() = rawBase + repo + "/" + branch + "/" + planPath
    val planWebUrl: String get() = webBase + repo + "/blob/" + branch + "/" + planPath
    val repoWebUrl: String get() = webBase + repo
    val commitUrl: String get() = apiBase + repo + "/commits?per_page=1&sha=" + branch
    val milestonesUrl: String get() = apiBase + repo + "/milestones?state=open"
    val rootUrl: String get() = apiBase + repo + "/contents?ref=" + branch
}

object Voxelverse {
    fun parse(json: String): VoxelverseDecl {
        val o = JSONObject(json)
        fun arr(k: String): JSONArray = o.optJSONArray(k) ?: JSONArray()
        val tx = o.optJSONObject("text") ?: JSONObject()
        return VoxelverseDecl(
            repo = o.getString("repo"), branch = o.optString("branch", "main"), planPath = o.getString("plan_path"),
            rawBase = o.getString("raw_base"), apiBase = o.getString("api_base"), webBase = o.getString("web_base"),
            engineMarker = o.optString("engine_marker"),
            subpages = (0 until arr("subpages").length()).map { arr("subpages").getJSONObject(it) }.map { VoxSub(it.getString("id"), it.getString("label")) },
            sections = (0 until arr("plan_sections").length()).map { arr("plan_sections").getJSONObject(it) }
                .map { VoxSection(it.getString("id"), it.getString("heading"), it.getString("title")) },
            roadmapHeading = o.optJSONObject("roadmap")?.optString("heading").orEmpty(),
            roadmapTitle = o.optJSONObject("roadmap")?.optString("title").orEmpty(),
            artifact = o.optJSONObject("viewer")?.optString("artifact")?.takeIf { it.isNotBlank() && it != "null" },
            text = tx.keys().asSequence().associateWith { tx.getString(it) },
        )
    }

    /** The `## ` sections of a markdown document: heading to body, in order (text before the first `## ` is dropped). */
    fun sections(md: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        var head: String? = null
        val body = StringBuilder()
        for (line in md.lines()) {
            if (line.startsWith("## ")) {
                head?.let { out.add(it to body.toString().trim()) }
                head = line.removePrefix("## ").trim(); body.clear()
            } else if (head != null) body.append(line).append('\n')
        }
        head?.let { out.add(it to body.toString().trim()) }
        return out
    }

    /** Markdown inline marks out: bold, code, escapes, links keep their text, embed placeholders vanish. */
    fun clean(s: String): String = s
        .replace(Regex("\\[([^\\]]*)]\\([^)]*\\)"), "$1")
        .replace("**", "").replace("`", "")
        .replace(Regex("\\\\([_*\\[\\]#])"), "$1")
        .trim()

    /** One section body as a block: bullets (`- `) and paragraphs, tables and embed placeholders skipped. */
    fun block(title: String, body: String): VoxBlock {
        val bullets = ArrayList<String>()
        val paras = ArrayList<String>()
        for (raw in body.lines()) {
            val l = raw.trim()
            when {
                l.isEmpty() || l.startsWith("|") || l.startsWith("&#91;") || l.startsWith("---") -> {}
                l.startsWith("- ") || l.startsWith("* ") -> bullets.add(clean(l.substring(2)))
                Regex("^\\d+\\. ").containsMatchIn(l) -> bullets.add(clean(l.replace(Regex("^\\d+\\. "), "")))
                else -> paras.add(clean(l))
            }
        }
        return VoxBlock(title, bullets, paras)
    }

    /** The blocks the declaration names, in its order, from the fetched document; a missing heading yields no block. */
    fun plan(decl: VoxelverseDecl, md: String): List<VoxBlock> {
        val secs = sections(md).toMap()
        return decl.sections.mapNotNull { s -> secs[s.heading]?.let { block(s.title, it) } }
    }

    /** The roadmap's numbered list `N. **Title (time)** - summary. Gate: ...` as phases. */
    fun phases(decl: VoxelverseDecl, md: String): List<VoxPhase> {
        val body = sections(md).toMap()[decl.roadmapHeading] ?: return emptyList()
        val re = Regex("^\\d+\\.\\s+\\*\\*(.+?)\\*\\*\\s*[—-]?\\s*(.*)$")
        return body.lines().mapNotNull { re.find(it.trim()) }.map { m ->
            val rest = clean(m.groupValues[2])
            val i = rest.indexOf("Gate:")
            VoxPhase(clean(m.groupValues[1]), (if (i >= 0) rest.substring(0, i) else rest).trim().trimEnd('.', ' '), if (i >= 0) rest.substring(i + 5).trim() else "")
        }
    }

    /** The newest commit of GitHub's `commits?per_page=1` answer; null when it is not one. */
    fun commit(json: String): VoxCommit? = runCatching {
        val c = JSONArray(json).getJSONObject(0)
        val m = c.getJSONObject("commit")
        VoxCommit(c.getString("sha").take(7), m.getJSONObject("author").getString("date").take(10), m.getString("message").lineSequence().first())
    }.getOrNull()

    fun milestones(json: String): List<VoxMilestone> = runCatching {
        val a = JSONArray(json)
        (0 until a.length()).map { a.getJSONObject(it) }.map { VoxMilestone(it.getString("title"), it.optInt("open_issues"), it.optInt("closed_issues")) }
    }.getOrDefault(emptyList())

    /** True when the repo's root listing holds [marker] (the Rust workspace's file): the engine exists. */
    fun hasEngine(rootJson: String, marker: String): Boolean = runCatching {
        val a = JSONArray(rootJson)
        marker.isNotEmpty() && (0 until a.length()).any { a.getJSONObject(it).optString("name") == marker }
    }.getOrDefault(false)
}

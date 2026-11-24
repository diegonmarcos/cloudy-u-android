package com.diegonmarcos.cloudwiki

import com.diegonmarcos.superapp.graphview.GEdge
import com.diegonmarcos.superapp.graphview.GNode
import com.diegonmarcos.superapp.graphview.GraphBuilder
import org.json.JSONArray
import org.json.JSONObject

/**
 * One page: Markdown [md] with a short explanation, the [tags] its chips show, filed under [topic]. #1891 [path] (its folder in the library
 * repo) and [updated] (when the dataset says it last changed) are what the dataset carries, blank when it does not.
 */
data class WikiPage(val id: String, val topic: String, val title: String, val tags: List<String>, val md: String, val path: String = "", val updated: String = "") {
    /** The ids this page links to with `[[topic.slug]]`, in order, without repeats. */
    val links: List<String> get() = Wiki.links(md)
}

/** A topic: a side-menu page whose [pages] are its top subpage tabs after the Overview tab. [intro] is the Overview's Markdown. */
data class WikiTopic(val id: String, val label: String, val intro: String, val pages: List<WikiPage>, val path: String = "")

/**
 * #1867 a parent (Modern Science, Arcanum Science, Projects) or a hub (Sciences, A. Architecture of Sacred Sciences, ...) of the repo's
 * tree: its menu name, the Markdown [intro] of its index.md and the ids of the nodes below it, in order.
 */
data class WikiHub(val id: String, val label: String, val intro: String, val children: List<String>)

data class WikiData(val topics: List<WikiTopic>, val hubs: List<WikiHub> = emptyList()) {
    val pages: List<WikiPage> by lazy { topics.flatMap { it.pages } }
    private val byId: Map<String, WikiPage> by lazy { pages.associateBy { it.id } }
    fun page(id: String?): WikiPage? = if (id == null) null else byId[id]
    fun topic(id: String?): WikiTopic? = topics.firstOrNull { it.id == id }
    fun hub(id: String?): WikiHub? = hubs.firstOrNull { it.id == id }
}

/** The dataset and what is done with it: parse, tags, the tag filter, the graph. Pure, so the JVM suite covers it. */
object Wiki {
    /** The runtime dataset name (data/fleet/app/<package>/wiki.json). */
    const val DATASET = "wiki"

    private val LINK = Regex("""\[\[([^\]\s]+)]]""")

    fun parse(json: String): WikiData {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return WikiData(emptyList())
        val topics = objects(root.optJSONArray("topics")).map { t ->
            val tid = t.getString("id")
            WikiTopic(tid, t.optString("label", tid), t.optString("intro"), objects(t.optJSONArray("pages")).map { p ->
                WikiPage(p.getString("id"), tid, p.optString("title"), strings(p.optJSONArray("tags")), p.optString("md"), p.optString("path"), p.optString("updated"))
            }, t.optString("path"))
        }
        // #1867 schema 2 (the repo's generated wiki.json) adds the parents and hubs with their intros; schema 1 has none
        val hubs = objects(root.optJSONArray("hubs")).map { h ->
            val hid = h.getString("id")
            WikiHub(hid, h.optString("label", hid), h.optString("intro"), strings(h.optJSONArray("children")))
        }
        return WikiData(topics, hubs)
    }

    private fun objects(a: JSONArray?): List<JSONObject> = (0 until (a?.length() ?: 0)).mapNotNull { a?.optJSONObject(it) }
    private fun strings(a: JSONArray?): List<String> = (0 until (a?.length() ?: 0)).map { a!!.getString(it) }

    /** The page ids a Markdown text links to with `[[id]]`, in order, each once. */
    fun links(md: String): List<String> = LINK.findAll(md).map { it.groupValues[1] }.distinct().toList()

    /** Every tag of [pages] with the number of pages that carry it, the most used first, then by name. */
    fun tagCloud(pages: List<WikiPage>): List<Pair<String, Int>> =
        pages.flatMap { it.tags.distinct() }.groupingBy { it }.eachCount().toList().sortedWith(compareBy({ -it.second }, { it.first }))

    /** The pages that carry EVERY tag of [selected]; all of [pages] when none is selected. */
    fun filter(pages: List<WikiPage>, selected: Collection<String>): List<WikiPage> =
        if (selected.isEmpty()) pages else pages.filter { p -> selected.all { it in p.tags } }

    /** The chips to offer under a filter: the selected tags first, then the tags of the pages that still match, minus the selected. */
    fun chips(pages: List<WikiPage>, selected: List<String>): List<String> =
        selected + tagCloud(filter(pages, selected)).map { it.first }.filter { it !in selected }

    /** Adds [tag] to [selected] when absent, removes it when present. */
    fun toggle(selected: List<String>, tag: String): List<String> = if (tag in selected) selected - tag else selected + tag

    /** One graph node per page, grouped by topic, carrying its tags. */
    fun nodes(data: WikiData): List<GNode> = data.pages.map { GNode(it.id, it.title, it.topic, it.tags) }

    /** The graph's edges: a LINK edge per `[[link]]` between two pages, a TAG edge per pair sharing a tag (not already linked). */
    fun edges(data: WikiData, minShared: Int = 1): List<GEdge> =
        GraphBuilder.build(nodes(data), data.pages.associate { it.id to it.links }, minShared)
}

/** The Markdown a page is written in: headings, paragraphs and bullets, with `**bold**`, `*italic*` and `[[page-id]]` links inline. */
object Md {
    sealed class Block {
        data class Heading(val level: Int, val spans: List<Span>) : Block()
        data class Para(val spans: List<Span>) : Block()
        data class Bullet(val spans: List<Span>) : Block()
    }

    sealed class Span {
        data class Text(val text: String, val bold: Boolean = false, val italic: Boolean = false) : Span()
        data class Link(val id: String) : Span()
    }

    private val INLINE = Regex("""\[\[([^\]\s]+)]]|\*\*(.+?)\*\*|\*(.+?)\*""")

    fun spans(text: String): List<Span> {
        val out = ArrayList<Span>()
        var at = 0
        for (m in INLINE.findAll(text)) {
            if (m.range.first > at) out += Span.Text(text.substring(at, m.range.first))
            out += when {
                m.groups[1] != null -> Span.Link(m.groupValues[1])
                m.groups[2] != null -> Span.Text(m.groupValues[2], bold = true)
                else -> Span.Text(m.groupValues[3], italic = true)
            }
            at = m.range.last + 1
        }
        if (at < text.length) out += Span.Text(text.substring(at))
        return out
    }

    /** Blank lines split paragraphs; consecutive non-blank lines join into one paragraph. */
    fun parse(md: String): List<Block> {
        val out = ArrayList<Block>()
        val para = StringBuilder()
        fun flush() { if (para.isNotEmpty()) { out += Block.Para(spans(para.toString().trim())); para.clear() } }
        for (raw in md.lines()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> flush()
                line.length >= 3 && line.all { it == '-' } -> flush() // a horizontal rule (topics.md of Sacred-geo) separates blocks
                line.startsWith("#") -> { flush(); val n = line.takeWhile { it == '#' }.length; out += Block.Heading(n.coerceAtMost(3), spans(line.drop(n).trim())) }
                line.startsWith("- ") || line.startsWith("* ") -> { flush(); out += Block.Bullet(spans(line.drop(2).trim())) }
                else -> { if (para.isNotEmpty()) para.append(' '); para.append(line) }
            }
        }
        flush()
        return out
    }
}

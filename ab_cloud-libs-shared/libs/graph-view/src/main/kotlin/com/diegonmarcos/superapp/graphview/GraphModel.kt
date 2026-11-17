package com.diegonmarcos.superapp.graphview

/** One node of the graph: a page. [group] colours it (a topic), [tags] build the shared-tag edges. */
data class GNode(val id: String, val label: String, val group: String = "", val tags: List<String> = emptyList())

/** An undirected edge, [a] sorts before [b]. A LINK is an explicit link between two pages; a TAG edge joins pages that share [weight] tags. */
data class GEdge(val a: String, val b: String, val kind: Kind, val weight: Int = 1) {
    enum class Kind { LINK, TAG }
}

/** Builds the edges of a graph from page links and shared tags. Pure, so the JVM suite covers it. */
object GraphBuilder {
    /**
     * [links] maps a page id to the ids it links to. A link to an unknown page, to the page itself, and a repeat in either direction yield
     * one edge. Two pages sharing at least [minShared] tags are joined by a TAG edge unless they are already linked; the weight of a
     * TAG edge is the number of tags they share. A tag held by more than [maxFanout] pages is too common to relate anything and is skipped.
     */
    fun build(nodes: List<GNode>, links: Map<String, List<String>>, minShared: Int = 1, maxFanout: Int = 12): List<GEdge> {
        val ids = nodes.map { it.id }.toSet()
        val out = LinkedHashMap<Pair<String, String>, GEdge>()
        fun key(x: String, y: String) = if (x <= y) x to y else y to x
        for ((from, tos) in links) {
            if (from !in ids) continue
            for (to in tos) {
                if (to !in ids || to == from) continue
                val k = key(from, to)
                out[k] = GEdge(k.first, k.second, GEdge.Kind.LINK)
            }
        }
        val byTag = LinkedHashMap<String, MutableList<String>>()
        for (n in nodes) for (t in n.tags.distinct()) byTag.getOrPut(t) { mutableListOf() }.add(n.id)
        val shared = LinkedHashMap<Pair<String, String>, Int>()
        for ((_, members) in byTag) {
            if (members.size > maxFanout) continue
            for (i in members.indices) for (j in i + 1 until members.size) shared.merge(key(members[i], members[j]), 1, Int::plus)
        }
        for ((k, n) in shared) if (n >= minShared && k !in out) out[k] = GEdge(k.first, k.second, GEdge.Kind.TAG, n)
        return out.values.toList()
    }

    /** The number of edges touching each node id (nodes with none are present with 0). */
    fun degrees(nodes: List<GNode>, edges: List<GEdge>): Map<String, Int> {
        val d = nodes.associate { it.id to 0 }.toMutableMap()
        for (e in edges) { d.merge(e.a, 1, Int::plus); d.merge(e.b, 1, Int::plus) }
        return d
    }
}

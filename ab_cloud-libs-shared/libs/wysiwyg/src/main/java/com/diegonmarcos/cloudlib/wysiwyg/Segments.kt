package com.diegonmarcos.cloudlib.wysiwyg

/** A run of a block's text under one set of marks, the unit both writers emit. */
internal data class Segment(val text: String, val marks: List<Mark>)

internal object Segments {
    /** The block's text cut at every mark boundary; neighbours under the same marks are merged back together. */
    fun of(block: Block): List<Segment> {
        val text = block.text
        if (text.isEmpty()) return emptyList()
        val cuts = sortedSetOf(0, text.length)
        for (m in block.marks) { cuts += m.start.coerceIn(0, text.length); cuts += m.end.coerceIn(0, text.length) }
        val points = cuts.toList()
        val out = ArrayList<Segment>()
        for (k in 0 until points.size - 1) {
            val a = points[k]
            val b = points[k + 1]
            if (b <= a) continue
            val active = block.marks.filter { it.start <= a && it.end >= b }.sortedWith(compareBy({ it.type }, { it.url.orEmpty() }))
            val last = out.lastOrNull()
            if (last != null && last.marks.map { it.type to it.url } == active.map { it.type to it.url }) out[out.size - 1] = last.copy(text = last.text + text.substring(a, b))
            else out += Segment(text.substring(a, b), active)
        }
        return out
    }
}

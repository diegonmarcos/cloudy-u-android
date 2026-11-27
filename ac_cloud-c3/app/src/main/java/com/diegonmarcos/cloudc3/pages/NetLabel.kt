package com.diegonmarcos.cloudc3.pages

/**
 * #1955 A rev2 Shortening of canvas text (pure; the JVM suite runs the exact code the phone runs). A text that does not fit is
 * shortened with an ellipsis; fewer than [MIN_CHARS] characters left means it is not drawn (blank). The full text is always in a sheet.
 */
object NetLabel {
    const val ELLIPSIS = "…"
    const val MIN_CHARS = 4

    /** The longest dot-suffix shared by at least [share] of [peers] (computed from the data, never typed), or "". */
    fun sharedSuffix(peers: List<String>, share: Float = 0.6f): String {
        val ps = peers.distinct()
        if (ps.size < 2) return ""
        val candidates = ps.flatMap { p -> p.indices.filter { p[it] == '.' && it > 0 }.map { p.substring(it) } }.distinct().sortedByDescending { it.length }
        return candidates.firstOrNull { s -> ps.count { it.endsWith(s) && it.length > s.length } >= share * ps.size } ?: ""
    }

    /** End ellipsis at [max] characters; a shortened text under [MIN_CHARS] is blank (a short full name is kept). */
    fun end(s: String, max: Int): String = when {
        s.length <= max -> s
        max < MIN_CHARS -> ""
        else -> s.take(max - 1) + ELLIPSIS
    }

    /** Middle ellipsis keeping the last 3 characters ("cloudy…api"); blank under [MIN_CHARS]. */
    fun middle(s: String, max: Int): String = when {
        s.length <= max -> s
        max < 5 -> ""
        else -> s.take(max - 4) + ELLIPSIS + s.takeLast(3)
    }

    /** Rules 1 and 2 of the design: strip the shared dot-suffix of [peers], then end-ellipsise to [maxChars]. */
    fun short(label: String, peers: List<String>, maxChars: Int): String {
        val suf = sharedSuffix(peers)
        val base = if (suf.isNotEmpty() && label.endsWith(suf) && label.length > suf.length) label.dropLast(suf.length) else label
        return end(base, maxChars)
    }

    /**
     * Rule 3 over a whole page: [labels] id to full label, [peersOf] the names that share a column or frame with an id. Two different
     * full names that read the same after [short] both switch to a middle ellipsis; a pair still equal after that is not drawn.
     */
    fun shortAll(labels: Map<String, String>, peersOf: (String) -> List<String>, maxChars: (String) -> Int): Map<String, String> {
        val first = labels.mapValues { (id, l) -> short(l, peersOf(id), maxChars(id)) }
        val clash = first.entries.groupBy { it.value }.filter { (k, es) -> k.isNotEmpty() && es.map { labels.getValue(it.key) }.distinct().size > 1 }
            .values.flatten().map { it.key }.toSet()
        val second = first.mapValues { (id, s) -> if (id in clash) middle(stripped(labels.getValue(id), peersOf(id)), maxChars(id)) else s }
        val still = second.entries.groupBy { it.value }.filter { (k, es) -> k.isNotEmpty() && es.map { labels.getValue(it.key) }.distinct().size > 1 }
            .values.flatten().map { it.key }.toSet()
        return second.mapValues { (id, s) -> if (id in still) "" else s }
    }

    private fun stripped(label: String, peers: List<String>): String {
        val suf = sharedSuffix(peers)
        return if (suf.isNotEmpty() && label.endsWith(suf) && label.length > suf.length) label.dropLast(suf.length) else label
    }

    /**
     * The measured fit: the longest form of [s] whose measured width is at most [avail] (same ellipsis style as [s]: a middle
     * ellipsis stays a middle one), or "" when fewer than [MIN_CHARS] characters would remain.
     */
    fun fit(s: String, avail: Float, width: (String) -> Float): String {
        if (s.isEmpty()) return ""
        if (width(s) <= avail) return s
        val mid = s.contains(ELLIPSIS) && !s.endsWith(ELLIPSIS)
        val head = if (mid) s.substringBefore(ELLIPSIS) else s.removeSuffix(ELLIPSIS)
        val tail = if (mid) s.substringAfter(ELLIPSIS) else ""
        // the widest head that fits (width grows with the head length): binary search
        fun at(k: Int) = head.take(k) + ELLIPSIS + tail
        var lo = 1; var hi = head.length - 1; var best = -1
        while (lo <= hi) { val k = (lo + hi) / 2; if (width(at(k)) <= avail) { best = k; lo = k + 1 } else hi = k - 1 }
        if (best < 0) return ""
        val c = at(best)
        return if (c.length - 1 < MIN_CHARS) "" else c
    }

    /** "First segment" of a sub line: the text before the first of "  ", " · ", ",", "(". */
    fun firstSegment(s: String): String {
        val cut = listOf("  ", " · ", ",", "(").mapNotNull { m -> s.indexOf(m).takeIf { it >= 0 } }.minOrNull() ?: s.length
        return s.substring(0, cut).trim()
    }

    /** Edge pill text: a comma list becomes its first item plus " +n", then the end ellipsis. */
    fun pill(s: String, max: Int): String {
        val items = s.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val base = if (items.size > 1) "${items.first()} +${items.size - 1}" else s.trim()
        return end(base, max)
    }
}

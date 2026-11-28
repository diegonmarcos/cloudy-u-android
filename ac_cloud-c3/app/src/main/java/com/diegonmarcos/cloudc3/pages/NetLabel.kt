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

    /** Characters that end a name's distinguishing part ("oci-apps" ends in "apps", "a.b.com" in "com"). */
    private const val SEPS = "-_. /:"

    /** The distinguishing tail of a name: the part after its last separator when 2 to 6 characters long, else its last 3 characters. */
    fun tail(s: String): String {
        val i = s.indexOfLast { it in SEPS }
        val t = if (i > 0) s.substring(i + 1) else ""
        return if (t.length in 2..6) t else s.takeLast(3)
    }

    /**
     * #1955 A rev2 Rule 1 over a page, as the layout sizes the boxes: each id's label with the dot-suffix its [peersOf] share stripped,
     * capped at [maxChars]. Two different full labels that would read the same keep a shorter suffix, one dot level at a time
     * ("stalwart.mesh.example.com" next to a service "stalwart" reads "stalwart.mesh"), so no name is blanked by a clash.
     */
    fun bases(labels: Map<String, String>, peersOf: (String) -> List<String>, maxChars: Int): Map<String, String> {
        val suf = labels.mapValues { (id, l) -> sharedSuffix(peersOf(id)).takeIf { it.isNotEmpty() && l.endsWith(it) && l.length > it.length }.orEmpty() }.toMutableMap()
        fun read(id: String) = end(labels.getValue(id).dropLast(suf.getValue(id).length), maxChars)
        repeat(labels.size + 1) {
            val clash = labels.keys.groupBy(::read).values.filter { ids -> ids.map { labels.getValue(it) }.distinct().size > 1 }.flatten().filter { suf.getValue(it).isNotEmpty() }
            if (clash.isEmpty()) return labels.keys.associateWith(::read)
            clash.forEach { id -> suf[id] = suf.getValue(id).let { x -> x.indexOf('.', 1).let { i -> if (i < 0) "" else x.substring(i) } } }
        }
        return labels.keys.associateWith(::read)
    }

    /**
     * The measured fit: the longest form of [s] whose measured width is at most [avail] (same ellipsis style as [s]: a middle
     * ellipsis stays a middle one), or "" when fewer than [MIN_CHARS] characters would remain. [middle]: a name that does not fit keeps
     * its distinguishing [tail] after the ellipsis ("oci-…apps"), falling back to the last 3 characters, then to the end ellipsis.
     */
    fun fit(s: String, avail: Float, middle: Boolean = false, width: (String) -> Float): String {
        if (s.isEmpty()) return ""
        if (width(s) <= avail) return s
        if (middle && !s.contains(ELLIPSIS)) {
            for (t in listOf(tail(s), s.takeLast(3)).distinct()) {
                val head = s.dropLast(t.length)
                fun at(k: Int) = head.take(k) + ELLIPSIS + t
                var lo = 1; var hi = head.length - 1; var best = -1
                while (lo <= hi) { val k = (lo + hi) / 2; if (width(at(k)) <= avail) { best = k; lo = k + 1 } else hi = k - 1 }
                if (best >= 1 && best + t.length >= MIN_CHARS) return at(best)
            }
        }
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

    /** #1955 A rev2.1 A name at most [max] characters long: the middle ellipsis that keeps its distinguishing [tail] ("desktop-nixos" at 10 reads "desk…nixos"). */
    fun cap(s: String, max: Int): String = if (s.length <= max) s else fit(s, max.toFloat(), middle = true) { it.length.toFloat() }

    /**
     * #1955 A rev2.1 The root-cause chip's text, one line of at most [max] characters: "■ ROOT <name> · N impacted ›" where the name is the
     * root's label without the dot-suffix its [peers] share ("analytics.diegonmarcos.com" reads "analytics"); a name still too long keeps
     * its tail after a middle ellipsis, the arrow goes before the name is cut.
     */
    fun rootChip(label: String, peers: List<String>, impacted: Int, max: Int = 30): String {
        val name = stripped(label, peers)
        val head = "■ ROOT "
        listOf(" · $impacted impacted ›", " · $impacted impacted").forEach { tail -> if ((head + name + tail).length <= max) return head + name + tail }
        val tail = " · $impacted impacted"
        return head + cap(name, (max - head.length - tail.length).coerceAtLeast(MIN_CHARS)) + tail
    }

    /** The forms a name may take in [avail] when its first fit reads like another drawn name: other tails, then the end ellipsis. */
    fun variants(s: String, avail: Float, width: (String) -> Float): List<String> =
        ((3..6).map { k -> s.takeLast(k) }.mapNotNull { t ->
            val head = s.dropLast(t.length)
            (head.length - 1 downTo 1).asSequence().map { head.take(it) + ELLIPSIS + t }.firstOrNull { width(it) <= avail && it.length - 1 >= MIN_CHARS }
        } + fit(s, avail, width = width)).filter { it.isNotEmpty() }.distinct()

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

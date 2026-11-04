package com.diegonmarcos.superapp.scraper

import java.text.Normalizer

/** #1459 one song as every service reports it. [id] is what the service's own write call takes (a URI, a video id). */
data class Track(
    val title: String,
    val artist: String,
    val album: String = "",
    val isrc: String = "",
    val durationMs: Long = 0,
    val id: String = "",
    val uri: String = "",
)

data class Playlist(val id: String, val name: String, val count: Int = 0)

/** International Standard Recording Code: 2 letters, 3 letters or digits, 2 digits (year), 5 digits. */
object Isrc {
    private val SHAPE = Regex("^[A-Z]{2}[A-Z0-9]{3}[0-9]{7}$")

    /** Upper case, with the dashes and spaces some services print ("US-RC1-76-07839") taken out. */
    fun normalize(raw: String?): String = raw.orEmpty().uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }

    fun valid(raw: String?): Boolean = SHAPE.matches(normalize(raw))

    /** The normalised code, or "" when [raw] is not an ISRC. */
    fun clean(raw: String?): String = normalize(raw).takeIf { SHAPE.matches(it) }.orEmpty()
}

enum class How { ISRC, FUZZY, NONE }

data class Match(val how: How, val candidate: Track?, val score: Double)

/** ISRC exact match first, then a fuzzy match on title + artist + duration. */
object Matcher {
    /** Below 0.8 so the same title and artist with a very different length (0.8 exactly) still matches, without a float tie. */
    const val THRESHOLD = 0.79
    private const val MIN_TITLE = 0.70
    private const val MIN_ARTIST = 0.70
    private val BRACKETS = Regex("""[(\[{][^)\]}]*[)\]}]""")
    private val SUFFIX = Regex("""\s-\s.*(remaster|version|edit|mix|live|mono|stereo).*$""")
    private val FEAT = Regex("""\b(feat|ft|featuring)\b\.?.*$""")
    private val MARKS = Regex("""\p{M}+""")
    private val NON_WORD = Regex("""[^\p{L}\p{N}]+""")
    private val ARTIST_SPLIT = Regex("""\s*(?:,|&|;|/|\bfeat\.?|\bft\.?|\band\b|\bx\b)\s*""")

    /** Lower case, accents, "(feat. x)", "[Remastered]", " - 2011 Remaster" and punctuation taken out. */
    fun norm(s: String): String {
        var t = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).let { MARKS.replace(it, "") }
        t = BRACKETS.replace(t, " ")
        t = SUFFIX.replace(t, "")
        t = FEAT.replace(t, "")
        return NON_WORD.replace(t, " ").trim()
    }

    /** 0..1: the better of the bigram Dice coefficient and the word-set Jaccard index of two normalised strings. */
    fun sim(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        return maxOf(dice(a, b), jaccard(a, b))
    }

    private fun dice(a: String, b: String): Double {
        if (a.length < 2 || b.length < 2) return 0.0
        val x = a.windowed(2).groupingBy { it }.eachCount()
        val y = b.windowed(2).groupingBy { it }.eachCount()
        var inter = 0
        for ((k, v) in x) inter += minOf(v, y[k] ?: 0)
        return 2.0 * inter / ((a.length - 1) + (b.length - 1))
    }

    private fun jaccard(a: String, b: String): Double {
        val x = a.split(' ').toSet()
        val y = b.split(' ').toSet()
        return x.intersect(y).size.toDouble() / x.union(y).size
    }

    /** The best artist similarity: the whole credit, or any single credited artist against any other. */
    fun artistSim(a: String, b: String): Double {
        val whole = sim(norm(a), norm(b))
        val xs = a.lowercase().split(ARTIST_SPLIT).map { norm(it) }.filter { it.isNotEmpty() }
        val ys = b.lowercase().split(ARTIST_SPLIT).map { norm(it) }.filter { it.isNotEmpty() }
        var best = whole
        for (x in xs) for (y in ys) best = maxOf(best, sim(x, y))
        return best
    }

    /** 1 within 2 s, falling to 0 at 10 s apart; null when either length is unknown. */
    fun durationSim(a: Long, b: Long): Double? {
        if (a <= 0 || b <= 0) return null
        val d = kotlin.math.abs(a - b)
        return when {
            d <= 2_000 -> 1.0
            d >= 10_000 -> 0.0
            else -> 1.0 - (d - 2_000) / 8_000.0
        }
    }

    /** The fuzzy score of [b] as the same song as [a], or null when the title or the artist is too far apart. */
    fun score(a: Track, b: Track): Double? {
        val t = sim(norm(a.title), norm(b.title))
        val ar = artistSim(a.artist, b.artist)
        if (t < MIN_TITLE || ar < MIN_ARTIST) return null
        val d = durationSim(a.durationMs, b.durationMs)
        return if (d == null) 0.6 * t + 0.4 * ar else 0.5 * t + 0.3 * ar + 0.2 * d
    }

    fun best(src: Track, candidates: List<Track>): Match {
        val code = Isrc.clean(src.isrc)
        if (code.isNotEmpty()) candidates.firstOrNull { Isrc.clean(it.isrc) == code }?.let { return Match(How.ISRC, it, 1.0) }
        var best: Track? = null
        var top = 0.0
        for (c in candidates) {
            val s = score(src, c) ?: continue
            if (s > top) { top = s; best = c }
        }
        return if (best != null && top >= THRESHOLD) Match(How.FUZZY, best, top) else Match(How.NONE, null, top)
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

/**
 * One highlighted fix in the field: [start]..[end] (absolute field offsets, end exclusive) holds [original],
 * and the checker suggests [suggestion] ("" = delete it, e.g. a repeated word).
 */
data class GrammarIssue(val start: Int, val end: Int, val original: String, val suggestion: String) {
    /** What an Ignore remembers: the text and its fix, not the position, so an edit elsewhere cannot resurrect it. */
    val ignoreKey: String get() = original + "\u0000" + suggestion
}

/**
 * Grammar Check ▸ Highlight: the spans the checker marked in the current field, and the ones the user
 * ignored this input session. Pure; the IME owns one per process ([GrammarHighlights.session]) and clears it
 * when a new field starts. JVM-tested in GrammarHighlightsTest.
 */
class GrammarHighlights {
    private val issues = mutableListOf<GrammarIssue>()
    private val ignored = mutableSetOf<String>()

    val all: List<GrammarIssue> @Synchronized get() = issues.toList()
    val ignoredCount: Int @Synchronized get() = ignored.size

    @Synchronized fun isIgnored(original: String, suggestion: String) = (original + "\u0000" + suggestion) in ignored

    /** Records a fresh check's marks; ignored ones are dropped, and a mark overlapping a recorded one replaces it. */
    @Synchronized fun record(found: List<GrammarIssue>): List<GrammarIssue> {
        val kept = found.filter { it.ignoreKey !in ignored }
        kept.forEach { n -> issues.removeAll { it.start < n.end && n.start < it.end } }
        issues += kept
        issues.sortBy { it.start }
        return kept
    }

    /** The mark a collapsed cursor at [cursor] sits in (a tap on the word puts the cursor inside or at an edge of it). */
    @Synchronized fun at(cursor: Int): GrammarIssue? = issues.firstOrNull { cursor >= it.start && cursor <= it.end }

    /** Ignore: the mark goes, and the same text/fix pair is not marked again this session. */
    @Synchronized fun ignore(issue: GrammarIssue) {
        issues.remove(issue)
        ignored += issue.ignoreKey
    }

    /** Replace: the mark goes and every later mark shifts by the length change. */
    @Synchronized fun replaced(issue: GrammarIssue) {
        if (!issues.remove(issue)) return
        val delta = issue.suggestion.length - (issue.end - issue.start)
        for (i in issues.indices) {
            val o = issues[i]
            if (o.start >= issue.end) issues[i] = o.copy(start = o.start + delta, end = o.end + delta)
        }
    }

    /** A mark whose text no longer matches the field is dropped (the user edited it by hand). */
    @Synchronized fun drop(issue: GrammarIssue) { issues.remove(issue) }

    /** A new field: forget both the marks and the ignores. */
    @Synchronized fun newSession() { issues.clear(); ignored.clear() }

    companion object {
        @JvmField val session = GrammarHighlights()
    }
}

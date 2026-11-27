package com.diegonmarcos.cloudlib.pdf

object PdfWords {
    /**
     * The word around [index] in [text] — what a long press selects. Null when the press landed
     * on whitespace or outside the text. A word is a run of letters or digits; the apostrophe and
     * hyphen inside one keep it whole (don't, well-known).
     */
    fun wordRange(text: String, index: Int): IntRange? {
        if (index < 0 || index >= text.length) return null
        fun inWord(i: Int): Boolean {
            val c = text[i]
            if (c.isLetterOrDigit()) return true
            return (c == '\'' || c == '-' || c == '’') &&
                i > 0 && i < text.length - 1 &&
                text[i - 1].isLetterOrDigit() && text[i + 1].isLetterOrDigit()
        }
        if (!inWord(index)) return null
        var start = index
        var end = index
        while (start > 0 && inWord(start - 1)) start--
        while (end < text.length - 1 && inWord(end + 1)) end++
        return start..end
    }
}

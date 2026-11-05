package com.diegonmarcos.superapp.ocr

/** Language guess for OCR text, offline: the dominant scripts (ISO 15924) and, for Latin text, a stop-word vote. */
object Languages {
    private val STOP = mapOf(
        "en" to "the and of to in is for on with that this you are not".split(' '),
        "fr" to "le la les des et est une pour dans que qui pas sur avec vous".split(' '),
        "es" to "el los las del y es una para que con por como mas pero".split(' '),
        "de" to "der die das und ist nicht ein eine mit für auf den von zu".split(' '),
        "pt" to "o os as do da dos e é um uma para com não que por mais".split(' '),
    )

    fun detect(text: String): List<String> {
        val letters = text.filter { it.isLetter() }
        if (letters.isEmpty()) return emptyList()
        val scripts = letters.groupingBy { script(it) }.eachCount().filter { it.key != "Zyyy" && it.value * 10 >= letters.length }.entries.sortedByDescending { it.value }.map { it.key }
        val out = ArrayList<String>()
        if ("Latn" in scripts) {
            val words = Regex("[\\p{L}]+").findAll(text.lowercase()).map { it.value }.toList()
            val votes = STOP.mapValues { (_, sw) -> words.count { it in sw } }.filter { it.value >= 2 }
            val best = votes.maxByOrNull { it.value }
            out.add(if (best != null) "${best.key}-Latn" else "und-Latn")
        }
        scripts.filter { it != "Latn" }.forEach { out.add("und-$it") }
        return out
    }

    private fun script(c: Char): String = when (Character.UnicodeScript.of(c.code)) {
        Character.UnicodeScript.LATIN -> "Latn"
        Character.UnicodeScript.CYRILLIC -> "Cyrl"
        Character.UnicodeScript.GREEK -> "Grek"
        Character.UnicodeScript.ARABIC -> "Arab"
        Character.UnicodeScript.HEBREW -> "Hebr"
        Character.UnicodeScript.DEVANAGARI -> "Deva"
        Character.UnicodeScript.HAN -> "Hani"
        Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> "Jpan"
        Character.UnicodeScript.HANGUL -> "Kore"
        else -> "Zyyy"
    }
}

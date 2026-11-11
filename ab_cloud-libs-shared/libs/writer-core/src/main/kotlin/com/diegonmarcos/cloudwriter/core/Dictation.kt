package com.diegonmarcos.cloudwriter.core

/** How Listen writes what it heard into the page. */
object Dictation {

    /** Where a translation goes: under the original, or in its place. Ids are the stored preference. */
    enum class TranslateMode(val id: String) {
        ALONGSIDE("alongside"),
        INSTEAD("instead");

        companion object {
            fun of(id: String?): TranslateMode = values().firstOrNull { it.id == id } ?: ALONGSIDE
        }
    }

    /** A model's transcript marks a change of voice as "Speaker N:" at the start of a line (the prompt asks for exactly that). */
    private val SPEAKER = Regex("^Speaker \\d+:")
    private val TERMINAL = ".!?…:;\"')]»".toSet()

    /**
     * On-device recognisers hand back lowercase words with no punctuation; a model hands back
     * sentences. This gives the former a capital and a full stop and leaves the latter alone.
     */
    fun punctuate(raw: String): String {
        val t = raw.trim().replace(Regex("\\s+"), " ")
        if (t.isEmpty()) return ""
        val capital = t.replaceFirstChar { it.uppercaseChar() }
        return if (capital.last() in TERMINAL) capital else "$capital."
    }

    /** What goes between the page so far and a new segment: nothing at a line start, a new line for a new speaker, else one space. */
    fun separator(before: String, segment: String): String = when {
        before.isEmpty() || before.endsWith("\n") -> ""
        SPEAKER.containsMatchIn(segment.trimStart()) -> "\n"
        before.endsWith(" ") -> ""
        else -> " "
    }

    /** The segment as written: the original, the original with its translation under it, or the translation alone. */
    fun withTranslation(original: String, translation: String?, mode: TranslateMode): String = when {
        translation.isNullOrBlank() -> original
        mode == TranslateMode.INSTEAD -> translation.trim()
        else -> original + "\n→ " + translation.trim() + "\n"
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

/**
 * The emoji lists shown at the bottom of Mediaboards > Emojis: one list per category file of the keyboard's own emoji
 * assets (assets/emoji/NAME.txt, one emoji per line), read at runtime, never copied anywhere else. Pure, so the parsing
 * and the labels are testable without Android.
 */
object EmojiLists {
    /** The category files, in the order the panel shows them; minApi.txt is not a category. */
    val categoryFiles: List<String> = listOf(
        "SMILEYS_AND_EMOTION", "PEOPLE_AND_BODY", "ANIMALS_AND_NATURE", "FOOD_AND_DRINK", "TRAVEL_AND_PLACES",
        "ACTIVITIES", "OBJECTS", "SYMBOLS", "FLAGS", "EMOTICONS",
    )

    class Category(val file: String, val label: String, val emojis: List<String>)

    /** "FOOD_AND_DRINK" -> "Food and drink". */
    fun label(file: String): String =
        file.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }

    /** The emojis of one category file's [lines]: a line holds one emoji (variants after a tab are skipped); blank lines are dropped. */
    fun parse(lines: List<String>): List<String> =
        lines.map { it.substringBefore('\t').trim() }.filter { it.isNotEmpty() }

    /** Every category [read] can supply; a file that is missing or unreadable is left out (and named in [missing]), never a crash. */
    fun load(read: (String) -> List<String>?): List<Category> = categoryFiles.mapNotNull { f ->
        val lines = runCatching { read("emoji/$f.txt") }.getOrNull() ?: return@mapNotNull null
        parse(lines).takeIf { it.isNotEmpty() }?.let { Category(f, label(f), it) }
    }

    fun missing(loaded: List<Category>): List<String> = categoryFiles - loaded.map { it.file }.toSet()
}

package org.fossify.phone.nav

import android.content.Context
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.text.Normalizer

@Serializable
data class KeypadKey(val digit: String, val letters: String = "")

@Serializable
data class T9Limits(
    @SerialName("results_limit") val resultsLimit: Int = 20,
    @SerialName("min_name_substring") val minNameSubstring: Int = 2,
    @SerialName("min_number_substring") val minNumberSubstring: Int = 3,
)

/** #1528 the keypad declaration (assets/keypad.json): the keys with their letters (also the T9 map) and the search limits. */
@Serializable
data class KeypadSpec(val columns: Int = 3, val keys: List<KeypadKey>, val search: T9Limits = T9Limits()) {
    val rows: List<List<KeypadKey>> get() = keys.chunked(columns)

    /** letter (lower case) -> the digit it is printed under. */
    val letterToDigit: Map<Char, Char> by lazy {
        keys.flatMap { k -> k.letters.filter { it.isLetter() }.map { it.lowercaseChar() to k.digit.first() } }.toMap()
    }

    companion object {
        const val ASSET = "keypad.json"
        private val json = Json { ignoreUnknownKeys = true }
        fun parse(raw: String): KeypadSpec = json.decodeFromString(serializer(), raw)
        fun load(ctx: Context): KeypadSpec = parse(ctx.assets.open(ASSET).bufferedReader().use { it.readText() })
    }
}

/** A name with one number, plus how recently ([lastMillis], 0 = never) and how often ([count]) it was called. */
data class T9Contact(val name: String, val number: String, val lastMillis: Long = 0, val count: Int = 0)

/** How a contact matched, best first: whole name or number equals the digits, prefix, a later word starts with them, or they occur inside. */
enum class T9Tier { EXACT, PREFIX, WORD_START, SUBSTRING }

data class T9Hit(val contact: T9Contact, val tier: T9Tier)

/** #1528 T9 contact search: the typed digits against the digits each name spells and against the number. Pure, unit-tested. */
object T9 {
    private val accents = Regex("\\p{M}+")

    /** The name as digit words ("John Smith" -> ["5646", "76484"]); anything that is neither letter nor digit separates words. */
    fun words(name: String, map: Map<Char, Char>): List<String> {
        val folded = accents.replace(Normalizer.normalize(name, Normalizer.Form.NFD), "").lowercase()
        val out = ArrayList<String>()
        val cur = StringBuilder()
        fun flush() { if (cur.isNotEmpty()) { out += cur.toString(); cur.clear() } }
        for (ch in folded) {
            val d = if (ch.isDigit()) ch else map[ch]
            if (d == null) flush() else cur.append(d)
        }
        flush()
        return out
    }

    fun digits(s: String): String = s.filter { it.isDigit() }

    private fun nameTier(q: String, words: List<String>, minSub: Int): T9Tier? {
        val full = words.joinToString("")
        return when {
            full.isEmpty() -> null
            full == q -> T9Tier.EXACT
            full.startsWith(q) -> T9Tier.PREFIX
            words.drop(1).any { it.startsWith(q) } -> T9Tier.WORD_START
            q.length >= minSub && full.contains(q) -> T9Tier.SUBSTRING
            else -> null
        }
    }

    private fun numberTier(q: String, number: String, minSub: Int): T9Tier? {
        val n = digits(number)
        return when {
            n.isEmpty() -> null
            n == q -> T9Tier.EXACT
            n.startsWith(q) -> T9Tier.PREFIX
            q.length >= minSub && n.contains(q) -> T9Tier.SUBSTRING
            else -> null
        }
    }

    /**
     * The contacts matching [typed] (non-digits ignored), ranked exact > prefix > word start > substring, then most recently called,
     * then most often called, then by name. One hit per name and number; several names are all listed, up to the declared limit.
     */
    fun search(typed: String, contacts: List<T9Contact>, spec: KeypadSpec): List<T9Hit> {
        val q = digits(typed)
        if (q.isEmpty()) return emptyList()
        val lim = spec.search
        val map = spec.letterToDigit
        return contacts.distinctBy { it.name.lowercase() + "|" + digits(it.number) }.mapNotNull { c ->
            val tier = listOfNotNull(nameTier(q, words(c.name, map), lim.minNameSubstring), numberTier(q, c.number, lim.minNumberSubstring))
                .minOrNull()
            tier?.let { T9Hit(c, it) }
        }.sortedWith(
            compareBy<T9Hit> { it.tier }.thenByDescending { it.contact.lastMillis }.thenByDescending { it.contact.count }
                .thenBy { it.contact.name.lowercase() },
        ).take(lim.resultsLimit)
    }
}

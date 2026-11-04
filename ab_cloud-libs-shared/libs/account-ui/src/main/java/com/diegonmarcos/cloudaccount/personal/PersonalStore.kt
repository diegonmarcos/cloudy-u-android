package com.diegonmarcos.cloudaccount.personal

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

/**
 * #1438 Personal Info (IDs, Pay Cards, Others > Entries): what each kind holds. [masked] fields stay behind the mask
 * until device auth; a card's number is masked by [CardMask], every other masked field by [CardMask.hide].
 */
enum class PersonalKind(val key: String, private val defFields: List<String>, private val defMasked: Set<String>) {
    IDS("ids", listOf("type", "number", "country", "expiry"), setOf("number")),
    CARDS("cards", listOf("holder", "number", "expiry", "brand"), setOf("number")),
    OTHERS("others", listOf("value"), setOf("value"));

    /** #1445 the schema is account-ui.json::kinds (Cloud Me's SoT, baked); the literals above are only the fallback of a build that carries none. */
    private val decl: JSONObject? get() = com.diegonmarcos.superapp.accountui.AccountUi.kind(key)
    private fun JSONArray?.names(): List<String> = if (this == null) emptyList() else (0 until length()).map { optString(it) }.filter { it.isNotEmpty() }
    val fields: List<String> get() = decl?.optJSONArray("fields").names().ifEmpty { defFields }
    val masked: Set<String> get() = decl?.optJSONArray("masked")?.names()?.toSet() ?: defMasked
    val title: String get() = decl?.optString("title").orEmpty().ifBlank { key.replaceFirstChar { it.uppercase() } }
    fun numeric(field: String): Boolean = decl?.optJSONArray("numeric").names().contains(field)
    fun multiline(field: String): Boolean = decl?.optJSONArray("multiline").names().contains(field)
}

/** One personal entry. [toString] names the field KEYS only, so an entry that reaches a log carries no value. */
class PersonalEntry(val id: String, val label: String, val fields: Map<String, String>) {
    override fun toString(): String = "PersonalEntry(id=$id, fields=${fields.keys.sorted()})"

    fun json(): JSONObject = JSONObject().put("id", id).put("label", label)
        .put("fields", JSONObject().apply { fields.forEach { (k, v) -> put(k, v) } })

    companion object {
        fun parse(o: JSONObject): PersonalEntry? {
            val id = o.optString("id").ifBlank { return null }
            val f = o.optJSONObject("fields") ?: JSONObject()
            return PersonalEntry(id, o.optString("label"), f.keys().asSequence().associateWith { f.optString(it) })
        }
    }
}

/**
 * The masks. A card number shows its last four digits only (`•••• •••• •••• 1234`); a number too short to have
 * four digits to spare shows none. Any other masked field shows dots only. The clear value is drawn only after
 * device auth said yes (PersonalPages via RevealGate).
 */
object CardMask {
    const val DOT = '•'

    fun mask(number: String): String {
        val digits = number.filter(Char::isDigit)
        if (digits.length < 8) return hide(number)
        return "$DOT$DOT$DOT$DOT $DOT$DOT$DOT$DOT $DOT$DOT$DOT$DOT " + digits.takeLast(4)
    }

    fun hide(value: String): String = if (value.isEmpty()) "" else DOT.toString().repeat(8)

    /** What a row draws for [field] of [kind]: the mask unless [revealed]. */
    fun display(kind: PersonalKind, field: String, value: String, revealed: Boolean): String = when {
        revealed || field !in kind.masked -> value
        kind == PersonalKind.CARDS && field == "number" -> mask(value)
        else -> hide(value)
    }
}

/** The entries of each kind, as one JSON array per kind in [prefs]. Writes are synchronous (commit). */
class PersonalRepo(private val prefs: SharedPreferences) {
    fun list(kind: PersonalKind): List<PersonalEntry> = runCatching {
        val a = JSONArray(prefs.getString(kind.key, null) ?: "[]")
        (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(PersonalEntry::parse) }
    }.getOrDefault(emptyList())

    fun get(kind: PersonalKind, id: String): PersonalEntry? = list(kind).firstOrNull { it.id == id }

    fun put(kind: PersonalKind, entry: PersonalEntry) = write(kind, list(kind).filter { it.id != entry.id } + entry)

    fun remove(kind: PersonalKind, id: String) = write(kind, list(kind).filter { it.id != id })

    private fun write(kind: PersonalKind, entries: List<PersonalEntry>) {
        prefs.edit().putString(kind.key, JSONArray().apply { entries.forEach { put(it.json()) } }.toString()).commit()
    }
}

/**
 * THE one place personal data is stored: EncryptedSharedPreferences (keys AES256-SIV, values AES256-GCM, the master key
 * in the Android keystore). There is no plain fallback: when the keystore cannot open the file, [repo] is null and the
 * pages say so, nothing is written anywhere else. [testPrefs] lets a JVM test (no keystore under Robolectric) hand in
 * its own store; production never sets it.
 */
object PersonalStore {
    const val FILE = "personal_info_secure"

    @Volatile @androidx.annotation.VisibleForTesting var testPrefs: ((Context) -> SharedPreferences)? = null

    fun encrypted(ctx: Context): SharedPreferences {
        val key = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(ctx, FILE, key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    }

    fun repo(ctx: Context): PersonalRepo? = runCatching {
        PersonalRepo(testPrefs?.invoke(ctx) ?: encrypted(ctx.applicationContext))
    }.getOrNull()
}

package app.sterna.ui.compose

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * #1800: saved compose templates — a name and a text the composer inserts at the caret from the
 * overflow's Templates section, and adds, edits or deletes from its "Add / manage" dialog.
 * Kept on the phone in the app's own preferences; nothing leaves the device.
 */
data class ComposeTemplate(val id: String, val name: String, val text: String)

/** The JSON codec, pure so the JVM test can run it. A malformed store reads as empty, never throws. */
internal object ComposeTemplateCodec {
    fun encode(list: List<ComposeTemplate>): String = JSONArray().apply {
        list.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("text", it.text)) }
    }.toString()

    fun decode(json: String?): List<ComposeTemplate> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val a = JSONArray(json)
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id"); val text = o.optString("text")
                if (id.isBlank() || text.isBlank()) null else ComposeTemplate(id, o.optString("name").ifBlank { text.lineSequence().first().take(40) }, text)
            }
        }.getOrDefault(emptyList())
    }

    /** [list] with [t] added, or replacing the entry with its id. */
    fun upsert(list: List<ComposeTemplate>, t: ComposeTemplate): List<ComposeTemplate> =
        if (list.any { it.id == t.id }) list.map { if (it.id == t.id) t else it } else list + t

    fun remove(list: List<ComposeTemplate>, id: String): List<ComposeTemplate> = list.filterNot { it.id == id }
}

/** The on-device store behind [ComposeTemplateCodec]. */
internal object ComposeTemplateStore {
    private const val PREFS = "compose_templates"
    private const val KEY = "templates"

    fun load(context: Context): List<ComposeTemplate> =
        ComposeTemplateCodec.decode(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))

    fun save(context: Context, list: List<ComposeTemplate>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, ComposeTemplateCodec.encode(list)).apply()
    }
}


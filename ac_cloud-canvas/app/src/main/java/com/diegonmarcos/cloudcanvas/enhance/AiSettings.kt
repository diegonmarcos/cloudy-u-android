package com.diegonmarcos.cloudcanvas.enhance

import android.content.Context
import android.content.SharedPreferences
import com.diegonmarcos.superapp.crash.SafeEncryptedPrefs
import com.diegonmarcos.superapp.decisions.link.DecisionsLink
import org.json.JSONObject

/** A [KeyValue] over a SharedPreferences file. */
class PrefsKv(private val p: SharedPreferences) : KeyValue {
    override fun get(key: String): String? = p.getString(key, null)
    override fun put(key: String, value: String?) { p.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply() }
}

/**
 * Configs > AI storage. The choices (model per task, quality, size, cost cap) are plain prefs `canvas_ai`; the app's own OpenRouter key is in
 * the encrypted prefs file `canvas_ai_secret` (SafeEncryptedPrefs, the fleet's secure storage) and nowhere else. It is never logged, never
 * exported (store class secret), and only ever printed masked. Without an own key the run goes through the fleet's decisions engine
 * (#1799, [EngineRoute]), which holds the fleet Account's OpenRouter key in its own process: this app never reads that key.
 */
class AiSettings(private val ctx: Context) {
    val choices = AiChoices(PrefsKv(ctx.getSharedPreferences("canvas_ai", Context.MODE_PRIVATE)))
    private val secret = PrefsKv(SafeEncryptedPrefs.prefs(ctx, "canvas_ai_secret"))

    enum class Source { OWN, FLEET, NONE }

    class Resolved(val key: Secret?, val source: Source, val why: String)

    fun hasOwnKey(): Boolean = !secret.get(KEY).isNullOrBlank()
    fun saveOwnKey(v: String) = secret.put(KEY, v.trim().takeIf { it.isNotEmpty() })
    fun clearOwnKey() = secret.put(KEY, null)

    /** The app's own key when one is saved; null = the run goes through the fleet's decisions engine. */
    fun ownKey(): Secret? = secret.get(KEY)?.takeIf { it.isNotBlank() }?.let { Secret(it) }

    /**
     * The per-app override wins; else the fleet Account's key as the decisions engine reports it (held by the engine, never
     * read here: [Resolved.key] is null for FLEET). Blocking: call off the main thread.
     */
    fun resolve(): Resolved = ownKey()?.let { Resolved(it, Source.OWN, "") } ?: fleet(DecisionsLink.status(ctx))

    companion object {
        private const val KEY = "openrouter_key"

        /** The engine's status as the key line of Configs > AI: FLEET when it serves this app's image use with a key. */
        fun fleet(s: JSONObject): Resolved {
            if (!s.optBoolean("ok", false)) return Resolved(null, Source.NONE, s.optString("detail").ifBlank { "the fleet's AI engine answered " + s.optString("reason") })
            val image = s.optJSONObject("image")
            val use = image?.optJSONObject("uses")?.optJSONObject(EngineRoute.USE)
            if (image == null || !image.optBoolean("served") || use == null) {
                return Resolved(null, Source.NONE, "the fleet's AI engine is too old for image runs; update it from Store ▸ Cloud Constellation ▸ Libs")
            }
            if (!s.optBoolean("token")) return Resolved(null, Source.NONE, "no openrouter key in the fleet Account")
            if (!use.optBoolean("enabled") || !use.optBoolean("allowed_for_app")) return Resolved(null, Source.NONE, "the fleet's AI engine does not allow image runs for this app")
            if (!use.optBoolean("consent")) return Resolved(null, Source.NONE, "image runs are switched off in the SuperApp's AI consent settings")
            return Resolved(null, Source.FLEET, "")
        }
    }
}

package com.diegonmarcos.superapp.decisions.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * #1801 THE FLEET'S AI PROVIDER KEYS, HELD BY THIS ENGINE AND NOWHERE ELSE. Before #1801 the key lived in the keyboard's
 * preferences and crossed the ITextTools binder in both directions (setAiRouting wrote it, revealAiKey read it), so every
 * app that spent it held a copy in memory. Now an app names a provider, a model and a declared use and hands over the
 * request; the engine reads the key here, adds it to the one HTTPS call, and only the provider's answer goes back.
 *
 * TWO SLOTS PER PROVIDER, both kept from before (rule 16, nothing removed): the FLEET key (what the keyboard's AI Routing
 * and the SuperApp's Account used to write) and an optional PER-APP override (what Cloud Calc's Jev "manual override" was),
 * keyed `provider@package`. [get] answers the app's override first, then the fleet key.
 *
 * The keys are written ONLY by the engine's own key-settings screen (KeysActivity, in this process). [status] says whether
 * a slot holds a key and its last four characters, enough to tell two accounts apart and nothing to spend: it is what the
 * `keys` method answers, and nothing in this class's public surface besides [get] returns a key. Never log what [get] returns.
 *
 * Persisted through [store], which the engine seals with the Android Keystore (engine/SealedKv); the plain-JVM suite uses
 * a memory store. Thread-safe.
 */
class KeyBook(private val store: KvStore) {

    @Synchronized
    fun get(provider: String, app: String? = null): String? {
        val doc = store.read() ?: return null
        app?.takeIf { it.isNotBlank() }?.let { a -> doc.optString(slot(provider, a)).trim().takeIf { it.isNotEmpty() }?.let { return it } }
        return doc.optString(slot(provider, null)).trim().takeIf { it.isNotEmpty() }
    }

    /** Keep [value] (trimmed) in the fleet slot of [provider], or [app]'s override slot; a blank value clears the slot. */
    @Synchronized
    fun set(provider: String, value: String?, app: String? = null): Boolean {
        if (provider.isBlank()) return false
        val doc = store.read() ?: JSONObject()
        val v = value?.trim().orEmpty()
        if (v.isEmpty()) doc.remove(slot(provider, app)) else doc.put(slot(provider, app), v)
        store.write(doc)
        return true
    }

    fun clear(provider: String, app: String? = null): Boolean = set(provider, null, app)

    /** Whether the slot itself holds a key (an override slot does not fall back to the fleet one here). */
    @Synchronized
    fun present(provider: String, app: String? = null): Boolean = raw(provider, app) != null

    /** The last four characters of the slot's key, "" when it holds none or the key is too short to show any safely. */
    @Synchronized
    fun hint(provider: String, app: String? = null): String =
        raw(provider, app)?.takeIf { it.length >= MIN_FOR_HINT }?.takeLast(HINT_CHARS).orEmpty()

    /** The apps that hold an override for [provider], in stored order. */
    @Synchronized
    fun overrides(provider: String): List<String> {
        val doc = store.read() ?: return emptyList()
        val prefix = "$provider$SEP"
        return doc.keys().asSequence().filter { it.startsWith(prefix) && doc.optString(it).isNotBlank() }
            .map { it.removePrefix(prefix) }.toList()
    }

    /**
     * What the `keys` method answers for [providers] (id to label): per provider whether the fleet slot holds a key, its
     * hint, and each per-app override's presence and hint. NO KEY IS IN IT, by construction: only [present] and [hint].
     */
    @Synchronized
    fun status(providers: Map<String, String>): JSONObject {
        val list = JSONArray()
        for ((id, label) in providers) {
            val over = JSONArray()
            for (a in overrides(id)) over.put(JSONObject().put("app", a).put("key_present", true).put("key_hint", hint(id, a)))
            list.put(JSONObject().put("id", id).put("label", label).put("key_present", present(id)).put("key_hint", hint(id)).put("overrides", over))
        }
        return JSONObject().put("ok", true).put("providers", list)
    }

    private fun raw(provider: String, app: String?): String? =
        (store.read() ?: return null).optString(slot(provider, app)).trim().takeIf { it.isNotEmpty() }

    private fun slot(provider: String, app: String?): String =
        if (app.isNullOrBlank()) provider else "$provider$SEP$app"

    companion object {
        const val SEP = "@"
        const val HINT_CHARS = 4
        /** A key shorter than this shows no hint: four characters of an eight-character key are half of it. */
        const val MIN_FOR_HINT = 12
    }
}

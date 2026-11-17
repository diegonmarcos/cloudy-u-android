package com.diegonmarcos.cloudcanvas.enhance

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import com.diegonmarcos.superapp.ai.AiConfig
import com.diegonmarcos.superapp.crash.SafeEncryptedPrefs
import com.diegonmarcos.superapp.texttools.TextToolsClient

/** A [KeyValue] over a SharedPreferences file. */
class PrefsKv(private val p: SharedPreferences) : KeyValue {
    override fun get(key: String): String? = p.getString(key, null)
    override fun put(key: String, value: String?) { p.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply() }
}

/**
 * Configs > AI storage. The choices (model per task, quality, size, cost cap) are plain prefs `canvas_ai`; the app's own OpenRouter key is in
 * the encrypted prefs file `canvas_ai_secret` (SafeEncryptedPrefs, the fleet's secure storage) and nowhere else. It is never logged, never
 * exported (store class secret), and only ever printed masked. Without an own key the fleet Account's OpenRouter key is read on each use.
 */
class AiSettings(private val ctx: Context) {
    val choices = AiChoices(PrefsKv(ctx.getSharedPreferences("canvas_ai", Context.MODE_PRIVATE)))
    private val secret = PrefsKv(SafeEncryptedPrefs.prefs(ctx, "canvas_ai_secret"))

    enum class Source { OWN, FLEET, NONE }

    class Resolved(val key: Secret?, val source: Source, val why: String)

    fun hasOwnKey(): Boolean = !secret.get(KEY).isNullOrBlank()
    fun saveOwnKey(v: String) = secret.put(KEY, v.trim().takeIf { it.isNotEmpty() })
    fun clearOwnKey() = secret.put(KEY, null)

    /** The per-app override wins; else the fleet Account's key (libs:text-tools, read per use, no copy kept). Blocking: call off the main thread. */
    fun resolve(): Resolved {
        secret.get(KEY)?.takeIf { it.isNotBlank() }?.let { return Resolved(Secret(it), Source.OWN, "") }
        val provider = runCatching { AiConfig.load(ctx).accountProvider }.getOrDefault("openrouter")
        val c = client ?: synchronized(AiSettings::class.java) { client ?: TextToolsClient(ctx.applicationContext).also { client = it } }
        if (c.isServingAppInstalled()) {
            val until = SystemClock.elapsedRealtime() + 3_000L
            while (!c.isConnected() && SystemClock.elapsedRealtime() < until) Thread.sleep(100)
        }
        val r = c.revealAiKey(provider)
        val v = r.text?.takeIf { it.isNotBlank() }
        return if (v != null) Resolved(Secret(v), Source.FLEET, "") else Resolved(null, Source.NONE, r.error ?: "no $provider key in the fleet Account")
    }

    companion object {
        private const val KEY = "openrouter_key"
        @Volatile private var client: TextToolsClient? = null
    }
}

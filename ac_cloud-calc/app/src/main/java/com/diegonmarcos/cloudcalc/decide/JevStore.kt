package com.diegonmarcos.cloudcalc.decide

import com.diegonmarcos.superapp.crash.SafeEncryptedPrefs
import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import com.diegonmarcos.cloudcalc.BuildConfig
import com.diegonmarcos.cloudcalc.Declarations
import com.diegonmarcos.superapp.decisions.Http
import com.diegonmarcos.cloudcalc.jev.JevConfig
import com.diegonmarcos.cloudcalc.jev.Models
import com.diegonmarcos.superapp.decisions.EngineKeyedHttp
import com.diegonmarcos.superapp.decisions.UrlHttp
import com.diegonmarcos.superapp.decisions.link.DecisionsLink
import com.diegonmarcos.superapp.decisions.link.RelayReply
import org.json.JSONObject

/**
 * Everything the Jev section keeps on the phone (#770), and nothing more:
 *  - the routing declaration: build.json::jev as baked, or the user's edit of that same JSON
 *    (Configs › Routing), validated by [JevConfig.parse] AND against this app's modes first;
 *  - the per-use model choice and the cached decision-model catalogue;
 *  - #1801 NOT the OpenRouter key. It lives in the fleet's AI-key owner engine (Cloud-Lib-Decisions-
 *    Engine, its AI keys screen) and nowhere else: every Jev call's request goes there through
 *    [http] (DecisionsLink.relay, use calc_jev) and the engine adds the key, this app's per-app
 *    override first (what Configs › Token's manual override was, now that engine's per-app slot),
 *    then the fleet key. [token] says only whether one is held and which; the old override that
 *    lived in this app's Keystore-backed prefs is retired on first read ([retireOldOverride]).
 *
 * Token and network calls BLOCK: call them off the main thread.
 */
object JevStore {
    private const val PREFS = "cloud_calc_jev"
    private const val SECRET_PREFS = "cloud_calc_jev_secret"
    private const val K_CONFIG = "config_override"
    private const val K_CATALOG = "catalog"
    private const val K_CATALOG_AT = "catalog_at"
    private const val K_MODEL = "model_"
    private const val K_TOKEN = "openrouter_token"

    /** The app context the relay binds the engine from; set by [config], which every Jev path reads first. */
    @Volatile private var app: Context? = null

    /**
     * #1801 the transport every Jev call uses: an [EngineKeyedHttp], so [com.diegonmarcos.superapp.decisions.Decisions.post]
     * sends with no token. A POST (the Decisions API, the chat) and the key-usage GET go to the AI-key owner engine, which
     * adds the key; any other GET (the public model catalogue) goes straight out. The Robolectric suites swap in a fake.
     */
    @Volatile var http: Http = object : EngineKeyedHttp {
        override fun send(url: String, token: String?, body: String?, timeoutMs: Int): Http.Response {
            val keyRead = body == null && url.contains(KEY_PATH)
            if (body == null && !keyRead) return UrlHttp.send(url, null, null, timeoutMs)
            val ctx = app ?: throw java.io.IOException("the Jev section has not been opened")
            val r = relay(ctx, RelayReply.endpointOf(url).let { if (keyRead) "key" else it }, body.orEmpty(), keyRead)
            if (!r.ok) throw java.io.IOException(r.why)
            return Http.Response(r.code, r.body)
        }
    }

    /** One request through the AI-key owner engine; the suites swap in a fake. */
    @Volatile var relay: (Context, String, String, Boolean) -> RelayReply = { ctx, endpoint, body, get ->
        DecisionsLink.relay(ctx, USE, endpoint, body, get)
    }

    /** The declared relay use (decisions.json::relay.uses). */
    const val USE = "calc_jev"
    private const val KEY_PATH = "/api/v1/key"

    /** The Keystore-backed store the retired override lived in; null where no Keystore exists (Robolectric). */
    @Volatile var secrets: (Context) -> SharedPreferences? = { ctx ->
        runCatching {
            SafeEncryptedPrefs.prefs(ctx, SECRET_PREFS)
        }.getOrNull()
    }

    /** Which key the engine holds for a provider (presence only, never the key); the suites swap in a fake. */
    @Volatile var account: (Context, String) -> Token = { ctx, provider -> readStatus(ctx, provider) }

    private fun prefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ── the routing declaration ─────────────────────────────────────────────────────────────

    val defaultJson: String by lazy {
        JSONObject(String(java.util.Base64.getDecoder().decode(BuildConfig.JEV_CONFIG_B64), Charsets.UTF_8)).toString(2)
    }
    val defaults: JevConfig by lazy { JevConfig.parse(defaultJson) }

    fun isEdited(ctx: Context): Boolean = prefs(ctx).getString(K_CONFIG, null) != null

    /** The declaration in force: the user's edit if it still parses, else the baked default. */
    fun config(ctx: Context): JevConfig {
        app = ctx.applicationContext
        return prefs(ctx).getString(K_CONFIG, null)?.let { runCatching { JevConfig.parse(it) }.getOrNull() } ?: defaults
    }

    fun configJson(ctx: Context): String = prefs(ctx).getString(K_CONFIG, null) ?: defaultJson

    /** Store [json] as the declaration; null when stored, else every reason it was refused. */
    fun saveConfig(ctx: Context, json: String): String? {
        val cfg = runCatching { JevConfig.parse(json) }.getOrElse { return it.message ?: "invalid" }
        appErrors(cfg).takeIf { it.isNotEmpty() }?.let { return it.joinToString("\n") }
        prefs(ctx).edit().putString(K_CONFIG, json).apply()
        return null
    }

    fun resetConfig(ctx: Context) = prefs(ctx).edit().remove(K_CONFIG).apply()

    /** What only this app can check: every tool's mode and form, and every ask key, exist here. */
    fun appErrors(cfg: JevConfig): List<String> {
        val out = mutableListOf<String>()
        for (t in cfg.route.tools) {
            if (t.action == "timer") continue
            val m = Declarations.mode(t.mode)
            if (m == null) { out += "tool ${t.id}: no mode ${t.mode} in this app"; continue }
            if (t.action == "form" && m.forms.none { it.id == t.form }) out += "tool ${t.id}: mode ${t.mode} has no form ${t.form}"
        }
        if (cfg.route.onError == "expression" && Declarations.mode(cfg.route.fallbackMode) == null)
            out += "route.fallback_mode: no mode ${cfg.route.fallbackMode} in this app"
        for (k in cfg.ask.keys) if (k != JevConfig.ANY_MODE && Declarations.mode(k) == null) out += "ask.$k: no such mode"
        return out
    }

    // ── models ──────────────────────────────────────────────────────────────────────────────

    fun catalogue(ctx: Context): List<Models.Info> = Models.parse(prefs(ctx).getString(K_CATALOG, null))
    fun catalogueAt(ctx: Context): Long = prefs(ctx).getLong(K_CATALOG_AT, 0)

    /** Re-read the live catalogue when older than the TTL (or [force]); null when fresh, else why not. */
    fun refreshCatalogue(ctx: Context, force: Boolean): String? {
        val cfg = config(ctx)
        val fresh = System.currentTimeMillis() - catalogueAt(ctx) < cfg.catalogTtlHours * 3_600_000L
        if (!force && fresh && catalogue(ctx).isNotEmpty()) return null
        val res = runCatching { http.send(cfg.modelsUrl, null, null, cfg.timeoutMs) }.getOrElse { return "network: ${it.message ?: it.javaClass.simpleName}" }
        if (res.code !in 200..299) return "HTTP ${res.code}"
        if (Models.parse(res.body).isEmpty()) return "the catalogue listed no decision model"
        prefs(ctx).edit().putString(K_CATALOG, res.body).putLong(K_CATALOG_AT, System.currentTimeMillis()).apply()
        return null
    }

    /** What the user picked for [use] ("route", "score"), else the declaration's. */
    fun modelChoice(ctx: Context, use: String): String = prefs(ctx).getString(K_MODEL + use, null) ?: config(ctx).model(use)

    fun setModelChoice(ctx: Context, use: String, slug: String?) =
        prefs(ctx).edit().apply { if (slug == null) remove(K_MODEL + use) else putString(K_MODEL + use, slug) }.apply()

    /** The model [use] actually runs on (Models.resolve over the cached catalogue). */
    fun model(ctx: Context, use: String): String = Models.resolve(modelChoice(ctx, use), catalogue(ctx), config(ctx).fallbackModel)

    // ── the token (#1801: held by the AI-key owner engine; only whether and which is known here) ──────────

    /** [present] = the engine holds a key this app's calls spend; [source] says which, or why there is none. */
    data class Token(val present: Boolean, val source: String)

    /** Whether the engine holds this app's own override (Configs › Token's manual override, now in the engine). */
    fun hasOverride(ctx: Context): Boolean = token(ctx).source == SOURCE_MANUAL

    /** Which key this app's calls spend: the override while the engine holds one, else the fleet key. Blocks on the engine. */
    fun token(ctx: Context): Token {
        retireOldOverride(ctx)
        return account(ctx, config(ctx).accountProvider)
    }

    /** The engine's AI keys screen: this app's override slot when [override], else the fleet key. False with no engine. */
    fun openKeySettings(ctx: Context, override: Boolean): Boolean =
        DecisionsLink.openKeySettings(ctx, config(ctx).accountProvider, if (override) ctx.packageName else null)

    /**
     * #1801 the override this app used to keep in its own Keystore-backed prefs is removed: the key lives in the engine
     * only. The owner sets it again in the engine's per-app slot (Configs › Token › Set override), which this screen says.
     */
    fun retireOldOverride(ctx: Context): Boolean {
        val s = secrets(ctx) ?: return false
        if (!s.contains(K_TOKEN)) return false
        s.edit().remove(K_TOKEN).apply()
        return true
    }

    const val SOURCE_MANUAL = "manual override"
    const val SOURCE_ACCOUNT = "fleet key"

    private fun readStatus(ctx: Context, provider: String): Token {
        val k = DecisionsLink.keys(ctx)
        if (!k.optBoolean("ok")) return Token(false, "AI keys engine: " + k.optString("detail").ifBlank { k.optString("reason", "no answer") })
        val list = k.optJSONArray("providers")
        val p = (0 until (list?.length() ?: 0)).mapNotNull { list?.optJSONObject(it) }.firstOrNull { it.optString("id") == provider }
            ?: return Token(false, "AI keys engine: no $provider provider")
        val over = p.optJSONArray("overrides")
        val mine = (0 until (over?.length() ?: 0)).mapNotNull { over?.optJSONObject(it) }.any { it.optString("app") == ctx.packageName && it.optBoolean("key_present") }
        return when {
            mine -> Token(true, SOURCE_MANUAL)
            p.optBoolean("key_present") -> Token(true, SOURCE_ACCOUNT)
            else -> Token(false, "AI keys engine: no $provider key")
        }
    }
}

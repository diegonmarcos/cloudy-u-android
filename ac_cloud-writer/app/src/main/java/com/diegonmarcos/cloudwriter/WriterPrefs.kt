package com.diegonmarcos.cloudwriter

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.diegonmarcos.superapp.texttools.TextToolsClient
import org.json.JSONObject

/**
 * cloud-writer's OWN text-tool settings — including THE PER-TOOL MODEL, which is the one thing
 * here that exists nowhere else in the fleet.
 *
 * SEPARATE BY THE PLATFORM, NOT BY A NAMING CONVENTION. These are cloud-writer's
 * SharedPreferences, in cloud-writer's data directory, under cloud-writer's user id. This
 * application declares no `sharedUserId`, no settings ContentProvider and no shared backup agent,
 * so Android itself is what keeps this store and the serving application's apart: there is no key
 * cloud-writer can write that the keyboard can read, and none it can read that the keyboard can
 * write. That is why the key names below can safely echo the keyboard's — same name, different
 * file, different application, no relationship.
 *
 * NO CREDENTIAL IS STORED HERE, AND THERE IS NO KEY FOR ONE. Choosing a model is a setting;
 * holding the account that pays for it is not. cloud-writer names a provider and a model and the
 * serving application spends the credential — which is exactly what `enhanceWith` and
 * `summariseWith` exist for. `settingsSnapshot()`, which [seedFromServingApp] reads, carries no
 * key by construction on the far side, so there is nothing arriving here to store even by
 * accident.
 *
 * @see WriterRegistry for the prompts and model lists these ids resolve against.
 */
object WriterPrefs {

    /**
     * cloud-writer's own preference file, named for the feature. NOT the default
     * `<package>_preferences` file: these values are read on a background thread during seeding,
     * and keeping them out of the file the rest of the application edits means a seed cannot
     * contend with an unrelated write.
     */
    private const val FILE = "text_tools"

    /** Provider id, e.g. "openrouter". */
    const val KEY_PROVIDER = "ai_provider"

    /** + provider id → the model id chosen for that provider, for any tool with no override. */
    const val KEY_MODEL_PREFIX = "ai_model_"

    /**
     * + tool id + "_" + provider id → THE MODEL THIS ONE TOOL USES. The owner's request, in one
     * preference key: "here we will define the AI model to do Summary, Grammar Only, Text
     * Enhance" — and Translate, added later, because "missing the option to select the model that
     * would do only translation" was a gap in exactly this key.
     *
     * KEYED BY TOOL **AND** PROVIDER, not by tool alone. A model id is only meaningful inside the
     * provider that serves it, so a per-tool key without the provider would hand an OpenRouter
     * model id to the mesh bridge the first time the owner switched provider — a route that
     * resolves to nothing and fails at call time with no screen having said so. It also means
     * switching provider and back remembers each provider's per-tool choices instead of flattening
     * them.
     *
     * UNSET FALLS BACK, and the fallback chain is deliberately two steps rather than one:
     * this tool's model, then the provider's [KEY_MODEL_PREFIX] model, then the registry's
     * `default_model`. So an owner who has never opened the per-tool section gets exactly the
     * behaviour of an application without one, and setting Summary alone leaves Enhance and
     * Grammar where they were.
     */
    const val KEY_TOOL_MODEL_PREFIX = "ai_tool_model_"

    /** Text Enhancement: which style, and the three lines appended after it. */
    const val KEY_ENHANCE_STYLE = "enhance_style"
    const val KEY_ENHANCE_TONE = "enhance_tone"
    const val KEY_ENHANCE_LENGTH = "enhance_length"
    const val KEY_ENHANCE_LANGUAGE = "enhance_language"

    /** Text Resume: which summary shape. */
    const val KEY_SUMMARY_STYLE = "summary_style"

    /**
     * Text Enhancement: WHAT the tool rewrites. "auto" | "selection" | "field".
     *
     * Honoured by [MainActivity]: the input box has a selection like the keyboard's field does,
     * so this is a live setting here and not a copied label.
     */
    const val KEY_ENHANCE_SCOPE = "enhance_scope"

    /**
     * Text Enhancement: the magic-wand key on the toolbar.
     *
     * STORED, AND SAID OUT LOUD TO BE INERT. cloud-writer draws no toolbar, so this switch is a
     * value this application keeps and nothing here reads. It exists because the owner asked for
     * the SAME pages and a missing row is a page that does not match; the row carries
     * `enhance_toolbar_writer_note` so it is never a control that silently does nothing. In the
     * keyboard the equivalent switch is not a pref at all — it edits the toolbar-keys list — which
     * is another reason it could not have been shared even if sharing were wanted.
     */
    const val KEY_ENHANCE_TOOLBAR_KEY = "enhance_toolbar_key"

    /** Translation: BCP-47 target tag; "" = the serving engine's own default. */
    const val KEY_TRANSLATE_TARGET = "translate_default_target"

    /**
     * Translation, the three translate-BAR settings. Stored here, this application's own, and
     * currently read by nothing in this application — see `translate_writer_note`, which is on the
     * page saying exactly that. Copied rather than dropped so the page matches the keyboard's.
     */
    const val KEY_TRANSLATE_AUTO_DETECT = "translate_auto_detect"
    const val KEY_TRANSLATE_APPLY_MODE = "translate_apply_mode"
    const val KEY_TRANSLATE_LIVE_COMMIT = "translate_live_commit"

    /**
     * Grammar check: "off" | "local" | "remote" | "ai".
     *
     * ALL FOUR ARE HONOURED BY [WriterToolRunner], and two of them by REFUSING with a reason
     * rather than by working: "off" because the owner turned it off, and "remote" because reaching
     * LanguageTool needs a socket this application deliberately does not have and a binder method
     * that does not exist yet. A mode that silently did the AI rewrite instead would put "improve
     * this text" behind a button the owner set to Remote.
     */
    /** #800 System / Light / Dark — ui.WriterTheme. */
    const val KEY_THEME = "writer_theme"

    const val KEY_GRAMMAR_MODE = "grammar_mode"

    /** Grammar check, the three local fixes. Live: "local" mode applies exactly the ones that are on. */
    const val KEY_GRAMMAR_FIX_CAPITALIZE_I = "grammar_fix_capitalize_i"
    const val KEY_GRAMMAR_FIX_SENTENCE_CAPS = "grammar_fix_sentence_caps"
    const val KEY_GRAMMAR_FIX_REPEATED_WORDS = "grammar_fix_repeated_words"

    /**
     * Grammar check: the LanguageTool endpoint, and the Portuguese variant and n-gram slot beside
     * it.
     *
     * MESH-ONLY, AND LEFT THAT WAY. [DEFAULT_GRAMMAR_REMOTE_URL] is infra-ai_languagetool, which
     * answers on the WireGuard mesh and nowhere else. It is copied from the keyboard unchanged;
     * pointing this at a public LanguageTool would send the owner's text to a third party that
     * never had it, which is a bigger change than the one word it would take to make.
     */
    const val KEY_GRAMMAR_REMOTE_URL = "grammar_remote_url"
    const val KEY_GRAMMAR_PT_VARIANT = "grammar_pt_variant"
    const val KEY_GRAMMAR_NGRAM_URL = "grammar_ngram_url"

    // ---- defaults, this application's own copy of the keyboard's ----
    //
    // COPIED VALUES, NOT COPIED STORAGE. Each of these is the same value the keyboard's
    // Defaults.kt holds, so an untouched cloud-writer page reads exactly like an untouched
    // keyboard page. Changing one here changes nothing there, which is the entire point.

    const val SCOPE_AUTO = "auto"
    const val SCOPE_SELECTION = "selection"
    const val SCOPE_FIELD = "field"
    const val DEFAULT_ENHANCE_SCOPE = SCOPE_AUTO
    const val DEFAULT_ENHANCE_TOOLBAR_KEY = true

    const val DEFAULT_TRANSLATE_AUTO_DETECT = true
    const val APPLY_INSERT = "insert"
    const val APPLY_REPLACE = "replace"
    const val DEFAULT_TRANSLATE_APPLY_MODE = APPLY_INSERT
    const val DEFAULT_TRANSLATE_LIVE_COMMIT = true

    const val GRAMMAR_OFF = "off"
    const val GRAMMAR_LOCAL = "local"
    const val GRAMMAR_REMOTE = "remote"
    const val GRAMMAR_AI = "ai"

    /**
     * "ai", AND THIS IS THE ONE DEFAULT THAT DELIBERATELY DIFFERS FROM THE KEYBOARD'S.
     *
     * The keyboard defaults to "remote" and can honour it: it holds INTERNET and talks to
     * LanguageTool itself. cloud-writer opens no socket at all, so a "remote" default would greet
     * the owner with a Grammar button that refuses on its first tap — a working tool turned into a
     * refusal by a page that was added to make things better. "ai" is what this application's
     * Grammar button already does today, so the page describes the behaviour the owner has rather
     * than replacing it. Remote is still in the menu and still says why it cannot run.
     *
     * NAMED IN THE REPORT, not slipped in. The row is the one place these two applications' pages
     * do not read the same on first open.
     */
    const val DEFAULT_GRAMMAR_MODE = GRAMMAR_AI

    const val DEFAULT_GRAMMAR_FIX_CAPITALIZE_I = true
    const val DEFAULT_GRAMMAR_FIX_SENTENCE_CAPS = true
    const val DEFAULT_GRAMMAR_FIX_REPEATED_WORDS = true
    /** #1093 the declared languagetool URL, baked from the one fleet declaration (BuildConfig.LANGUAGETOOL_URL). */
    val DEFAULT_GRAMMAR_REMOTE_URL: String get() = BuildConfig.LANGUAGETOOL_URL
    const val DEFAULT_GRAMMAR_PT_VARIANT = "pt-PT"
    const val DEFAULT_GRAMMAR_NGRAM_URL = ""

    /**
     * Set once the values below have been seeded from whatever the owner had already configured.
     *
     * Its ABSENCE is what triggers [seedFromServingApp], so it is written only after a seed that
     * actually got an answer. A seed attempted while nothing is bound must leave this unset and
     * try again later: marking the application seeded because the peer happened to be unreachable
     * for one call is how a migration silently resets someone's settings to defaults.
     */
    private const val KEY_SEEDED = "seeded_from_serving_app"

    /** [TextToolsClient.settingsSnapshot]'s object of provider id → chosen model id. */
    private const val SNAPSHOT_MODELS = "ai_models"

    private const val TAG = "WriterPrefs"

    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ---- reads: every one falls back to this application's registry, never to a peer's ----

    fun providerId(context: Context): String =
        prefs(context).getString(KEY_PROVIDER, null) ?: WriterRegistry.defaultProvider

    /** The provider-wide model — what a tool with no override of its own uses. */
    fun modelId(context: Context, providerId: String = providerId(context)): String {
        val provider = WriterRegistry.provider(providerId)
        return prefs(context).getString(KEY_MODEL_PREFIX + provider.id, null)
            ?.takeIf { it.isNotBlank() }
            ?: provider.defaultModel
    }

    /**
     * THE MODEL [tool] RUNS ON. The whole point of the per-tool section, and the only read in this
     * file a run actually uses.
     *
     * A stored id that the registry no longer holds is IGNORED rather than sent. Model catalogues
     * are edited — a model is withdrawn, an id is renamed — and a route to a model the provider
     * has never heard of fails at call time, mid-run, with the owner looking at a progress line.
     * Falling back here means the tool keeps working and the picker simply shows the fallback,
     * which is a state the owner can see and correct.
     *
     * THREE STEPS, IN THIS ORDER: what the owner picked for THIS tool, then the model the registry
     * nominates for this tool under this provider, then the provider's own default. The middle
     * step is [WriterRegistry.Provider.toolModels] and it is why a fresh install translates on a
     * translation-ranked model and summarises on a cheap one instead of running all four tools on
     * whichever single model the provider happens to lead with. Every step is checked against the
     * live catalogue for the same reason the first one is.
     */
    fun modelFor(context: Context, tool: WriterTool, providerId: String = providerId(context)): String {
        val provider = WriterRegistry.provider(providerId)
        fun known(id: String?): String? = id?.takeIf { candidate -> provider.models.any { it.id == candidate } }
        val chosen = known(prefs(context).getString(KEY_TOOL_MODEL_PREFIX + tool.id + "_" + provider.id, null))
        return chosen ?: known(provider.toolModels[tool.id]) ?: modelId(context, provider.id)
    }

    fun enhanceStyleId(context: Context): String =
        prefs(context).getString(KEY_ENHANCE_STYLE, null) ?: WriterRegistry.defaultStyle

    fun enhanceToneId(context: Context): String =
        prefs(context).getString(KEY_ENHANCE_TONE, null) ?: WriterRegistry.defaultTone

    fun enhanceLengthId(context: Context): String =
        prefs(context).getString(KEY_ENHANCE_LENGTH, null) ?: WriterRegistry.defaultLength

    fun enhanceLanguageId(context: Context): String =
        prefs(context).getString(KEY_ENHANCE_LANGUAGE, null) ?: WriterRegistry.defaultLanguage

    fun summaryStyleId(context: Context): String =
        prefs(context).getString(KEY_SUMMARY_STYLE, null) ?: WriterRegistry.defaultSummary

    fun translateTarget(context: Context): String =
        prefs(context).getString(KEY_TRANSLATE_TARGET, null).orEmpty()

    fun enhanceScope(context: Context): String =
        prefs(context).getString(KEY_ENHANCE_SCOPE, null) ?: DEFAULT_ENHANCE_SCOPE

    fun grammarMode(context: Context): String =
        prefs(context).getString(KEY_GRAMMAR_MODE, null) ?: DEFAULT_GRAMMAR_MODE

    fun grammarRemoteUrl(context: Context): String =
        prefs(context).getString(KEY_GRAMMAR_REMOTE_URL, null) ?: DEFAULT_GRAMMAR_REMOTE_URL

    fun string(context: Context, key: String, fallback: String): String =
        prefs(context).getString(key, null) ?: fallback

    fun flag(context: Context, key: String, fallback: Boolean): Boolean =
        prefs(context).getBoolean(key, fallback)

    // ---- writes: each one lands in THIS application's file only ----

    fun put(context: Context, key: String, value: String) {
        prefs(context).edit().putString(key, value).apply()
    }

    fun putFlag(context: Context, key: String, value: Boolean) {
        prefs(context).edit().putBoolean(key, value).apply()
    }

    /** The provider-wide model, so switching provider and back remembers each choice. */
    fun putModel(context: Context, providerId: String, modelId: String) =
        put(context, KEY_MODEL_PREFIX + providerId, modelId)

    /** The model for ONE tool under ONE provider. */
    fun putToolModel(context: Context, tool: WriterTool, providerId: String, modelId: String) =
        put(context, KEY_TOOL_MODEL_PREFIX + tool.id + "_" + providerId, modelId)

    // ---- the composed prompts a run sends. Built from THIS application's values, sent whole. ----

    fun enhancePrompt(context: Context): String = WriterRegistry.enhancePrompt(
        enhanceStyleId(context),
        enhanceToneId(context),
        enhanceLengthId(context),
        enhanceLanguageId(context),
    )

    /** #1418 the Drafter's prompt over the Enhancer's tone, length and language rows. */
    fun draftPrompt(context: Context): String =
        WriterRegistry.draftPrompt(enhanceToneId(context), enhanceLengthId(context), enhanceLanguageId(context))

    fun summaryPrompt(context: Context): String =
        WriterRegistry.summaryPrompt(summaryStyleId(context))

    /**
     * The Translate prompt, or NULL when Language Output is still on "keep my language".
     *
     * Reads the SAME [KEY_ENHANCE_LANGUAGE] the Enhance prompt reads, because the options row
     * under the text box is one row serving both tools — the owner asked for "all the options for
     * the enhance/translation" in one place, and two Language Output settings in two places is the
     * split that made Translate and Enhance disagree about the target language.
     */
    fun translatePrompt(context: Context): String? =
        WriterRegistry.translatePrompt(enhanceLanguageId(context))

    /** Whether this application's chosen summary prompt asked the model for a list. */
    fun summaryWantsBullets(context: Context): Boolean =
        WriterRegistry.summary(summaryStyleId(context)).bullets

    // ---- migration ----

    fun isSeeded(context: Context): Boolean = prefs(context).getBoolean(KEY_SEEDED, false)

    /**
     * Adopt, ONCE, whatever the owner had already configured in the serving application, so that
     * opening cloud-writer for the first time does not look like their configuration being thrown
     * away.
     *
     * The owner has been using these tools from the keyboard and from cloud-mail for months. The
     * style they pinned, the tone, the summary shape, the model they chose — all of it lives in
     * the serving application's store. Starting cloud-writer at the registry defaults would reset
     * every one of them as far as the owner can tell, so the first run reads what is there and
     * keeps it.
     *
     * WHAT IT ADOPTS: provider, the chosen model for each provider, Enhance style/tone/length/
     * output language, the Resume shape, the translation target. WHAT IT DOES NOT: the API key,
     * which the snapshot does not carry and this application has no place to put; and the PER-TOOL
     * models, which no peer has because no peer has ever had per-tool models — they start at the
     * adopted provider-wide model, which is precisely the behaviour the owner has today.
     *
     * ONLY EVER A STARTING POINT. After this runs the two stores are unrelated; editing one never
     * touches the other, and this function never runs again.
     *
     * BLOCKING — it is a binder call. Callers put it on a background thread.
     */
    fun seedFromServingApp(context: Context, client: TextToolsClient) {
        if (isSeeded(context)) return
        val raw = client.settingsSnapshot()
        if (raw.isNullOrBlank()) {
            // Nothing bound, or a peer too old to know the method. Stay unseeded and try again
            // next time rather than declaring the owner's settings to be the defaults.
            Log.i(TAG, "no settings snapshot available yet - leaving unseeded, will retry")
            return
        }
        val snapshot = runCatching { JSONObject(raw) }.getOrElse {
            Log.w(TAG, "settings snapshot was not JSON - leaving unseeded", it)
            return
        }
        val edit = prefs(context).edit()

        // Only ids THIS application's registry knows. The registries are separate copies and the
        // peer's may hold a style or a model that cloud-writer's does not; adopting one of those
        // would store a value no screen here can display or change, which is the uneditable state
        // this whole arrangement exists to end.
        fun adopt(key: String, known: List<WriterRegistry.Style>) {
            val id = snapshot.optString(key).takeIf { it.isNotEmpty() } ?: return
            if (known.any { it.id == id }) edit.putString(key, id)
        }
        adopt(KEY_ENHANCE_STYLE, WriterRegistry.styles)
        adopt(KEY_ENHANCE_TONE, WriterRegistry.tones)
        adopt(KEY_ENHANCE_LENGTH, WriterRegistry.lengths)
        adopt(KEY_ENHANCE_LANGUAGE, WriterRegistry.languages)
        adopt(KEY_SUMMARY_STYLE, WriterRegistry.summaries)

        snapshot.optString(KEY_PROVIDER).takeIf { id -> WriterRegistry.providers.any { it.id == id } }
            ?.let { edit.putString(KEY_PROVIDER, it) }

        snapshot.optJSONObject(SNAPSHOT_MODELS)?.let { models ->
            WriterRegistry.providers.forEach { provider ->
                val chosen = models.optString(provider.id).takeIf { it.isNotEmpty() } ?: return@forEach
                if (provider.models.any { it.id == chosen }) {
                    edit.putString(KEY_MODEL_PREFIX + provider.id, chosen)
                }
            }
        }

        // A target tag is free text from an engine's language list rather than a registry id, so
        // there is nothing here to validate it against; it is taken as given, and an empty one
        // means "the engine's default", which is also this application's default.
        edit.putString(KEY_TRANSLATE_TARGET, snapshot.optString(KEY_TRANSLATE_TARGET))

        edit.putBoolean(KEY_SEEDED, true).apply()
        Log.i(TAG, "seeded cloud-writer's text-tool settings from the serving app's, once")
    }
}

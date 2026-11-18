// SPDX-License-Identifier: GPL-3.0-only
package com.diegonmarcos.superapp.texttools

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.diegonmarcos.superapp.translate.TranslateEngines
import com.diegonmarcos.superapp.translate.TranslatePrefs
import com.diegonmarcos.superapp.translate.Translator
import helium314.keyboard.latin.AiRouter
import helium314.keyboard.latin.TextEnhancer
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.prefs
import org.json.JSONArray
import org.json.JSONObject

/**
 * Serves [ITextTools] out of the app that owns the Text tools.
 *
 * THIS IS THE WHOLE POINT OF THE MODULE. The two engines and their settings stay in
 * one process — this one. A sibling app asking for a rewrite does not link AiRouter,
 * does not read the routing prefs, and above all never receives the provider key: the
 * key is read here, spent here on one HTTPS request, and only the resulting TEXT
 * crosses the binder. There is no second copy of the credential path because there is
 * no second holder of the credential.
 *
 * ONE ENGINE, SEVERAL SETS OF SETTINGS — and the two halves of that sentence are why this
 * class has two families of methods.
 *
 * [enhance], [translate] and [summarise] resolve the prompt, the model and the translation
 * target out of THIS app's preferences. They are what the keyboard's own surfaces need, and
 * for a while they were all a sibling app had, which made this app the only owner of every
 * one of those settings: cloud-mail's Text pages could only ever be the keyboard's pages,
 * and the owner could look at their mail settings without being able to change any of them.
 *
 * [enhanceWith] and [summariseWith] take the prompt and the model FROM THE CALLER. An app
 * that holds its own copy of the registry and its own preferences sends its decision and
 * gets its own rewrite. What it still does not get, and what makes the split safe, is the
 * engine: the HTTP call, the chunking, the summary budget, the bullet enforcement, the error
 * wording and the API KEY all stay here in one copy. Duplicating settings is what the owner
 * asked for; duplicating the credential path is what this module exists to prevent, and the
 * two are not the same request.
 *
 * ROUTING, AND IT MUST NOT BE CROSSED:
 *   [enhance]/[enhanceWith]     -> AiRouter / TextEnhancer  — the OpenRouter-shape provider.
 *   [summarise]/[summariseWith] -> AiRouter                 — the SAME provider, another prompt.
 *   [translate]                 -> Translator / TranslateEngines — the translation library.
 * The LLM pair and the translator may never reach each other's engine. They cost different money,
 * they answer differently, and a caller cannot tell from a reply which one produced it.
 * [enhance] and [summarise] deliberately DO share an engine: they are one feature's plumbing asked
 * two questions, and a second copy of the routing and credential path for the second question is
 * the duplication this whole module exists to prevent.
 *
 * Access is decided entirely by the manifest: the service is exported under
 * [TextTools.PERMISSION], declared `signature`, so the platform turns away any caller
 * not signed with the Cloud key before a line of this class runs. No uid check here —
 * re-implementing what the package manager already enforces, worse, is how those
 * checks come to disagree.
 *
 * Binder transactions arrive on a pool thread, never the main one, which is what makes
 * calling the blocking engines directly correct here.
 */
class TextToolsService : Service() {

    override fun onBind(intent: Intent?): IBinder = binder

    private val binder = object : ITextTools.Stub() {

        override fun enhance(text: String?, styleId: String?): Array<String> {
            val body = text.orEmpty()
            if (body.isBlank()) return failed("Nothing to enhance")
            val style = if (styleId.isNullOrEmpty()) {
                // The style, tone, length and output language pinned in Text Enhancements —
                // the same prompt the ENHANCE toolbar key builds, so the two surfaces cannot
                // produce different rewrites from the same settings.
                AiRouter.enhanceStyle(this@TextToolsService)
            } else {
                AiRouter.styleById(styleId)
            }
            return try {
                // rewrite(), not complete(): it splits text past the provider's input budget
                // on paragraph, then line, then sentence boundaries and rejoins on the
                // whitespace it cut at. An email body is routinely longer than one request,
                // which is the case the keyboard's short buffer rarely hit.
                ok(TextEnhancer.rewrite(this@TextToolsService, style, body))
            } catch (e: AiRouter.NoTokenException) {
                failed("No API key for ${e.provider.label} — set one in AI Routing")
            } catch (e: Exception) {
                Log.w(TAG, "enhance failed", e)
                failed(e.message ?: e.javaClass.simpleName)
            }
        }

        override fun translate(text: String?, targetTag: String?): Array<String> {
            val body = text.orEmpty()
            if (body.isBlank()) return failed("Nothing to translate")
            val result = try {
                Translator.translateNow(this@TextToolsService, body, targetTag.orEmpty())
            } catch (e: Exception) {
                Log.w(TAG, "translate failed", e)
                return failed(e.message ?: e.javaClass.simpleName)
            }
            // Translator already turns every failure into a readable reason rather than a
            // null; pass that through instead of inventing a second wording for it.
            return result.text?.let { ok(it) } ?: failed(result.error ?: "Translate failed")
        }

        /**
         * "AI Resume" / "Text Resume" — SUMMARISE this message. The owner's product name, kept
         * exactly; it is not a curriculum vitae and it does not resume anything.
         *
         * THE SAME ENGINE AS [enhance], ON PURPOSE. Same provider, same key, same model, same
         * timeout, same error wording — the routing, credential and progress machinery is not
         * copied for a third feature, it is called with a different prompt set. Only two things
         * differ, and both are deliberate:
         *
         *  - the prompt comes from keyboard_ai.summaries via AiRouter.summaryStyle, not from the
         *    Enhance styles. Mixing the two menus would offer "improve this" to a feature that
         *    promises "shorten this".
         *  - it goes through AiRouter.summarise rather than TextEnhancer.rewrite. rewrite() splits
         *    input past the provider's budget and rejoins the answers, which for a rewrite is right
         *    and for a summary is exactly wrong: it produces a summary PER PIECE, concatenated, and
         *    a "summary" longer than the mail it was made from.
         *
         * NOTHING OF THE SUMMARY IS DECIDED HERE. The budget, the truncation note and the check
         * that a reply asked for bullets came back as bullets all live in AiRouter.summarise, which
         * the keyboard's own Text Resume bar also calls directly. That is what makes cloud-mail's
         * AI Resume and the keyboard's Text Resume the same feature rather than two features that
         * agree today: this method contributes the binder, and not one rule about summarising.
         */
        override fun summarise(text: String?, summaryId: String?): Array<String> {
            val body = text.orEmpty()
            if (body.isBlank()) return failed("Nothing to summarise")
            val style = if (summaryId.isNullOrEmpty()) {
                AiRouter.summaryStyle(this@TextToolsService)
            } else {
                AiRouter.summaryById(summaryId)
            }
            return try {
                ok(AiRouter.summarise(this@TextToolsService, style, body))
            } catch (e: AiRouter.NoTokenException) {
                failed("No API key for ${e.provider.label} — set one in AI Routing")
            } catch (e: Exception) {
                Log.w(TAG, "summarise failed", e)
                failed(e.message ?: e.javaClass.simpleName)
            }
        }

        /**
         * [enhance], against a prompt and a model the CALLER owns.
         *
         * The difference from [enhance] is only where the two decisions come from: there, this
         * app's preferences; here, the caller's. Everything that makes a rewrite a rewrite —
         * TextEnhancer.rewrite's splitting on paragraph/line/sentence, the rejoin, the timeout,
         * the wording of every failure — is the same code on the same side of the binder, and the
         * provider key is still read here and never sent anywhere.
         *
         * The caller hands over a FULLY COMPOSED system prompt. Nothing is appended to it: this
         * app's preamble, style, tone, length and language are this app's settings, and mixing
         * them into another app's prompt is precisely the sharing the owner asked to be undone.
         */
        override fun enhanceWith(
            text: String?,
            systemPrompt: String?,
            providerId: String?,
            modelId: String?,
        ): Array<String> {
            val body = text.orEmpty()
            if (body.isBlank()) return failed("Nothing to enhance")
            val prompt = systemPrompt.orEmpty()
            // An empty prompt would send the text with no instructions at all, and the model
            // would answer it as a message rather than rewrite it. That is a caller bug and it
            // says so, rather than quietly falling back to THIS app's prompt — which would hand
            // the caller settings it does not own and cannot see.
            if (prompt.isBlank()) return failed("No prompt was sent with this rewrite")
            val route = AiRouter.routeOf(this@TextToolsService, providerId, modelId)
            return try {
                ok(TextEnhancer.rewrite(
                    this@TextToolsService,
                    AiRouter.Style(CALLER_STYLE_ID, CALLER_STYLE_ID, prompt),
                    body,
                    route = route,
                ))
            } catch (e: AiRouter.NoTokenException) {
                failed("No API key for ${e.provider.label} — set one in AI Routing")
            } catch (e: Exception) {
                Log.w(TAG, "enhanceWith failed", e)
                failed(e.message ?: e.javaClass.simpleName)
            }
        }

        /**
         * [summarise], against a prompt and a model the CALLER owns — [enhanceWith]'s split
         * applied to Text Resume.
         *
         * Still the one-request summariser, not the chunking rewriter, and still AiRouter's own
         * budget, truncation note and bullet enforcement. [bullets] travels because only the
         * caller now knows which of ITS prompts asked for a list; what to DO about that answer
         * stays here, so both apps enforce bullets the same way.
         */
        override fun summariseWith(
            text: String?,
            systemPrompt: String?,
            bullets: Boolean,
            providerId: String?,
            modelId: String?,
        ): Array<String> {
            val body = text.orEmpty()
            if (body.isBlank()) return failed("Nothing to summarise")
            val prompt = systemPrompt.orEmpty()
            if (prompt.isBlank()) return failed("No prompt was sent with this summary")
            val route = AiRouter.routeOf(this@TextToolsService, providerId, modelId)
            return try {
                ok(AiRouter.summarise(
                    this@TextToolsService,
                    AiRouter.Style(CALLER_STYLE_ID, CALLER_STYLE_ID, prompt, bullets),
                    body,
                    route,
                ))
            } catch (e: AiRouter.NoTokenException) {
                failed("No API key for ${e.provider.label} — set one in AI Routing")
            } catch (e: Exception) {
                Log.w(TAG, "summariseWith failed", e)
                failed(e.message ?: e.javaClass.simpleName)
            }
        }

        override fun enhanceProviderLabel(): String =
            AiRouter.provider(this@TextToolsService).label

        override fun providerLabelFor(providerId: String?): String =
            AiRouter.routeOf(this@TextToolsService, providerId, null).provider.label

        /**
         * This app's current text-tool choices, for a sibling seeding its own copy of them.
         *
         * WITHOUT THE TOKEN, and that omission is the design rather than an oversight. A caller
         * adopting these settings is adopting the owner's CHOICES — which provider, which model,
         * which style, which summary shape, which translation target. The credential is not a
         * choice, it is an account, and it stays the single copy that only this process holds.
         * Anything added to this object later has to pass the same test.
         */
        override fun settingsSnapshot(): String {
            val context = this@TextToolsService
            val prefs = context.prefs()
            val provider = AiRouter.provider(context)
            val models = JSONObject()
            AiRouter.providers.forEach { p -> models.put(p.id, AiRouter.model(context, p)) }
            return JSONObject()
                .put(Settings.PREF_AI_PROVIDER, provider.id)
                .put(SNAPSHOT_MODELS, models)
                .put(Settings.PREF_ENHANCE_STYLE, prefs.getString(Settings.PREF_ENHANCE_STYLE, AiRouter.defaultStyle))
                .put(Settings.PREF_ENHANCE_TONE, prefs.getString(Settings.PREF_ENHANCE_TONE, AiRouter.defaultTone))
                .put(Settings.PREF_ENHANCE_LENGTH, prefs.getString(Settings.PREF_ENHANCE_LENGTH, AiRouter.defaultLength))
                .put(Settings.PREF_ENHANCE_LANGUAGE, prefs.getString(Settings.PREF_ENHANCE_LANGUAGE, AiRouter.defaultLanguage))
                .put(Settings.PREF_SUMMARY_STYLE, prefs.getString(Settings.PREF_SUMMARY_STYLE, AiRouter.defaultSummary))
                .put(TranslatePrefs.KEY_DEFAULT_TARGET, TranslatePrefs.defaultTarget(context))
                .toString()
        }

        override fun translateLanguages(): List<String> =
            TranslateEngines.client?.supportedLanguages().orEmpty().ifEmpty { TranslatePrefs.FALLBACK_LANGS }

        /**
         * This app's whole AI-Routing state for a fleet console to render — see [ITextTools].
         *
         * THE MODEL ROWS ARE THE REGISTRY'S OWN, copied field for field out of [AiRouter.providers]
         * and never restated. That is the point of shipping them at all: a console that held its own
         * list would be a second registry, and a second registry is what put a stale price and a
         * wrong unit on this fleet's screens twice this week. Prices go out under names that carry
         * their unit, unconverted, exactly as [AiRouter.Pricing] holds them.
         *
         * NO KEY CROSSES HERE. The hint is at most four trailing characters, which is enough to
         * tell two accounts apart and not enough to spend either. #1801 no method of this service
         * emits a key: the key-needing providers' keys live in the AI-key owner engine.
         */
        override fun aiRoutingSnapshot(): String {
            val context = this@TextToolsService
            val providers = JSONArray()
            AiRouter.providers.forEach { p ->
                // #1801 presence and the last four characters: the AI-key owner engine's for a key-needing provider,
                // this keyboard's optional gateway token for the mesh bridge. Never a key.
                val (present, hint) = AiRouter.keyState(context, p)
                val models = JSONArray()
                p.models.forEach { m ->
                    // pricing() prefers the live catalog over the baked row, which is what the
                    // keyboard's own routing table shows — so the console and the table cannot
                    // quote different numbers for the same model on the same phone.
                    val price = AiRouter.pricing(context, p, m)
                    models.put(JSONObject()
                        .put("id", m.id)
                        .put("name", m.name)
                        .put("open", m.open)
                        .put("params_b", m.paramsB ?: JSONObject.NULL)
                        .put("quant", JSONArray(m.quant))
                        .put("trained_for", JSONArray(m.trainedFor))
                        .put("note", m.note ?: JSONObject.NULL)
                        .put("prompt_usd_per_million", price?.promptUsdPerMillionTokens ?: JSONObject.NULL)
                        .put("completion_usd_per_million", price?.completionUsdPerMillionTokens ?: JSONObject.NULL))
                }
                providers.put(JSONObject()
                    .put("id", p.id)
                    .put("label", p.label)
                    .put("needs_token", p.needsToken)
                    .put("default_model", p.defaultModel)
                    .put("chosen_model", AiRouter.model(context, p))
                    .put("key_present", present)
                    .put("key_hint", if (present) hint.takeLast(4) else "")
                    .put("pricing_as_of", p.pricingAsOf ?: JSONObject.NULL)
                    .put("models", models))
            }
            return JSONObject()
                .put("app", packageName)
                .put("default_provider", AiRouter.provider(context).id)
                .put("providers", providers)
                .toString()
        }

        /**
         * #1801 the retired slot of setAiRouting: no key is written here any more (the fleet's AI provider keys live in the
         * AI-key owner engine). An older console still sending the four arguments gets this refusal and nothing is stored.
         */
        override fun retiredRoutingWrite(): Array<String> =
            failed("AI keys moved to the AI keys engine (Cloud Lib Decisions Engine ▸ AI keys); models are chosen in AI Routing")

        /**
         * #1801 the retired slot of revealAiKey: no key leaves this process, because none is here. A key is revealed on the
         * AI-key owner engine's own screen, in its own process.
         */
        override fun retiredRevealSlot(): Array<String> =
            failed("AI keys are revealed in the AI keys engine (Cloud Lib Decisions Engine ▸ AI keys), not over this binder")

        /** #781 See [ITextTools.clipboardLists]. Null when the clipboard store cannot open (device locked). */
        override fun clipboardLists(): String? =
            runCatching { helium314.keyboard.latin.database.ClipboardDao.getInstance(this@TextToolsService)?.exportJson()?.toString() }
                .getOrNull()

        /** #781 See [ITextTools.importClipboardLists]. The reason travels; the lists never reach a log. */
        override fun importClipboardLists(json: String?): Array<String> = importLists(json, replace = true)

        /** #781 See [ITextTools.clipboardListsSlice]. */
        override fun clipboardListsSlice(start: Int, length: Int): String? {
            val all = clipboardLists() ?: return null
            if (start >= all.length || length <= 0) return ""
            return all.substring(start.coerceAtLeast(0), minOf(all.length, start.coerceAtLeast(0) + length))
        }

        /** #781 See [ITextTools.appendClipboardLists]. */
        override fun appendClipboardLists(json: String?): Array<String> = importLists(json, replace = false)

        private fun importLists(json: String?, replace: Boolean): Array<String> {
            val dao = helium314.keyboard.latin.database.ClipboardDao.getInstance(this@TextToolsService)
                ?: return failed("the clipboard store cannot open (device locked?)")
            return runCatching { dao.importJson(org.json.JSONObject(json.orEmpty()), this@TextToolsService, replace) }
                .fold({ ok(it.toString()) }, { failed("clipboard lists not imported: ${it.javaClass.simpleName}") })
        }
    }

    private companion object {
        const val TAG = "TextToolsService"

        /**
         * The id and label given to a prompt that arrived over the binder. It is not one of this
         * app's styles and must never be mistaken for one — the caller's own screen holds the name
         * the user sees, and inventing a local label here would put a second name on one setting.
         */
        const val CALLER_STYLE_ID = "caller"

        /** [settingsSnapshot] key holding provider id → chosen model id. */
        const val SNAPSHOT_MODELS = "ai_models"

        /**
         * How much of a key may appear on a fleet console: its last four characters, and only
         * when it is long enough that four characters are not most of it.
         *
         * FOUR IS ENOUGH TO TELL TWO ACCOUNTS APART and short enough to be worth nothing to whoever
         * reads it over the owner's shoulder, which is the whole job — the console shows this so
         * the owner can confirm WHICH key a peer holds without asking for the key. A short string
         * gets no hint at all rather than a proportionally bigger one: a hint that is most of a
         * ten-character secret is not a hint.
         */
        fun keyHint(token: String): String =
            if (token.length < HINT_MIN_LENGTH) "" else token.takeLast(HINT_CHARS)

        const val HINT_CHARS = 4

        /** Below this a four-character tail is too much of the whole to show. */
        const val HINT_MIN_LENGTH = 12

        /** Slot 0 carries the result and slot 1 stays empty — see [ITextTools]. */
        fun ok(text: String) = arrayOf(text, "")

        /** Slot 0 stays empty and slot 1 carries the reason; never squash one into the other. */
        fun failed(reason: String) = arrayOf("", reason)
    }
}

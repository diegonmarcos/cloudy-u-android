package com.diegonmarcos.superapp.texttools;

/**
 * The Text tools, as one binder: rewrite this text, translate this text.
 *
 * TWO ENGINES, AND THEY ARE NOT INTERCHANGEABLE. enhance() goes through the
 * OpenRouter-shape provider chosen in AI Model Routing. translate() goes through the
 * translation library. Neither ever calls the other's engine; crossing them would
 * bill a translation to the LLM provider and route a rewrite through a translator,
 * and the caller cannot tell from the reply which one answered. The implementation
 * is asserted against exactly that.
 *
 * REPLY SHAPE: every call answers String[2] = {text, reason}. Success fills slot 0
 * and leaves slot 1 empty; failure leaves slot 0 empty and puts a user-readable
 * reason in slot 1. Never null, never a one-slot array. A binder cannot carry an
 * exception across a process boundary, and the translate engines in this repo
 * already learned that squashing a failure into an empty result is how a feature
 * comes to look "flaky" instead of broken - the reason has to travel.
 *
 * BLOCKING. Every method here does network or model work; the platform runs binder
 * transactions on a pool thread on the service side, but the CALLER's thread is the
 * one that waits. Never call these on a main thread.
 *
 * METHOD ORDER IS THE WIRE FORMAT. Transaction codes are assigned by declaration
 * order, so a method may only ever be APPENDED. Inserting one renumbers every method
 * below it, and an older installed keyboard would then answer the wrong call.
 */
interface ITextTools {

    /**
     * Rewrite [text] through the AI Model Routing provider.
     * styleId "" = the style, tone, length and output language the user pinned in
     * Text Enhancements; any other id = that one style alone (what Grammar check does).
     */
    String[] enhance(in String text, in String styleId);

    /**
     * Translate [text] through the translation library.
     * targetTag "" = the default target from Translation settings.
     */
    String[] translate(in String text, in String targetTag);

    /** Readable name of the provider enhance() routes to, for progress and error text. */
    String enhanceProviderLabel();

    /** BCP-47 tags translate() can target right now; empty when no engine is bound. */
    List<String> translateLanguages();

    /**
     * Summarise [text] through the same AI Model Routing provider enhance() uses.
     * summaryId "" = the summary shape the user pinned in Text Resume; any other id = that one.
     *
     * "RESUME" IS THE OWNER'S NAME FOR THIS AND IT MEANS SUMMARISE. Not a curriculum vitae, and
     * not resuming a paused operation. The method is spelled summarise() so the wire cannot be
     * misread even though the menus say Resume.
     *
     * A THIRD METHOD, NOT A THIRD ENGINE. It shares enhance()'s provider, key, model, timeout and
     * error wording; what differs is the prompt set it draws from (keyboard_ai.summaries) and that
     * it sends ONE request rather than splitting long input, because a summary of each piece
     * stitched together is longer than the piece it came from, not shorter.
     *
     * APPENDED, at the end, per the method-order rule above: inserting it anywhere else renumbers
     * every method below and an older installed keyboard would answer the wrong call.
     */
    String[] summarise(in String text, in String summaryId);

    /**
     * Rewrite [text] against a system prompt and a model THE CALLER chose.
     *
     * WHY THIS EXISTS, AND IT IS NOT AN OPTIMISATION. [enhance] resolves the prompt and the model
     * out of the SERVING app's preferences. That makes the serving app the only owner of those
     * settings: a second app calling it gets the keyboard's Enhance style whether it wants it or
     * not, and the only screen that could change it is the keyboard's own. The owner asked for
     * cloud-mail to have its own Enhance settings, editable in cloud-mail, and this is the method
     * that lets an app have them — it hands over its whole decision and keeps nothing here.
     *
     * WHAT STAYS ON THIS SIDE: the HTTP call, the auth header, the timeout, the chunking of input
     * past the provider's budget, the error wording, and above all the API KEY. The caller names a
     * provider; it never sees that provider's credential. One engine, several sets of settings.
     *
     * [systemPrompt] is the FULLY COMPOSED prompt — preamble, style, tone, length and output
     * language already joined by the caller from the caller's own registry. Nothing here adds to
     * it, because anything added here would be a setting this side still owned.
     *
     * [providerId] and [modelId] empty = fall back to whatever the serving app has configured.
     * That is the honest answer for a caller that has not chosen yet, and it is what the very
     * first launch after an upgrade does before its settings are seeded.
     */
    String[] enhanceWith(in String text, in String systemPrompt, in String providerId, in String modelId);

    /**
     * Summarise [text] against a system prompt and a model THE CALLER chose — [enhanceWith]'s
     * reasoning applied to Text Resume, and appended for the same wire-format rule.
     *
     * Still NOT [enhanceWith] with a different prompt: this one sends ONE request and states the
     * cut when the input did not fit, where a rewrite splits and rejoins. Crossing them yields a
     * summary per piece, concatenated — longer than the text it summarised.
     *
     * [bullets] true = the caller's prompt asked for a list, so hold the model to one. It travels
     * because it is a property OF THE CALLER'S PROMPT, and the caller is now the only side that
     * knows which of its prompts asked for bullets. The enforcement itself does not travel.
     */
    String[] summariseWith(in String text, in String systemPrompt, boolean bullets, in String providerId, in String modelId);

    /** Readable name of [providerId], for progress and error text; the configured one when empty. */
    String providerLabelFor(in String providerId);

    /**
     * The serving app's CURRENT text-tool choices, as JSON, so a caller adopting its own copy of
     * these settings can start from what the owner already configured rather than from defaults.
     *
     * READ ONCE, AT SEEDING, AND NEVER AGAIN. A caller that kept reading this would be back to
     * having no settings of its own. Keys mirror the preference names; see MailTextToolsPrefs.
     *
     * NO CREDENTIAL IS IN IT. The API key is deliberately absent and must stay absent: this method
     * exists so a second app can copy the owner's CHOICES, not their account. A snapshot that
     * carried the token would put the key in a second app's storage, which is the one thing the
     * whole binder design exists to prevent.
     */
    String settingsSnapshot();

    /**
     * THE SERVING APP'S WHOLE AI-ROUTING STATE, AS JSON, FOR A FLEET CONSOLE TO SHOW AND EDIT.
     * Read by cloud-superapp's Configs > AI > Tokens Fleet and by nothing else today.
     *
     * NO KEY IS IN IT. `key_present` says whether a provider holds one and `key_hint` carries at
     * most its last four characters so the owner can tell two accounts apart. #1801 for a provider
     * that needs a key both come from the AI-key owner engine's `keys` status (the engine holds the
     * key; nothing in this file ever carries it). Anything added to this object later has to pass
     * that same test - see settingsSnapshot() above, which draws the same line for the same reason.
     *
     * IT CARRIES THE MODEL REGISTRY, and that is the main reason it exists rather than being three
     * smaller methods. A console that listed models from a copy of its own would be a second
     * registry to go stale, which is what produced this fleet's stale-price and wrong-unit bugs;
     * shipping the rows themselves means the console can only ever show what the serving app
     * actually holds. Every model row is the registry's own: id, name, open, params_b, quant,
     * trained_for, note, and the two prices.
     *
     * PRICES ARE UNITED STATES DOLLARS PER MILLION TOKENS - the registry's unit, unconverted, under
     * the key names prompt_usd_per_million / completion_usd_per_million so no reader can mistake
     * them for the per-token figure OpenRouter publishes or for the cents this fleet briefly showed.
     *
     * SHAPE: {app, default_provider, providers:[{id, label, needs_token, default_model,
     * chosen_model, key_present, key_hint, pricing_as_of, models:[...]}]}.
     *
     * ANSWERS null FROM AN OLDER SERVING APP, and callers must handle that. A keyboard installed
     * before this method existed has no transaction code for it, so the reply parcel comes back
     * untouched and this reads as null rather than throwing. A caller that is BOUND and gets null
     * here has a peer too old to answer, which is a different thing from a peer that is not
     * installed, and the two must not be reported as one.
     */
    String aiRoutingSnapshot();

    /**
     * #1801 RETIRED SLOT (was setAiRouting, which carried the provider key INTO the serving app). The fleet's AI provider
     * keys live in the AI-key owner engine (Cloud-Lib-Decisions-Engine, its AI keys screen) and nowhere else, so no
     * ITextTools method takes or returns one (ac_cloud-mail/test/test-mail-text-tools.sh K1).
     *
     * THE SLOT IS KEPT, NOT DELETED, because a method's transaction code is its position: deleting it would renumber
     * clipboardLists and every method after it, and an older client would reach the wrong call. It takes nothing and
     * answers {"", reason}: an older console that still sends the four arguments gets that refusal (the arguments are
     * left unread in its parcel), never a write. The model choice it also carried is the serving app's AI Routing screen.
     */
    String[] retiredRoutingWrite();

    /**
     * #1801 RETIRED SLOT (was revealAiKey, the one method that emitted a credential). Kept for the same position reason;
     * it takes nothing and answers {"", reason} naming the AI-key owner engine, where the key is revealed now (its own
     * screen, in its own process). An older client that sends a provider id gets that refusal and no key.
     */
    String[] retiredRevealSlot();

    /**
     * #781 The serving app's clipboard lists (the keyboard's autocomplete pins) as ONE JSON
     * object: {version, tabs:[{listName, file, count}], files:{file: [{timeStamp, text, mimeTypes}]}}
     * - the same bytes its Settings ▸ Clipboard ▸ Export JSON writes, which is also what the vault's
     * `autocomplete` section holds. The SuperApp's Account ▸ Runtime compares the two.
     *
     * LAST IN THIS FILE ON PURPOSE: an AIDL method's transaction code is its position, so a method
     * added anywhere else would renumber every one after it for clients built before it. An older
     * serving app answers null (no transaction for it), which the caller reports as "not reporting".
     *
     * The lists can hold personal data and keys (the owner's "Cloud Keys" list); the same
     * CONSTELLATION_DATA signature guard applies, and neither side may log it.
     */
    String clipboardLists();

    /**
     * #781 The write half of clipboardLists: REPLACE the serving app's text clips with [json], the
     * same {tabs, files} object (the vault's `autocomplete` section, reassembled by Account ▸ Apply
     * all). Every list is parsed before anything is deleted, so a malformed body leaves the store
     * as it was; file-backed clips are kept. Answers {count, ""} or {"", reason}.
     *
     * APPENDED LAST, for the reason clipboardLists gives. An older serving app has no transaction
     * for it and the call fails, which the caller reports instead of claiming an apply. Same
     * CONSTELLATION_DATA guard and the same never-log rule: the lists hold the owner's keys.
     */
    String[] importClipboardLists(in String json);

    /**
     * #781 importClipboardLists without the replace: ADDS [json]'s clips. A binder call carries
     * about 1 MB and the owner's lists are larger (1.3 MB, 2026-10-09), so the caller sends the
     * first chunk through importClipboardLists and the rest through this. Appended last, same
     * guard, same never-log rule.
     */
    String[] appendClipboardLists(in String json);

    /**
     * #781 clipboardLists in pieces: chars [start, start + length) of the same JSON text, "" past
     * the end, null when the store cannot open. clipboardLists returns the whole 1.3 MB in one reply
     * and dies on the ~1 MB binder limit (2026-10-09: Runtime read "no lists" right after a good
     * apply), so the reader asks for slices until one comes back short. Appended last; same guard.
     */
    String clipboardListsSlice(int start, int length);
}

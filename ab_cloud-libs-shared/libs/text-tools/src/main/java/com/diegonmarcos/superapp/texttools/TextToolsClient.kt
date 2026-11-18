package com.diegonmarcos.superapp.texttools

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import android.util.Log

/**
 * Binds the [ITextTools] of the most preferred installed app in
 * [TextTools.SERVICE_PACKAGES] and calls it.
 *
 * The bind lifecycle is the translate client's, deliberately, because each branch of
 * it is a way that client went "not connected" for good until its process died:
 *  - the service's app was not installed when this client was constructed and was
 *    installed later: the initial bindService() returns FALSE with no exception, and
 *    nothing ever retried. Every use with no binder re-attempts, rate-limited to one
 *    try per [REBIND_MS].
 *  - the service's app was updated: Android reports that as onBindingDied, NOT
 *    onServiceDisconnected, and a died binding never comes back on its own — it has
 *    to be unbound and bound again.
 *  - the service's process was killed: onServiceDisconnected; BIND_AUTO_CREATE
 *    reconnects by itself, the explicit rebind only makes it immediate.
 *
 * Every call BLOCKS on the calling thread — see [ITextTools]. Callers put it on a
 * background dispatcher and show progress; this class does no threading of its own,
 * because a client that quietly moved the work would also quietly swallow the point
 * at which the caller is supposed to say "working…".
 */
class TextToolsClient(context: Context) {

    private val app = context.applicationContext

    @Volatile private var tools: ITextTools? = null
    @Volatile private var lastBindAttempt = 0L

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            tools = ITextTools.Stub.asInterface(binder)
            Log.i(TAG, "connected to $name")
        }

        override fun onServiceDisconnected(name: ComponentName) {
            tools = null
            bind()
        }

        override fun onBindingDied(name: ComponentName) {
            tools = null
            runCatching { app.unbindService(this) }
            Log.w(TAG, "binding to $name died — rebinding")
            bind()
        }

        override fun onNullBinding(name: ComponentName) {
            Log.w(TAG, "$name returned a null binder — a build without TextToolsService?")
        }
    }

    init { bind() }

    /**
     * WHICH app answers, decided fresh on every bind — the most preferred entry of
     * [TextTools.SERVICE_PACKAGES] that actually publishes [TextTools.ACTION] on this phone.
     *
     * ASKED OF THE PACKAGE MANAGER, NOT ASSUMED. Binding down the list blindly would ask
     * the platform to start a package that may not be installed, and `bindService` reports
     * that as a bare `false` with no way to tell "not installed" from "installed and slow".
     * Resolving first means the failure this method can produce is one honest state: nothing
     * on the list is here.
     *
     * BY INTENT, NEVER BY `getPackageInfo`. This module's manifest grants Android 11+
     * package visibility scoped to [TextTools.ACTION] and nothing wider, so a service that
     * publishes the action resolves while a bare package lookup can still come back empty
     * and read as an uninstall.
     *
     * RE-RESOLVED ON EVERY BIND ON PURPOSE, and that is what makes the handover free: the
     * rebind that follows an `onBindingDied` — which is how Android reports the peer being
     * updated, installed or removed — picks up the new answer without anything else having
     * to notice that the fleet's text tools changed address.
     */
    private fun resolveServingPackage(): String? {
        val publishing = runCatching {
            app.packageManager.queryIntentServices(Intent(TextTools.ACTION), 0)
                .mapNotNull { it.serviceInfo?.packageName }
                .toSet()
        }.getOrDefault(emptySet<String>())
        return TextTools.SERVICE_PACKAGES.firstOrNull { it in publishing }
    }

    @Synchronized
    private fun bind() {
        lastBindAttempt = SystemClock.elapsedRealtime()
        val target = resolveServingPackage()
        if (target == null) {
            // Not an error and not a retry-able hiccup: no app on this phone publishes the
            // tools. Said once per rate-limited attempt rather than swallowed, because the
            // caller's Result already carries TextTools.NOT_INSTALLED to the user and this
            // line is what tells whoever reads a bug report WHICH of the two was looked for.
            Log.w(TAG, "no installed app publishes ${TextTools.ACTION} " +
                "(looked for ${TextTools.SERVICE_PACKAGES.joinToString()})")
            return
        }
        val result = runCatching {
            app.bindService(
                Intent(TextTools.ACTION).apply { setPackage(target) },
                conn,
                Context.BIND_AUTO_CREATE,
            )
        }
        if (!result.getOrDefault(false)) {
            // bindService answers false rather than throwing when the target cannot be
            // resolved, so a failed bind and a pending one look identical from outside
            // unless this is written down. Reaching here means the service RESOLVED a
            // moment ago and still would not bind — a force-stopped peer, or one whose
            // process the platform refused to start — which is a different repair from
            // the not-installed branch above and has to read differently.
            Log.w(TAG, "bindService to $target/${TextTools.ACTION} failed " +
                "(exception=${result.exceptionOrNull()}) — is $target force-stopped?")
        }
    }

    /** The binder if bound; otherwise kick a rate-limited rebind and answer null. */
    private fun boundOrRebind(): ITextTools? {
        tools?.let { return it }
        if (SystemClock.elapsedRealtime() - lastBindAttempt > REBIND_MS) bind()
        return tools
    }

    /**
     * True when the tools can actually be called right now. Not "the client object
     * exists": this client is constructible, and constructed, on a device with none of
     * [TextTools.SERVICE_PACKAGES] installed at all. Status lines must ask this one.
     */
    fun isConnected(): Boolean = boundOrRebind() != null

    /**
     * Rewrite [text] through AI Model Routing — the OpenRouter path, never the
     * translator. [styleId] defaults to whatever the user pinned in Text Enhancements.
     */
    fun enhance(text: String, styleId: String = TextTools.STYLE_CONFIGURED): TextTools.Result =
        call("enhance") { it.enhance(text, styleId) }

    /**
     * Translate [text] through the translation library — never the LLM provider.
     * [targetTag] defaults to the target pinned in Translation settings.
     */
    fun translate(text: String, targetTag: String = TextTools.TARGET_CONFIGURED): TextTools.Result =
        call("translate") { it.translate(text, targetTag) }

    /**
     * Summarise [text] through the same AI Model Routing provider [enhance] uses — "AI Resume" /
     * "Text Resume", the owner's name for condensing a message, never a CV. [summaryId] defaults
     * to the shape pinned in Text Resume settings.
     */
    fun summarise(text: String, summaryId: String = TextTools.SUMMARY_CONFIGURED): TextTools.Result =
        call("summarise") { it.summarise(text, summaryId) }

    /**
     * Rewrite [text] against a prompt and a model THIS app chose — the call for an app that
     * holds its own AI Model Routing and Text Enhancement settings.
     *
     * [enhance] asks the serving app to decide; this one decides here and sends the decision.
     * That is the whole difference, and it is the difference between having settings and only
     * being shown someone else's. What is still not sent, in either direction, is the API key.
     *
     * [systemPrompt] must be fully composed by the caller — preamble, style, tone, length and
     * language already joined. Nothing on the far side adds to it.
     */
    fun enhanceWith(
        text: String,
        systemPrompt: String,
        providerId: String,
        modelId: String,
    ): TextTools.Result = call("enhanceWith") { it.enhanceWith(text, systemPrompt, providerId, modelId) }

    /**
     * Summarise [text] against a prompt and a model THIS app chose — [enhanceWith] for Text
     * Resume. [bullets] tells the far side whether this caller's prompt asked for a list, which
     * is now the only side that knows.
     */
    fun summariseWith(
        text: String,
        systemPrompt: String,
        bullets: Boolean,
        providerId: String,
        modelId: String,
    ): TextTools.Result =
        call("summariseWith") { it.summariseWith(text, systemPrompt, bullets, providerId, modelId) }

    /** What to name the provider in progress and error text; null when nothing is bound. */
    fun enhanceProviderLabel(): String? =
        boundOrRebind()?.let { runCatching { it.enhanceProviderLabel() }.getOrNull() }

    /** Readable name of [providerId] — what to call THIS app's chosen provider on screen. */
    fun providerLabelFor(providerId: String): String? =
        boundOrRebind()?.let { runCatching { it.providerLabelFor(providerId) }.getOrNull() }

    /**
     * The serving app's current text-tool choices as JSON, or null when nothing is bound.
     *
     * FOR SEEDING A COPY OF THEM, ONCE. A caller that reads this on every use has not got its
     * own settings, it has a cache of somebody else's. Contains no credential, by construction
     * on the far side — see `ITextTools.settingsSnapshot`.
     */
    fun settingsSnapshot(): String? =
        boundOrRebind()?.let { runCatching { it.settingsSnapshot() }.getOrNull() }

    /** Target languages the translation engine can reach; empty when nothing is bound. */
    fun translateLanguages(): List<String> =
        boundOrRebind()?.let { runCatching { it.translateLanguages() }.getOrNull() }.orEmpty()

    /**
     * Is ANY serving app INSTALLED — asked of the package manager, not of the binding.
     *
     * [isConnected] cannot answer this. It is false both for a peer that is not on the phone
     * and for one that is installed but force-stopped or still binding, and a fleet console that
     * reported those as one thing would tell the owner to install an app they already have. This
     * asks the only question that separates them.
     *
     * ANY, not the preferred one: the honest question a console asks is "can the tools work at
     * all", and answering it with only the head of [TextTools.SERVICE_PACKAGES] would report a
     * phone with a perfectly good Cloud Keyboard on it as having no text tools, and send the
     * owner to install something that would change nothing about whether the tools run.
     *
     * See [resolveServingPackage] for why this is a service resolution and never getPackageInfo.
     */
    fun isServingAppInstalled(): Boolean = resolveServingPackage() != null

    /**
     * The serving app's AI-Routing state as JSON — providers, their models, which model is chosen
     * and whether a key is held. Null when nothing is bound OR when the serving app is too old to
     * know the call.
     *
     * THE NULL IS TWO DIFFERENT FACTS and the caller has to separate them with [isConnected]: not
     * bound means the peer is absent or asleep, bound-and-null means it answered nothing because
     * its build predates this method. Both are honest states for a console to name and they are not
     * the same repair — one is an install, the other is an update.
     *
     * NO CREDENTIAL IS IN THIS. Key presence and a four-character hint travel; the key does not.
     * See `ITextTools.aiRoutingSnapshot`.
     *
     * BLOCKS, like everything else here. Callers put it on a background thread AND give it a
     * deadline — a peer that has wedged will never answer, and a settings page with no deadline
     * waits for it forever.
     */
    fun aiRoutingSnapshot(): String? =
        boundOrRebind()?.let { runCatching { it.aiRoutingSnapshot() }.getOrNull() }

    // #1801 setAiRouting and revealAiKey are gone from this client and from ITextTools (their wire slots are retired,
    // credential-free): the fleet's AI provider keys live in the AI-key owner engine. Set, reveal or clear one with
    // DecisionsLink.openKeySettings (libs:decisions-link); spend one with DecisionsLink.relay; read whether one is held
    // with DecisionsLink.keys or aiRoutingSnapshot's key_present.

    /**
     * #781 The serving app's clipboard lists as one JSON text ([ITextTools.clipboardLists]); null
     * when unbound, or from a serving app older than the method. Never log it: the lists hold keys.
     */
    fun clipboardLists(): String? {
        val t = boundOrRebind() ?: return null
        // Sliced first: the whole export can outgrow one binder reply. A keyboard older than the
        // slice method throws here, and gets the one-shot call it understands.
        val sliced = runCatching {
            val sb = StringBuilder()
            while (true) {
                val s = t.clipboardListsSlice(sb.length, SLICE_CHARS) ?: return@runCatching null
                sb.append(s)
                if (s.length < SLICE_CHARS) break
            }
            sb.toString()
        }
        // An unknown transaction may also answer null rather than throw: either way, one-shot.
        return sliced.getOrNull()?.takeIf { it.isNotEmpty() } ?: runCatching { t.clipboardLists() }.getOrNull()
    }

    /** #781 Replace the serving app's clipboard lists with [json] ([ITextTools.importClipboardLists]); text = count. */
    fun importClipboardLists(json: String): TextTools.Result = call("importClipboardLists") { it.importClipboardLists(json) }

    /** #781 Add [json]'s clips without replacing ([ITextTools.appendClipboardLists]); text = count. */
    fun appendClipboardLists(json: String): TextTools.Result = call("appendClipboardLists") { it.appendClipboardLists(json) }

    private inline fun call(what: String, body: (ITextTools) -> Array<String>?): TextTools.Result {
        val t = boundOrRebind() ?: return TextTools.Result.failed(TextTools.NOT_INSTALLED)
        return runCatching { TextTools.Result.of(body(t)) }.getOrElse {
            // A binder that dies mid-call throws here rather than answering; the user
            // gets the reason, not a no-op.
            Log.w(TAG, "$what call failed", it)
            TextTools.Result.failed("Text tools call failed: ${it.message ?: it.javaClass.simpleName}")
        }
    }

    private companion object {
        const val TAG = "TextToolsClient"
        const val REBIND_MS = 5_000L

        /** Chars per clipboardListsSlice reply: ~400 KB on the wire, well under the ~1 MB binder limit. */
        const val SLICE_CHARS = 200_000
    }
}

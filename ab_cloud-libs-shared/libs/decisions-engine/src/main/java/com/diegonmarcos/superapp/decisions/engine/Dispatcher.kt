package com.diegonmarcos.superapp.decisions.engine

import com.diegonmarcos.superapp.decisions.core.Engine
import com.diegonmarcos.superapp.decisions.core.ImageAnswer
import com.diegonmarcos.superapp.decisions.core.ImageEngine
import com.diegonmarcos.superapp.decisions.core.KeyBook
import com.diegonmarcos.superapp.decisions.core.RelayAnswer
import com.diegonmarcos.superapp.decisions.core.RelayEngine
import org.json.JSONObject

/**
 * The wire's methods over an [Engine] (and, #1799, its [ImageEngine]), with no Android in it so its suite runs on the JVM.
 * The service's `when (method)` routes to them (the engine-service tester reads that one). [engine] returns null when the
 * policy did not load: every method then answers `no_policy`, which a caller reads as "no opinion".
 */
class Dispatcher(
    private val engine: () -> Engine?,
    private val images: () -> ImageEngine?,
    /** #1801 the relay and the key book; null = not served (every relay call answers `no_policy`). */
    private val relay: () -> RelayEngine? = { null },
    private val keys: () -> KeyBook? = { null },
) {

    /** The decisions only (a trailing lambda is the engine): every image call answers `no_policy`. */
    constructor(engine: () -> Engine?) : this(engine, { null })

    fun decide(app: String, request: String): String =
        engine()?.decide(app, JSONObject(request))?.toString() ?: noPolicy()

    /** The decisions' status, with the image uses under `image` when the engine serves them. */
    fun status(app: String): String {
        val s = engine()?.status(app) ?: return noPolicy()
        images()?.let { s.put("image", it.status(app)) }
        relay()?.let { s.put("relay", it.status(app)) }
        return s.toString()
    }

    fun outcome(app: String, request: String): String =
        engine()?.outcome(app, JSONObject(request))?.toString() ?: noPolicy()

    fun consent(app: String, request: String): String =
        engine()?.setConsent(app, JSONObject(request))?.toString() ?: noPolicy()

    /** #1799 one image call; [input] is the picture the caller handed over its file descriptor. */
    fun image(app: String, request: String, input: ByteArray): ImageAnswer =
        images()?.run(app, JSONObject(request), input) ?: ImageAnswer(JSONObject(noPolicy()), null)

    /** `image` named through the JSON call: it needs the picture's file descriptors, so it is refused there. */
    fun imageWithoutFiles(): String =
        JSONObject().put("ok", false).put("reason", "bad_request").put("detail", "image is served through IDecisionsEngine.image").toString()

    /** #1801 one relay call; [body] is the request body the caller handed over its file descriptor. */
    fun relay(app: String, request: String, body: ByteArray): RelayAnswer =
        relay()?.run(app, JSONObject(request), body) ?: RelayAnswer(JSONObject(noPolicy()), null)

    /** `relay` named through the JSON call: it needs the body's file descriptors, so it is refused there. */
    fun relayWithoutFiles(): String =
        JSONObject().put("ok", false).put("reason", "bad_request").put("detail", "relay is served through IDecisionsEngine.relay").toString()

    /**
     * #1801 which provider keys the engine holds: per provider whether the fleet slot holds one and its last four characters,
     * and the per-app overrides the same way, plus the action that opens the AI keys screen. NEVER A KEY: [KeyBook.status].
     */
    fun keys(settingsAction: String): String {
        val book = keys() ?: return noPolicy()
        val labels = relay()?.providers()?.takeIf { it.isNotEmpty() } ?: mapOf(DEFAULT_PROVIDER to DEFAULT_LABEL)
        return book.status(labels).put("settings_action", settingsAction).toString()
    }

    /**
     * #1801 the one-time migration of a former reader's own copy ({provider, value, scope: "fleet"|"app"}): the fleet slot,
     * or the CALLER's per-app slot ([app] comes from the binder, never the request). The engine's key wins. Answers
     * {ok, result: stored|kept_engine} (the caller then clears its copy) or {ok:false, reason}; one journal line names
     * the app, the provider, the scope and the result, never the value.
     */
    fun importKey(app: String, request: String, journal: com.diegonmarcos.superapp.decisions.core.Journal?): String {
        val book = keys() ?: return noPolicy()
        val r = JSONObject(request)
        val provider = r.optString("provider")
        val known = relay()?.providers()?.keys?.takeIf { it.isNotEmpty() } ?: setOf(DEFAULT_PROVIDER)
        if (provider !in known) return JSONObject().put("ok", false).put("reason", "unknown_provider").toString()
        if (app.isBlank()) return JSONObject().put("ok", false).put("reason", "app_not_allowed").toString()
        val scope = r.optString("scope", "fleet")
        if (scope != "fleet" && scope != "app") return JSONObject().put("ok", false).put("reason", "bad_request").toString()
        val result = book.importOnce(provider, r.optString("value"), if (scope == "app") app else null)
        journal?.record(app, "key_import", result.wire, JSONObject().put("kind", "key_import").put("provider", provider).put("scope", scope))
        return if (result == KeyBook.Import.REFUSED) JSONObject().put("ok", false).put("reason", "bad_request").toString()
        else JSONObject().put("ok", true).put("result", result.wire).toString()
    }

    private companion object {
        const val DEFAULT_PROVIDER = "openrouter"
        const val DEFAULT_LABEL = "OpenRouter"
    }

    private fun noPolicy() = JSONObject().put("ok", false).put("reason", "no_policy").toString()
}

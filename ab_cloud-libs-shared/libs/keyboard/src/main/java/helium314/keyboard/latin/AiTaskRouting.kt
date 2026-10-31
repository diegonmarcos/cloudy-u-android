// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.edit
import helium314.keyboard.latin.utils.prefs

/** The three jobs Configs ▸ AI Routing gives a model table each. */
enum class AiTask(val id: String) {
    TRANSLATE("translate"),
    GRAMMAR("grammar"),
    /** Translation + grammar + enhancement in one rewrite (the ENHANCE key and the Enhance bar). */
    ENHANCE("enhance"),
}

/** Where a task runs: on the phone ("ML libs": ML Kit translation, the built-in grammar rules) or through OpenRouter. */
enum class AiEngine(val id: String) {
    ML("ml"), OPENROUTER("openrouter");
    companion object { fun fromId(id: String?): AiEngine? = entries.firstOrNull { it.id == id } }
}

/**
 * The decision for one task right now: what the user chose, what actually runs ([active], null = nothing can
 * run), what takes over when the chosen engine cannot ([fallback]) and why [active] is what it is.
 */
data class EngineResolution(val chosen: AiEngine, val active: AiEngine?, val fallback: AiEngine?, val reason: Reason) {
    enum class Reason { CHOSEN, OFFLINE, NO_KEY, ML_UNAVAILABLE, NOTHING_AVAILABLE }
}

/** Per-task model choice: one stored OpenRouter model id per [AiTask]. */
object AiTaskModels {
    fun key(task: AiTask) = "ai_task_model_${task.id}"
    fun get(store: KeyValueStore, task: AiTask): String? = store.get(key(task))?.takeIf { it.isNotBlank() }
    fun set(store: KeyValueStore, task: AiTask, modelId: String?) = store.put(key(task), modelId?.takeIf { it.isNotBlank() })
}

object AiTaskRouting {
    fun key(task: AiTask) = "ai_routing_${task.id}"

    /** The stored switch; ML (on-device) until the user picks OpenRouter. Enhancement has no on-device engine. */
    fun chosen(store: KeyValueStore, task: AiTask): AiEngine =
        if (task == AiTask.ENHANCE) AiEngine.OPENROUTER else AiEngine.fromId(store.get(key(task))) ?: AiEngine.ML

    fun choose(store: KeyValueStore, task: AiTask, engine: AiEngine) = store.put(key(task), engine.id)

    /**
     * Pure routing rule.
     *  - OpenRouter is usable when the phone is [online] and a key is stored ([hasKey]).
     *  - Chosen OpenRouter: OpenRouter when usable, else the on-device engine if [mlAvailable] (fallback ML).
     *  - Chosen ML: the on-device engine when [mlAvailable], else OpenRouter when usable (fallback OpenRouter,
     *    named only when a key exists, because without one it can never take over).
     */
    fun resolve(chosen: AiEngine, mlAvailable: Boolean, online: Boolean, hasKey: Boolean): EngineResolution {
        val orUsable = online && hasKey
        return when (chosen) {
            AiEngine.OPENROUTER -> {
                val fallback = if (mlAvailable) AiEngine.ML else null
                when {
                    orUsable -> EngineResolution(chosen, AiEngine.OPENROUTER, fallback, EngineResolution.Reason.CHOSEN)
                    mlAvailable -> EngineResolution(chosen, AiEngine.ML, fallback,
                        if (!online) EngineResolution.Reason.OFFLINE else EngineResolution.Reason.NO_KEY)
                    else -> EngineResolution(chosen, null, null, EngineResolution.Reason.NOTHING_AVAILABLE)
                }
            }
            AiEngine.ML -> {
                val fallback = if (hasKey) AiEngine.OPENROUTER else null
                when {
                    mlAvailable -> EngineResolution(chosen, AiEngine.ML, fallback, EngineResolution.Reason.CHOSEN)
                    orUsable -> EngineResolution(chosen, AiEngine.OPENROUTER, fallback, EngineResolution.Reason.ML_UNAVAILABLE)
                    else -> EngineResolution(chosen, null, fallback, EngineResolution.Reason.NOTHING_AVAILABLE)
                }
            }
        }
    }

    /**
     * The Grammar engine id ("off"|"local"|"remote"|"ai") GrammarChecker runs, given the stored engine and the
     * routing switch's resolved [active] engine. OpenRouter is the "ai" engine on the task's model; ML keeps the
     * stored non-AI engine (the built-in rules, or the LanguageTool server when that is what is stored); nothing
     * usable falls back to the built-in rules, which are always on the phone. "off" stays off.
     */
    fun grammarEngine(stored: String, active: AiEngine?): String = when {
        stored == "off" -> "off"
        active == AiEngine.OPENROUTER -> "ai"
        stored == "ai" -> "local"
        active == null -> "local"
        else -> stored
    }

    // ---- Android side ----------------------------------------------------------------------------

    val openRouter: AiRouter.Provider? get() = AiRouter.providers.firstOrNull { it.id == AiEngine.OPENROUTER.id }

    fun hasKey(context: Context): Boolean = openRouter?.let { AiRouter.token(context, it).isNotEmpty() } ?: false

    fun isOnline(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        cm?.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }.getOrDefault(false)

    /** On-device availability: ML Kit translation needs its engine bound; the grammar rules are always on the phone. */
    fun mlAvailable(task: AiTask): Boolean = when (task) {
        AiTask.TRANSLATE -> RoutedTranslateEngineClient.onDevice()?.isConnected() == true
        AiTask.GRAMMAR -> true
        AiTask.ENHANCE -> false
    }

    fun resolution(context: Context, task: AiTask): EngineResolution =
        resolve(chosen(context.prefs().asStore(), task), mlAvailable(task), isOnline(context), hasKey(context))

    /**
     * The OpenRouter route a task's requests go to: the model picked in its AI Routing table, or, when none
     * was picked, the general AI Model Routing choice (unchanged behaviour for anyone who never opened the tables).
     */
    fun route(context: Context, task: AiTask): AiRouter.Route {
        val p = openRouter ?: return AiRouter.route(context)
        val picked = AiTaskModels.get(context.prefs().asStore(), task) ?: return AiRouter.route(context)
        return AiRouter.Route(p, picked)
    }

    /** The model id the task uses, for display. */
    fun modelInUse(context: Context, task: AiTask): String = route(context, task).model
}

fun SharedPreferences.asStore(): KeyValueStore = object : KeyValueStore {
    override fun get(key: String): String? = getString(key, null)
    override fun put(key: String, value: String?) = edit { if (value == null) remove(key) else putString(key, value) }
}

package com.diegonmarcos.cloudsuperintel.engine

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.diegonmarcos.cloudsuperintel.BuildConfig
import com.diegonmarcos.cloudsuperintel.OpenRouterKey
import com.diegonmarcos.cloudsuperintel.OpenRouterStore
import com.diegonmarcos.cloudsuperintel.R
import com.diegonmarcos.superapp.ai.AiBackend
import com.diegonmarcos.superapp.ai.AiConfig
import com.diegonmarcos.superapp.ai.AiGatewayBackend
import com.diegonmarcos.superapp.ai.AiOpenRouterBackend
import com.diegonmarcos.superapp.ai.AiPublicFetch
import com.diegonmarcos.superapp.ai.AiTokenSource
import com.diegonmarcos.superapp.ai.AiUrlHttp
import com.diegonmarcos.superapp.aiengine.AiEngine
import com.diegonmarcos.superapp.aiengine.IAiEngine
import com.diegonmarcos.superapp.aiengine.IAiEngineCallback
import com.diegonmarcos.superapp.modelcatalogue.AgentCatalogue
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * #1855 P2 SERVES libs:ai-engine: the fleet's apps (Cloud Code first) ask Cloud SuperIntel for the providers, the models and a streamed
 * chat instead of talking to a gateway themselves. The access check is the manifest's: the service is guarded by the CONSTELLATION_DATA
 * signature permission, so the platform refuses any app not signed with the Cloud key; there is deliberately none in code.
 *
 * Every request is performed HERE with libs:ai's backends (AiGatewayBackend for the fleet gateway and claude-cli, AiOpenRouterBackend for
 * OpenRouter with the key read per send from Cloud Account's private store, [OpenRouterKey]): no client holds, sends or receives a key
 * (decisions-use guard D5). The rules are [EngineRequest], [EngineProviders], [EngineModels], [EngineChat] (JVM-tested); this class only
 * gathers the live inputs and owns the threads.
 */
class EngineService : Service() {
    private val pool = Executors.newCachedThreadPool()
    private val running = ConcurrentHashMap<String, AtomicBoolean>()

    private val app: Context get() = applicationContext
    private val cfg by lazy { AiConfig.load(app) }
    private val catalogue by lazy {
        runCatching { AgentCatalogue.parse(app.assets.open(AgentCatalogue.ASSET).bufferedReader().use { it.readText() }) }.getOrNull()
    }
    private val orStore by lazy { OpenRouterStore(File(filesDir, "openrouter"), { url -> AiPublicFetch.get(url) }) }
    private val ledger by lazy { EngineUsage(File(filesDir, "engine-usage.json")) }
    private val key by lazy { OpenRouterKey.source(app) }

    /** The gateway's /health, with a short timeout: a down mesh must not hold a caller for the chat's long one. */
    private fun gateway(): GatewayState = runCatching {
        val r = AiUrlHttp.get(BuildConfig.AI_GATEWAY_URL.trimEnd('/') + cfg.gatewayHealthPath, emptyMap(), HEALTH_TIMEOUT_MS)
        if (r.code in 200..299) GatewayState.parse(r.body) else GatewayState.DOWN
    }.getOrDefault(GatewayState.DOWN)

    private fun providerRows(): List<String> {
        val hasKey = runCatching { key().value != null }.getOrNull()
        val snap = runCatching { orStore.get() }.getOrNull()
        return EngineProviders.rows(gateway(), catalogue, hasKey, snap != null, cfg.defaultModel).map { it.encode() }
    }

    private fun modelRows(provider: String): List<String> {
        val rows = when (provider) {
            EngineProviders.OPENROUTER -> EngineModels.openRouter(runCatching { orStore.get()?.models }.getOrNull().orEmpty())
            EngineProviders.CLAUDE_CLI -> EngineModels.claude(runCatching { orStore.get()?.models }.getOrNull().orEmpty(), catalogue)
            EngineProviders.GATEWAY -> {
                val gw = gateway()
                EngineModels.gateway(catalogue, gw, if (gw.up == true) runCatching { AiGatewayBackend(cfg, BuildConfig.AI_GATEWAY_URL).models() }.getOrDefault(emptyList()) else emptyList())
            }
            else -> emptyList()
        }
        return rows.map { it.encode() }
    }

    private fun backend(provider: String): AiBackend =
        if (provider == EngineProviders.OPENROUTER) AiOpenRouterBackend(cfg, app.getString(R.string.app_name), AiTokenSource { key() })
        else AiGatewayBackend(cfg, BuildConfig.AI_GATEWAY_URL)

    private val binder = object : IAiEngine.Stub() {
        override fun version(): Int = AiEngine.VERSION
        override fun providers(): Array<String> = runCatching { providerRows() }.getOrDefault(emptyList()).toTypedArray()
        override fun models(providerId: String?): Array<String> = runCatching { modelRows(providerId.orEmpty()) }.getOrDefault(emptyList()).toTypedArray()
        override fun usage(): String = runCatching { ledger.summary() }.getOrDefault("{}")

        override fun chat(requestJson: String?, callback: IAiEngineCallback?): String {
            val (p, why) = EngineRequest.parse(requestJson)
            if (p == null) return why
            if (callback == null) return "no callback"
            val flag = AtomicBoolean(false)
            if (running.putIfAbsent(p.id, flag) != null) return "request '${p.id}' is already running"
            pool.execute {
                val out = object : AiEngine.Listener {
                    override fun onChunk(text: String) { runCatching { callback.onChunk(text) } }
                    override fun onDone(usageJson: String) { runCatching { callback.onDone(usageJson) } }
                    override fun onError(reason: String) { runCatching { callback.onError(reason) } }
                }
                try {
                    EngineChat.run(p, EngineRequest.toAi(p, EngineProviders.claudeMode(catalogue)), backend(p.provider), { flag.get() }, out) { pr, co, cost ->
                        runCatching { ledger.record(p.provider, p.model, pr, co, cost) }
                    }
                } catch (t: Throwable) {
                    out.onError(t.message ?: t.javaClass.simpleName)
                } finally {
                    running.remove(p.id, flag)
                }
            }
            return ""
        }

        override fun cancel(requestId: String?) { running[requestId.orEmpty()]?.set(true) }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        running.values.forEach { it.set(true) }
        pool.shutdownNow()
        super.onDestroy()
    }

    private companion object {
        const val HEALTH_TIMEOUT_MS = 3_000
    }
}

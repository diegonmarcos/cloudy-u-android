package com.diegonmarcos.cloudsuperintel

import android.content.Context
import com.diegonmarcos.superapp.ai.AiAgents
import com.diegonmarcos.superapp.ai.AiAttachments
import com.diegonmarcos.superapp.ai.AiImageFlow
import com.diegonmarcos.superapp.ai.AiMedia
import com.diegonmarcos.superapp.ai.AiModelTabs
import com.diegonmarcos.superapp.ai.AiOpenRouterBackend
import com.diegonmarcos.superapp.ai.AiTokenSource
import com.diegonmarcos.superapp.ai.AiChatState
import com.diegonmarcos.superapp.ai.AiConfig
import com.diegonmarcos.superapp.ai.AiDesign
import com.diegonmarcos.superapp.ai.AiFileSessions
import com.diegonmarcos.superapp.ai.AiGatewayBackend
import com.diegonmarcos.superapp.ai.AiMemoryPrefs
import com.diegonmarcos.superapp.ai.AiRequest
import com.diegonmarcos.superapp.ai.AiStandardHost
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The chats of the process: one libs:ai chat per persona (assets/si/personas.json), all over the fleet agent gateway
 * (my-ai-api, mesh only; its address is BuildConfig.AI_GATEWAY_URL, baked from the fleet catalogue) and all in ONE
 * sessions file, `<filesDir>/ai-sessions.json`. A persona's system prompt rides every request but is never stored. No
 * token is held or read here (decisions-use guard D5): the gateway holds the fleet keys, and picks Claude, Hermes,
 * Goose, OpenClaw or an OpenRouter model by the agent the page picked.
 */
object SiAi {
    private var personas: List<Persona>? = null
    private val chats = HashMap<String, AiChatState>()
    private val tuned = HashSet<String>()

    @Synchronized fun personas(ctx: Context): List<Persona> =
        personas ?: Personas.parse(ctx.assets.open(Personas.ASSET).bufferedReader().use { it.readText() }).also { personas = it }

    @Synchronized fun defaultId(ctx: Context): String =
        JSONObject(ctx.assets.open(Personas.ASSET).bufferedReader().use { it.readText() }).getString("default")

    /** The persona's chat; the router (SI) is the default. */
    @Synchronized fun chat(ctx: Context, persona: String? = null): AiChatState {
        val all = personas(ctx)
        val p = all.firstOrNull { it.id == (persona ?: defaultId(ctx)) } ?: all.first()
        return chats.getOrPut(p.id) {
            val app = ctx.applicationContext
            val cfg = AiConfig.load(app)
            AiChatState(AiStandardHost(
                cfg, store(app), AiMemoryPrefs(cfg.defaultModel, p.web, p.agent),
                gateway = AiGatewayBackend(cfg, BuildConfig.AI_GATEWAY_URL), agents = AiAgents.load(app),
                system = Personas.system(p, all), design = AiDesign.load(app), media = media(app, cfg),
            )).also { it.chooseMcps(p.mcps.toSet()) }
        }
    }

    /**
     * Image in / image out and vision (assets/ai/model-tabs.json::image_flow): a message with an image, or an ask for one, goes to OpenRouter's chat
     * completions with the key read per send from the private store (Cloud Account's secret provider, [OpenRouterKey]); the model is the picked one
     * when it draws, else the Image tab's. Everything else stays on the gateway.
     */
    private fun media(app: Context, cfg: AiConfig): AiMedia {
        val tabs = AiModelTabs.load(app)
        val key = OpenRouterKey.source(app)
        return AiMedia(
            AiOpenRouterBackend(cfg, app.getString(R.string.app_name), AiTokenSource { key() }),
            AiImageFlow(tabs), AiAttachments.imageStore(app), tabs.imageFlow.modalities,
        )
    }

    private var sessions: AiFileSessions? = null
    @Synchronized private fun store(app: Context) = sessions ?: AiFileSessions(File(app.filesDir, "ai-sessions.json")).also { sessions = it }

    /** Applies the persona's preferred model once per process, from what the gateway lists. Blocking. */
    fun tune(ctx: Context, p: Persona, chat: AiChatState) {
        if (p.models.isEmpty() || synchronized(this) { !tuned.add(p.id) }) return
        val listed = runCatching { chat.host().models().map { it.id } }.getOrDefault(emptyList())
        Personas.preferredModel(p, listed)?.let { chat.pick(it) }
    }

    /**
     * One non-streamed completion through the gateway with the agent and model the SI chat has picked (notebook
     * answers, studio outputs and news summaries). [Result] carries the text or the reason there is none.
     */
    fun complete(ctx: Context, system: String, user: String): Result<String> {
        val app = ctx.applicationContext
        val chat = chat(app)
        val cfg = AiConfig.load(app)
        val agents = AiAgents.load(app)
        val agent = agents.firstOrNull { it.id == chat.agent } ?: agents.firstOrNull()
        val wire = JSONArray().put(JSONObject().put("role", "system").put("content", system)).put(JSONObject().put("role", "user").put("content", user))
        val reply = AiGatewayBackend(cfg, BuildConfig.AI_GATEWAY_URL).chat(AiRequest(chat.model, emptyList(), agent = agent, wire = wire, stream = false)) {}
        return reply.text?.takeIf { it.isNotBlank() }?.let { Result.success(it) } ?: Result.failure(IllegalStateException(reply.error ?: "no answer"))
    }
}

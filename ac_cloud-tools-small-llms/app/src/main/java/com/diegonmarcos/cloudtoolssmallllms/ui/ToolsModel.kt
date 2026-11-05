package com.diegonmarcos.cloudtoolssmallllms.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.diegonmarcos.cloudtoolssmallllms.Decl
import com.diegonmarcos.cloudtoolssmallllms.core.OpenRouterChat
import com.diegonmarcos.cloudtoolssmallllms.core.TaskRegistry
import com.diegonmarcos.cloudtoolssmallllms.core.TokenVault
import com.diegonmarcos.cloudtoolssmallllms.core.MlCatalogue
import com.diegonmarcos.cloudtoolssmallllms.core.MlGroup
import com.diegonmarcos.cloudtoolssmallllms.core.ToolsConfig
import com.diegonmarcos.cloudtoolssmallllms.data.AccountStatus
import com.diegonmarcos.cloudtoolssmallllms.data.Catalogue
import com.diegonmarcos.cloudtoolssmallllms.data.AppDef
import com.diegonmarcos.cloudtoolssmallllms.data.FleetRetrieval
import com.diegonmarcos.cloudtoolssmallllms.data.LocalChatBackend
import com.diegonmarcos.cloudtoolssmallllms.data.Location
import com.diegonmarcos.cloudtoolssmallllms.data.ModelRow
import com.diegonmarcos.cloudtoolssmallllms.data.ModelRuntime
import com.diegonmarcos.cloudtoolssmallllms.data.ModelsData
import com.diegonmarcos.cloudtoolssmallllms.data.OllamaRuntime
import com.diegonmarcos.cloudtoolssmallllms.data.PhoneRuntime
import com.diegonmarcos.cloudtoolssmallllms.data.Retrieval
import com.diegonmarcos.cloudtoolssmallllms.data.RuntimeState
import com.diegonmarcos.cloudtoolssmallllms.data.Step
import com.diegonmarcos.cloudtoolssmallllms.data.SecretPrefs
import com.diegonmarcos.cloudtoolssmallllms.data.Stores
import com.diegonmarcos.cloudtoolssmallllms.BuildConfig
import com.diegonmarcos.superapp.ai.AiBackend
import com.diegonmarcos.superapp.ai.AiChatState
import com.diegonmarcos.superapp.ai.AiConfig
import com.diegonmarcos.superapp.ai.AiDesign
import com.diegonmarcos.superapp.ai.AiTask
import com.diegonmarcos.superapp.ai.AiFileSessions
import com.diegonmarcos.superapp.ai.AiGatewayBackend
import com.diegonmarcos.superapp.ai.AiMemoryPrefs
import com.diegonmarcos.superapp.ai.AiModel
import com.diegonmarcos.superapp.ai.AiMsg
import com.diegonmarcos.superapp.ai.AiReply
import com.diegonmarcos.superapp.ai.AiRequest
import com.diegonmarcos.superapp.ai.AiStandardHost
import com.diegonmarcos.superapp.decisions.UrlHttp
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The app's state, held once per Activity: the chat, the model data, the router and worker choices,
 * the token's mask, the Account's status and the catalogue's prices. Every network call runs on
 * [Dispatchers.IO] through :tools-core ([ToolsFlow], [OpenRouterChat]); this class only moves
 * their results into Compose state and the stores.
 */
class ToolsModel(context: Context, private val scope: CoroutineScope) {
    private val app = context.applicationContext
    val cfg: ToolsConfig = Decl.tools
    private val stores = Stores(context)
    private val vault = TokenVault(SecretPrefs(context))
    private val account = AccountStatus(context)
    val catalogue = Catalogue(context, UrlHttp, cfg.openRouter.timeoutMs)

    var section by mutableStateOf(Decl.nav.default()?.id ?: Decl.nav.bottomNav.firstOrNull().orEmpty())
    /** The open page of the section (a second-row tab); null = the section's first. */
    var page by mutableStateOf<String?>(null)
    var router by mutableStateOf(stores.router.takeIf { it in cfg.router.choices } ?: cfg.router.default)
        private set
    var workers by mutableStateOf(stores.workers())
        private set
    var masked by mutableStateOf("")
        private set
    var tokenSet by mutableStateOf(false)
        private set
    var keyStatus by mutableStateOf<String?>(null)
        private set
    var accountState by mutableStateOf<AccountStatus.State?>(null)
        private set
    var jevListed by mutableStateOf(stores.jev().first)
        private set
    var jevPrice by mutableStateOf(stores.jev().second ?: cfg.router.jevPriceIn)
        private set
    var asOf by mutableStateOf("")
        private set
    /** Bumped whenever prices change, so priced composables recompose. */
    var pricesVersion by mutableStateOf(0)
        private set

    fun registry(): TaskRegistry = TaskRegistry(cfg, catalogue.book, workers)

    init {
        refreshToken()
        scope.launch {
            withContext(Dispatchers.IO) { catalogue.stored() }
            asOf = catalogue.asOf(); pricesVersion++
            withContext(Dispatchers.IO) { catalogue.load() }
            asOf = catalogue.asOf(); pricesVersion++
        }
        refreshJev()
        refreshAccount()
    }


    // ── Chat (libs:ai's page over the fleet agent gateway) ───────────────────────────────────

    /** The model data (assets/tools-models.json): the Models and Apps sections and the AI chat's model picker. */
    val models: ModelsData = ModelsData.load(app)
    /** The Cloud Store's Lite-ML and Tiny-ML groups, derived from its fleet declaration (assets/ml-fleet.json); one per MLs page. */
    val mls: List<MlGroup> = MlCatalogue.parse(app.assets.open(ML_FLEET).bufferedReader().use { it.readText() }, Decl.mlPages)
    /** The Apps page's top filter (an id of tools-models.json::app_filters); the first is All. */
    var appFilter by mutableStateOf(models.appFilters.first().id)

    /** The Models view the switcher picked (kept across the Small / Medium / Retrieval pages). */
    var view by mutableStateOf(ModelsView.TABLE)

    /** The app open in the Apps section; null = the icon grid. */
    var appId by mutableStateOf<String?>(null)

    /** The AI chat's backend preference: "auto" (Local when the model is Loaded, else OpenRouter), "openrouter" or "local". */
    var backend by mutableStateOf(stores.backend.takeIf { it == LOCAL || it == OPENROUTER } ?: AUTO)
        private set

    /** The task the AI chat is on (a chat_tasks id): the task, not the user, picks the model. */
    var chatTask by mutableStateOf(models.chatDefaultTask)

    /** True = the next answers use the task's medium model at once (the Stronger chip). */
    var stronger by mutableStateOf(false)

    /** The model the chat is pinned to (Settings, advanced); empty = the task picks. */
    var pinned by mutableStateOf(stores.pinned)
        private set

    fun pin(model: String) {
        pinned = model
        stores.pinned = model
    }

    /** A location's address: its own url when the data gives one, else the fleet Ollama baked from the fleet declaration (BuildConfig.FLEET_OLLAMA_URL), never typed here. */
    private fun urlOf(l: Location): String? = l.url ?: BuildConfig.FLEET_OLLAMA_URL.takeIf { l.kind == "ollama" && it.isNotBlank() }

    /** The fleet embeddings + rerank service the Retrieval steps run on (null = none declared or baked). */
    private val retrieval = FleetRetrieval.create(models.retrievalService, BuildConfig.EMBED_API_URL)

    /** The runtimes of the Store, one per declared location. The state they report is [runtimeStates]; nothing else is kept. */
    val runtimes: List<ModelRuntime> = models.locations.mapNotNull { l ->
        when (l.kind) {
            "device" -> PhoneRuntime(app, l.id)
            "ollama" -> urlOf(l)?.let { OllamaRuntime(l.id, it, models.quantTags) }
            else -> null
        }
    }
    var runtimeStates by mutableStateOf<Map<String, RuntimeState>>(emptyMap())
        private set
    /** What the Store is doing right now ("install llama-3.2-1b-instruct on Fleet Ollama"). */
    var storeBusy by mutableStateOf<String?>(null)
        private set
    /** The last Store action's failure, if any. */
    var storeError by mutableStateOf<String?>(null)
        private set

    /** The first ollama-kind runtime's address: where the Local chat is sent. */
    private val localUrl: String? = models.locations.firstOrNull { it.kind == "ollama" }?.let { urlOf(it) }

    /** The names of the models currently Loaded on any runtime. */
    fun loadedNames(): Set<String> = runtimeStates.values.flatMap { st -> st.loaded.map { it.name } }.toSet()

    fun chooseBackend(b: String) {
        backend = if (b == LOCAL || b == OPENROUTER) b else AUTO
        stores.backend = backend
    }

    /** Re-reads every runtime (off the main thread): the Store and the chat's placement read the result. */
    fun refreshStore() {
        scope.launch {
            val st = withContext(Dispatchers.IO) { runtimes.associate { it.id to it.state() } }
            runtimeStates = st
        }
    }

    /** One Store action on [runtime]; [what] names it for the busy line. The runtime's own state is re-read after. */
    fun storeAction(runtime: ModelRuntime, what: String, act: (ModelRuntime) -> String?) {
        if (storeBusy != null) return
        storeBusy = what; storeError = null
        scope.launch {
            val err = withContext(Dispatchers.IO) { act(runtime) }
            storeBusy = null
            storeError = err?.let { "$what: $it" }
            refreshStore()
        }
    }

    /**
     * The one backend of the chat: routes each call by [backend]. OpenRouter goes through the fleet agent gateway
     * (the picked model out as its OpenRouter id) . Local goes to the Store's
     * runtime on its OpenAI-compatible endpoint, at no cost.
     */
    private val gateway = AiGatewayBackend(AiConfig.load(app), BuildConfig.AI_GATEWAY_URL)
    private val localChat = localUrl?.let { LocalChatBackend(AiConfig.load(app), it) }

    /** One model call on [be] (local or openrouter). */
    private fun call(be: String, task: String, req: AiRequest, onDelta: (String) -> Unit): AiReply {
        val row = models.row(req.model.orEmpty())
        if (be == LOCAL) {
            val l = localChat ?: return AiReply(null, error = "no local runtime is declared")
            return l.chat(req, onDelta)
        }
        val wire = AiRequest(row?.wireId ?: req.model, req.messages, req.web, req.nativeWeb, req.agent, req.tools, req.wire, req.stream)
        return gateway.chat(wire, onDelta)
    }

    /** Where [row] can run now under the backend preference: Local needs it Loaded, OpenRouter needs its openrouter_id. Null = nowhere. */
    private fun placeFor(row: ModelRow): String? {
        val loaded = row.model in loadedNames()
        val cloud = row.openrouterId != null
        return when (backend) {
            LOCAL -> if (loaded) LOCAL else null
            OPENROUTER -> if (cloud) OPENROUTER else null
            else -> if (loaded) LOCAL else if (cloud) OPENROUTER else null
        }
    }

    private fun chatReq(model: String, req: AiRequest) = AiRequest(model, req.messages, req.web, req.nativeWeb, req.agent, req.tools, req.wire, req.stream)

    /** One answer from [name] for [task]: the reply and the "model · backend" label of where it ran. */
    private fun ask(name: String, task: String, req: AiRequest, onDelta: (String) -> Unit): Pair<AiReply, String> {
        val row = models.row(name) ?: return AiReply(null, error = "unknown model $name") to name
        val be = placeFor(row) ?: return AiReply(null, error = "${row.model} is not available: Load it in Store, or use OpenRouter (no openrouter_id for it)") to row.model
        return call(be, task, chatReq(row.model, req), onDelta) to "${row.model} · $be"
    }

    /** A small one-shot call of a helper model (guard, judge) on [text]; null when it cannot be placed or fails. */
    private fun helper(name: String, macro: String, task: String, text: String): String? {
        val row = models.row(name) ?: return null
        val be = placeFor(row) ?: return null
        val req = AiRequest(row.model, listOf(AiMsg("user", models.stepPrompt(macro) + "\n\n" + text)), stream = false)
        return runCatching { call(be, task, req, {}).text }.getOrNull()
    }

    /** The guard (gatekeeping, automatic): the reason when [text] is flagged unsafe, else null (also when no guard can run). */
    private fun flagged(text: String, task: String): String? =
        helper(models.gateModel, "gatekeeping", task, text)?.takeIf { it.trim().startsWith("unsafe", ignoreCase = true) }?.take(160)

    /**
     * One chat turn on the user's TASK: the guard checks the question, the task's small model answers (Local when Loaded, else
     * OpenRouter; the pinned model instead when pinned), the small judge grades it and a low score asks the medium model again;
     * the guard checks the answer and the reply names the model it came from. 
     */
    private fun answerTask(req: AiRequest, onDelta: (String) -> Unit): AiReply {
        val task = models.chatTasks.firstOrNull { it.id == chatTask } ?: models.chatTasks.first()
        val question = req.messages.lastOrNull { it.role == "user" }?.content.orEmpty()
        flagged(question, "gatekeeping")?.let { return AiReply(null, error = "Blocked by the guard: $it") }
        val pin = pinned.takeIf { it.isNotBlank() && models.row(it) != null }
        var (reply, label) = ask(pin ?: if (stronger) task.medium else task.small, task.id, req, onDelta)
        if (pin == null && !stronger && reply.text != null) {
            val score = helper(models.judgeModel, "judging", "judging", "Request:\n$question\n\nAnswer:\n${reply.text}")
                ?.let { Regex("""\b(10|[1-9])\b""").find(it)?.value?.toInt() }
            if (score != null && score < models.judgeMinScore) {
                val (better, betterLabel) = ask(task.medium, task.id, req, onDelta = {})
                if (better.text != null) { reply = better; label = "$betterLabel · escalated (small scored $score)" }
            }
        }
        val text = reply.text ?: return reply
        flagged(text, "gatekeeping")?.let { return AiReply(null, error = "The guard held back the answer: $it") }
        return AiReply(text + "\n\n— " + label, reply.citations)
    }

    private val routedBackend = object : AiBackend {
        override val id: String get() = backend
        override fun models(): List<AiModel> = emptyList()
        override fun chat(req: AiRequest, onDelta: (String) -> Unit): AiReply = answerTask(req, onDelta)
    }

    // ── Apps: a mini-app runs its pipeline step by step ─────────────────────────────────────

    /** One step of a running app: what it is, where it runs and how far it got. */
    data class StepRun(val macro: String, val model: String?, val backend: String?, val status: String, val text: String?)

    /** A run of an app: the steps as they progress and the final output. */
    data class AppRun(val appId: String, val steps: List<StepRun>, val output: String?, val running: Boolean)

    var appRun by mutableStateOf<AppRun?>(null)
        private set

    /**
     * The model and backend a step runs on, by the Local / OpenRouter rules: the step's declared (or default) backend first;
     * Local needs the model Loaded in the Store, OpenRouter needs its openrouter_id; either falls back to the other when it can.
     * Null model = the step has nothing to run (no chat model: retrieval, vision) and is reported UI-only.
     */
    private fun place(a: AppDef, s: Step): Pair<ModelRow, String>? {
        if (s.macro == "seeing") return null
        val rows = s.models.mapNotNull { models.row(it) }.filter { it.chat }
        if (rows.isEmpty()) return null
        val loaded = loadedNames()
        val want = models.backend(a, s)
        fun local() = rows.firstOrNull { it.model in loaded }?.let { it to LOCAL }
        fun cloud() = rows.firstOrNull { it.openrouterId != null }?.let { it to OPENROUTER }
        return if (want == LOCAL) local() ?: cloud() else cloud() ?: local()
    }

    /** Runs [a] on [input]: each runnable step feeds its answer to the next, status per step, usage priced per step. */
    fun runApp(a: AppDef, input: String) {
        if (appRun?.running == true || input.isBlank()) return
        var steps = a.pipeline.map { StepRun(it.macro, it.models.firstOrNull(), null, "waiting", null) }
        appRun = AppRun(a.id, steps, null, true)
        scope.launch {
            var text = input
            var failed: String? = null
            for ((i, s) in a.pipeline.withIndex()) {
                val placed = place(a, s)
                steps = steps.toMutableList().also { it[i] = it[i].copy(model = placed?.first?.model ?: it[i].model, backend = placed?.second) }
                if (s.escalation) { steps = steps.toMutableList().also { it[i] = it[i].copy(status = "skipped: escalation only when needed") }; appRun = AppRun(a.id, steps, null, true); continue }
                if (s.macro == "retrieval") {
                    steps = steps.toMutableList().also { it[i] = it[i].copy(backend = FLEET, status = "asking the fleet service") }; appRun = AppRun(a.id, steps, null, true)
                    val (status, out) = withContext(Dispatchers.IO) { runRetrieval(s, text) }
                    if (out != null) text = out
                    steps = steps.toMutableList().also { it[i] = it[i].copy(status = status, text = out) }; appRun = AppRun(a.id, steps, null, true)
                    continue
                }
                if (placed == null) { steps = steps.toMutableList().also { it[i] = it[i].copy(status = "UI-only: no " + (if (s.macro == "seeing") "image input" else "runtime for this step") + " yet") }; appRun = AppRun(a.id, steps, null, true); continue }
                steps = steps.toMutableList().also { it[i] = it[i].copy(status = "running") }; appRun = AppRun(a.id, steps, null, true)
                val prompt = models.stepPrompt(s.macro) + "\n\n" + text
                val reply = withContext(Dispatchers.IO) {
                    runCatching { call(placed.second, "app:" + a.id, AiRequest(placed.first.model, listOf(AiMsg("user", prompt)), stream = false), {}) }.getOrElse { AiReply(null, error = it.message ?: it.javaClass.simpleName) }
                }
                val out = reply.text
                if (out == null) { failed = reply.error ?: "no answer"; steps = steps.toMutableList().also { it[i] = it[i].copy(status = "failed: $failed") }; break }
                text = out
                steps = steps.toMutableList().also { it[i] = it[i].copy(status = "done", text = out) }; appRun = AppRun(a.id, steps, null, true)
            }
            appRun = AppRun(a.id, steps, if (failed == null) text else null, false)
        }
    }

    /**
     * A Retrieval step on the fleet service: the input's first paragraph is the question and every later paragraph a passage.
     * A reranker step (its first model has the declared rerank role) keeps the passages the reranker scores at or above the
     * declared floor, an embedding step keeps the closest ones by cosine; the step hands on the question and what it kept.
     * Returns the step's status and its output (null = the step produced nothing and the text goes on unchanged).
     */
    private fun runRetrieval(s: Step, input: String): Pair<String, String?> {
        val svc = models.retrievalService
        val client = retrieval
        if (svc == null || client == null) return "UI-only: no fleet embeddings service is declared for this build" to null
        val (question, passages) = Retrieval.split(input)
        if (question.isBlank() || passages.isEmpty()) return "skipped: write the question, a blank line, then the passages, one paragraph each" to null
        val down = "UI-only: the fleet embeddings service did not answer (it needs the tunnel up)" to null
        val rerank = s.models.firstNotNullOfOrNull { models.row(it) }?.role == svc.rerankRole
        if (rerank) {
            val ranked = client.rerank(question, passages) ?: return down
            val kept = ranked.filter { it.second >= svc.minRerank }.take(svc.rerankTop)
            val out = if (kept.isEmpty()) question + "\n\n" + svc.noneRelevant else Retrieval.join(question, kept.map { it.first })
            return "done: reranked ${passages.size} passages on the fleet (${svc.rerankModel}), kept ${kept.size}" to out
        }
        val ranked = client.rankByEmbedding(question, passages) ?: return down
        val kept = ranked.take(svc.embedTop)
        return "done: ranked ${passages.size} passages on the fleet (${svc.embedModel}), kept ${kept.size}" to Retrieval.join(question, kept.map { it.first })
    }

    fun closeApp() {
        appId = null
        appRun = null
    }

    /**
     * The AI page's state: the open conversation, kept in a file. There is no model picker: the user picks a TASK
     * ([chatTask]) and [answerTask] picks the model; the gateway holds the fleet keys, so no token is held for it.
     */
    val chat = AiChatState(AiStandardHost(
        AiConfig.load(app), AiFileSessions(File(app.filesDir, "ai-sessions.json")),
        AiMemoryPrefs(models.chatTasks.first().small),
        direct = routedBackend,
        modelSource = { emptyList() },
        design = AiDesign.load(app),
    ))

    init {
        // The composer's Task control is the one task picker: the chat's tasks are this app's chat_tasks, mapped to the model each runs.
        chat.tasks = models.chatTasks.map { AiTask(it.id, it.label, "openrouter", it.small, it.icon, it.note) }
        chat.onTask = { if (it.isNotEmpty()) chatTask = it }
        chat.chooseTask(chatTask)
    }

    /** Apps' Try in AI: open the AI section on the task of [model]'s macro, asking the medium model when it is a medium one. */
    fun tryInAi(model: String) {
        val row = models.row(model)
        models.chatTasks.firstOrNull { it.macro == row?.macro }?.let { chatTask = it.id }
        stronger = row?.tier == "medium"
        section = Decl.SECTION_AI
        page = null
    }

    // ── settings ─────────────────────────────────────────────────────────────────────────────

    fun saveToken(raw: String): Boolean = vault.set(raw).also { refreshToken() }

    fun removeToken() {
        vault.clear()
        keyStatus = null
        refreshToken()
    }

    private fun refreshToken() {
        masked = vault.masked()
        tokenSet = vault.isSet
    }

    /** Settings' Test: GET the key endpoint with the stored token; spends nothing. */
    fun testToken(ok: (OpenRouterChat.KeyInfo) -> Unit) {
        scope.launch {
            val info = withContext(Dispatchers.IO) { OpenRouterChat.keyInfo(UrlHttp, cfg.openRouter.keyUrl, vault.token(), cfg.openRouter.timeoutMs) }
            ok(info)
        }
    }

    fun chooseRouter(id: String) {
        if (id !in cfg.router.choices) return
        router = id
        stores.router = id
    }

    fun chooseWorker(task: String, model: String?) {
        val m = workers.toMutableMap()
        if (model == null || model == cfg.task(task)?.worker) m.remove(task) else m[task] = model
        workers = m
        stores.saveWorkers(m)
    }

    /** Re-read the decision catalogue: Jev is used automatically while it lists the pinned model. */
    fun refreshJev() {
        scope.launch {
            val body = withContext(Dispatchers.IO) {
                runCatching { UrlHttp.send(cfg.openRouter.decisionModelsUrl, null, null, cfg.openRouter.timeoutMs) }.getOrNull()?.takeIf { it.code in 200..299 }?.body
            }
            val listed = OpenRouterChat.listsModel(body, cfg.router.jevModel) ?: return@launch
            val price = OpenRouterChat.decisionPrice(body, cfg.router.jevModel)
            jevListed = listed
            if (price != null) jevPrice = price
            stores.saveJev(listed, price, now())
        }
    }

    fun refreshAccount() {
        scope.launch { accountState = withContext(Dispatchers.IO) { account.read() } }
    }

    private fun now(): Long = System.currentTimeMillis()

    companion object {
        /** The task id of a chat call. */
        const val CHAT_TASK = "chat"
        const val AUTO = "auto"
        private const val ML_FLEET = "ml-fleet.json"
        const val OPENROUTER = "openrouter"
        const val LOCAL = "local"
        const val FLEET = "fleet"
    }
}

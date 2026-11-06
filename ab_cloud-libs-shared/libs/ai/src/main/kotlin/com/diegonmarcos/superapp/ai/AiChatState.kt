package com.diegonmarcos.superapp.ai

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.CompletableFuture

/** The chat's live state: the open session, the answer streaming in, what is being sent, the last error, the catalogue, the question waiting for the user. */
class AiChatState(private val host: AiHost) {
    var session by mutableStateOf(host.newSession(host.model, System.currentTimeMillis()))
    var sending by mutableStateOf(false)
    /** The answer so far, while it streams (empty when nothing has arrived or the host does not stream). */
    var streaming by mutableStateOf("")
    var error by mutableStateOf<String?>(null)
    var citations by mutableStateOf<List<String>>(emptyList())
    var models by mutableStateOf<List<AiModel>>(emptyList())
    var model by mutableStateOf(host.model)
    var web by mutableStateOf(host.web)
    var agent by mutableStateOf(host.agent())
    /** The files attached to the next message (the (+) menu's Files, Image and Camera): chips in the composer, sent with the message, then cleared. */
    var attachments by mutableStateOf<List<AiAttachment>>(emptyList())
        private set
    fun attach(a: AiAttachment) { attachments = attachments.filterNot { it.id == a.id } + a }
    fun detach(id: String) { attachments = attachments.filterNot { it.id == id } }

    /** The input's text: hoisted so an app can prefill it (a shared text, a task's example) and nothing is sent until the user does. */
    var draft by mutableStateOf("")
    /** A mutating tool waiting for Allow / Deny; the page draws the consent card for it. */
    var consent by mutableStateOf<AiConsentRequest?>(null)
        private set

    private var decision: CompletableFuture<Boolean>? = null

    /** The composer's Task control: the shared mapping (or the app's own); [task] is the selected one's id ("" = none). */
    var tasks by mutableStateOf<List<AiTask>>(emptyList())
    var task by mutableStateOf("")
        private set
    /** The composer's Permission Mode (a design mode id; "" until the page applies the design's default) and the MCPs control's servers and selection. */
    var mode by mutableStateOf("")
        private set
    var mcpServers by mutableStateOf<List<AiMcpServer>>(emptyList())
    var mcpOn by mutableStateOf<Set<String>>(emptySet())
        private set
    /** Called when the user picks a task, so an app that keeps its own task state (Cloud Tools) follows. */
    var onTask: (String) -> Unit = {}

    private var freeAgent = agent
    private var freeModel = model

    /** True while a task is selected: Agent and Model are set from the mapping and cannot be changed (the composer greys them). */
    val locked: Boolean get() = tasks.any { it.id == task }

    /** The selected task, or null. */
    val selectedTask: AiTask? get() = tasks.firstOrNull { it.id == task }

    /**
     * Selects a task ("" clears it): Agent and Model become the task's mapped ones and stay put until it is cleared,
     * when the picks made before the task come back.
     */
    fun chooseTask(id: String) {
        val t = tasks.firstOrNull { it.id == id }
        if (t != null && !locked) { freeAgent = agent; freeModel = model }
        if (t == null && locked) { agent = freeAgent; model = freeModel; host.pickAgent(agent); host.model = model }
        task = t?.id.orEmpty()
        if (t != null) { agent = t.agent; model = t.model; host.pickAgent(t.agent); host.model = t.model }
        onTask(task)
    }

    /** The composer's Effort (an [AiEffort] id; "" until picked = the model's own). */
    var effort by mutableStateOf("")
        private set
    fun chooseEffort(level: AiEffort) { effort = level.id; host.pickEffort(level.value) }

    /** The More sheet's optimisation plugins the user switched (plugin id to on/off) and the request headers they send. */
    var pluginChoice by mutableStateOf<Map<String, Boolean>>(emptyMap())
        private set
    private val pluginHeaders = LinkedHashMap<String, String>()
    fun setPlugin(item: AiMoreItem, on: Boolean) {
        pluginChoice = pluginChoice + (item.id to on)
        pluginHeaders[item.header] = if (on) "on" else "off"
        host.pickPlugins(LinkedHashMap(pluginHeaders))
    }

    /** The catalogue page that replaces the chat: "" = none, [PAGE_MODELS] (the Model button) or [PAGE_TASKS] (the Task button). */
    var page by mutableStateOf("")

    /** Whether the composer's config bar is collapsed to its one compact chip (the page loads and saves it per app). */
    var collapsed by mutableStateOf(false)

    /** Drops the last exchange (the last user message and what followed it) from the open chat; returns that message's text, or null when there is none. */
    fun undoLast(): String? {
        if (sending) return null
        val i = session.messages.indexOfLast { it.role == "user" }
        if (i < 0) return null
        val text = session.messages[i].content
        session = session.copy(messages = session.messages.take(i))
        error = null; citations = emptyList(); streaming = ""
        return text
    }

    fun chooseMode(id: String) { mode = id; host.pickMode(id) }

    fun toggleMcp(name: String) {
        mcpOn = if (name in mcpOn) mcpOn - name else mcpOn + name
        host.pickMcps(mcpOn)
    }

    /** Sets the MCP selection at once (a persona's preferred servers). */
    fun chooseMcps(names: Set<String>) { mcpOn = names; host.pickMcps(names) }

    fun chooseAgent(id: String) { if (locked) return; agent = id; host.pickAgent(id) }
    fun pick(id: String) { if (locked) return; model = id; host.model = id }
    fun toggleWeb() { web = !web; host.web = web }
    fun fresh() { session = host.newSession(model, System.currentTimeMillis()); attachments = emptyList(); error = null; citations = emptyList(); streaming = "" }
    fun open(s: AiSession) { session = s; error = null; citations = emptyList(); streaming = ""; if (s.model.isNotBlank()) model = s.model }

    /** Renames a stored session; the open one follows. A blank title is ignored. */
    fun rename(id: String, title: String) {
        val t = title.trim()
        if (t.isEmpty()) return
        host.renameSession(id, t)
        if (session.id == id) session = session.copy(title = t)
    }

    /** Deletes a stored session; deleting the open one starts a fresh chat. */
    fun remove(id: String) {
        host.deleteSession(id)
        if (session.id == id) fresh()
    }

    /** The user's answer to the waiting consent card. */
    fun decide(allow: Boolean) {
        val d = decision
        decision = null
        consent = null
        d?.complete(allow)
    }

    private val sink = object : AiSink {
        override fun delta(text: String) { streaming += text }
        override fun consent(request: AiConsentRequest): Boolean {
            val d = CompletableFuture<Boolean>()
            decision = d
            consent = request
            return try { d.get() } catch (e: Exception) { false }
        }
    }

    /**
     * His message shown at once; the answer streams in; the outcome (or why there is none) once the host returns.
     * Suspends until then; the blocking part runs off the main thread. A send while another is on its way, or an
     * empty one, does nothing.
     */
    suspend fun send(ask: String) {
        if ((ask.isEmpty() && attachments.isEmpty()) || sending) return
        sending = true
        val atts = attachments
        attachments = emptyList()
        error = null
        streaming = ""
        citations = emptyList()
        val before = session
        session = before.copy(messages = before.messages + AiMsg("user", ask, attachments = atts))
        val out = try {
            withContext(Dispatchers.IO) { host.sendWith(before, ask, atts, model, web, System.currentTimeMillis(), sink) }
        } catch (e: CancellationException) {
            sending = false
            decide(false)
            throw e
        } catch (e: Exception) {
            AiOutcome(session, e.message ?: e.javaClass.simpleName, emptyList())
        }
        session = out.session
        error = out.error
        citations = out.citations
        streaming = ""
        consent = null
        sending = false
    }

    fun host(): AiHost = host

    companion object {
        const val PAGE_MODELS = "models"
        const val PAGE_TASKS = "tasks"
    }
}

/** The test tags the page is driven by (Cloud Search's SearchShellTest, the browser's checks). */
object AiTags {
    const val PAGE = "ai_page"
    const val CHAT_INPUT = "ai_chat_input"
    const val CHAT_SEND = "ai_chat_send"
    const val NEW_CHAT = "ai_new_chat"
    const val MODEL = "ai_model"
    const val WEB = "ai_web_toggle"
    const val AGENT = "ai_agent"
    const val BAR_TOGGLE = "ai_bar_toggle"
    const val BAR_COMPACT = "ai_bar_compact"
    fun attach(id: String) = "ai_attach_$id"
    /** An attachment chip in the composer and its remove button; an image of a message, and its Save / Share / Use-as-input actions. */
    fun chip(id: String) = "ai_attach_chip_$id"
    fun chipRemove(id: String) = "ai_attach_remove_$id"
    fun image(id: String) = "ai_image_$id"
    fun imageAction(id: String, action: String) = "ai_image_${action}_$id"
    /** The Model Catalogue's search box, its status line, a result row, and a tab block's header. */
    const val CAT_SEARCH = "ai_cat_search"
    const val CAT_SEARCH_STATE = "ai_cat_search_state"
    fun catResult(id: String) = "ai_cat_result_$id"
    fun catHeader(i: Int) = "ai_cat_header_$i"
    fun effort(id: String) = "ai_effort_$id"
    fun more(id: String) = "ai_more_$id"
    fun morePlugin(id: String) = "ai_more_plugin_$id"
    const val MORE_DIALOG = "ai_more_dialog"
    /** The composer row's chips: `ai_ctl_task`, `ai_ctl_agent`, `ai_ctl_model`, `ai_ctl_mode`, `ai_ctl_mcp`. */
    fun ctl(id: String) = when (id) { "agent" -> AGENT; "model" -> MODEL; else -> "ai_ctl_$id" }
    fun task(id: String) = "ai_task_$id"
    const val TASK_NONE = "ai_task_none"
    /** The Model Catalogue page, its subpage tabs (`ai_cat_tab_frontier`...), a model row, and its close button. */
    const val CATALOGUE_PAGE = "ai_catalogue_models"
    const val CATALOGUE_CLOSE = "ai_catalogue_close"
    fun catTab(id: String) = "ai_cat_tab_$id"
    fun catRow(id: String) = "ai_cat_row_$id"
    fun catSizes(id: String) = "ai_cat_sizes_$id"
    /** The Task Catalogue page and a task group's header. */
    const val TASK_PAGE = "ai_catalogue_tasks"
    const val TASK_CLOSE = "ai_task_catalogue_close"
    fun taskGroup(id: String) = "ai_task_group_$id"
    fun mode(id: String) = "ai_mode_$id"
    fun mcp(name: String) = "ai_mcp_$name"
    /** The context-usage line above the composer bar. */
    const val CONTEXT = "ai_context_usage"
    const val SESSIONS = "ai_sessions"
    const val DRAWER = "ai_drawer"
    const val DRAWER_NEW = "ai_drawer_new"
    fun rename(id: String) = "ai_session_rename_$id"
    fun delete(id: String) = "ai_session_delete_$id"
    const val RENAME_FIELD = "ai_rename_field"
    const val RENAME_SAVE = "ai_rename_save"
    const val CONSENT = "ai_consent"
    const val CONSENT_ALLOW = "ai_consent_allow"
    const val CONSENT_DENY = "ai_consent_deny"
    fun agent(id: String) = "ai_agent_$id"
    fun engine(id: String) = "ai_engine_$id"
    fun session(id: String) = "ai_session_$id"
    fun suggestion(text: String) = "ai_suggestion_$text"
}
